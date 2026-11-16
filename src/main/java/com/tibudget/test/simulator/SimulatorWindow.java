package com.tibudget.test.simulator;

import com.tibudget.api.CollectorPlugin;
import com.tibudget.api.CounterpartyProvider;
import com.tibudget.api.OTPProvider;
import com.tibudget.api.exceptions.MessagesException;
import com.tibudget.dto.*;
import com.tibudget.test.AdvancedCookieJar;
import com.tibudget.test.OkHttpInternetProvider;
import com.tibudget.test.PDFToolsProviderPDFBox;
import javafx.animation.*;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.*;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.*;
import javafx.util.Duration;
import javafx.scene.web.WebView;
import okhttp3.OkHttpClient;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.*;

/**
 * Desktop workspace for any registered CollectorPlugin, with local state and diagnostic inspectors.
 * Collectors and network requests run off the JavaFX thread; result lists are read only after collect returns.
 */
public final class SimulatorWindow extends Application {
    private static final int MAX_REQUESTS = 1000;
    private static final int MAX_LOG_CHARACTERS = 200_000;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private final DiagnosticRedactor redactor = new DiagnosticRedactor();
    private final Queue<String> logQueue = new ConcurrentLinkedQueue<>();
    private final Queue<NetworkInspector.Exchange> networkQueue = new ConcurrentLinkedQueue<>();
    private final ObservableList<NetworkInspector.Exchange> exchanges = FXCollections.observableArrayList();
    private final TreeItem<FinancialRow> recurringRoot = new TreeItem<>();
    private final TreeItem<FinancialRow> accountRoot = new TreeItem<>();
    private final TreeTableView<FinancialRow> accountTable = new TreeTableView<>(accountRoot);
    private final TreeTableView<FinancialRow> recurringTable = new TreeTableView<>(recurringRoot);
    private final TableView<NetworkInspector.Exchange> networkTable = new TableView<>();
    private final TextArea logs = textArea("Collector logs will appear here when you run a collect.");
    private final TextArea objectDetails = textArea("Select an account, a transaction or a recurring payment to inspect every field.");
    private final ListView<FileDto> attachments = new ListView<>();
    private final Label status = new Label("Ready to configure");
    private final Label progressLabel = new Label("0%");
    private final Label elapsed = new Label("00:00");
    private final Label accountCount = new Label("0");
    private final Label transactionCount = new Label("0");
    private final Label recurringCount = new Label("0");
    private final Label requestCount = new Label("0");
    private final ProgressBar progress = new ProgressBar(0);
    private final Button run = new Button("▶  Run");
    private final Circle recordIcon = new Circle(5, Color.web("#df3348"));
    private final Button record = new Button("Run & Record", recordIcon);
    private final Hyperlink scenarioLink = new Hyperlink();
    private FadeTransition recordingAnimation;
    private SimulatorSettings savedSettings;
    private final Button scratch = new Button("Run from scratch");
    private final Button stopButton = new Button("■  Stop");
    private final Button aboutButton = new Button("À propos");
    private final Button settingsButton = new Button("⚙  Settings");
    private final CheckBox followLogs = new CheckBox("Follow");
    private final Map<String, String> counterparties = new HashMap<>();
    private Map<String, Object> inputValues = new LinkedHashMap<>();
    private Supplier<CollectorPlugin> factory;
    private SimulatorInputs inputForm;
    private ResourceBundle messages;
    private Stage window;
    private Path directory;
    private volatile CollectorPlugin activeCollector;
    private volatile Thread worker;
    private volatile boolean stopRequested;
    private boolean configured;
    private boolean closing;
    private boolean openId;
    private long started;
    private int previousProgress = -1;
    private long requestTotal;
    private Timeline pulse;
    private Dialog<?> activePrompt;
    private Handler logHandler;
    private Handler[] originalHandlers;
    private Level originalLevel;
    private PrintStream originalOut;
    private PrintStream originalErr;
    private Thread shutdownHook;

    @Override
    public void start(Stage stage) {
        window = stage;
        stage.setTitle("Tibu · Collector Simulator");
        try {
            startupLog("INFO", "Starting simulator: Java " + System.getProperty("java.version") + ", JavaFX " + System.getProperty("javafx.runtime.version"));
            startupLog("INFO", "Simulator classes loaded from " + getClass().getProtectionDomain().getCodeSource().getLocation());
            startupLog("INFO", "Discovering CollectorPlugin providers.");
            List<ServiceLoader.Provider<CollectorPlugin>> providers = ServiceLoader.load(CollectorPlugin.class).stream().toList();
            startupLog("INFO", "CollectorPlugin providers found: " + providers.size());
            if (providers.size() != 1) throw new IllegalStateException("Expected exactly one CollectorPlugin in META-INF/services; found " + providers.size());
            startupLog("INFO", "Creating collector " + providers.getFirst().type().getName());
            factory = providers.getFirst()::get;
            CollectorPlugin collector = factory.get();
            try {
                messages = ResourceBundle.getBundle("messages", Locale.getDefault(), collector.getClass().getClassLoader());
            } catch (MissingResourceException ignored) {
                startupLog("WARN", "No collector messages bundle for locale " + Locale.getDefault());
                // A collector without translations can still be inspected using its class name and input names.
            }
            directory = Path.of("target", "com/tibudget/test", collector.getClass().getName());
            startupLog("INFO", "Reading collector inputs.");
            inputForm = new SimulatorInputs(factory, messages);
            try {
                savedSettings = new SimulatorSettings(Path.of(System.getProperty("user.home"), ".config", "tibudget", "simulator"), collector.getClass().getName());
                var saved = savedSettings.load();
                if (!saved.isEmpty()) {
                    inputValues = inputForm.restore(saved, redactor);
                    inputForm.protect(inputValues, redactor);
                    configured = true;
                    startupLog("INFO", "Encrypted collector inputs restored.");
                }
            } catch (Exception failure) {
                startupLog("WARN", "Saved inputs could not be restored; open Settings to enter them again (" + failure.getClass().getSimpleName() + ").");
            }
            openId = collector.getOpenIdJSONConfiguration() != null;
            installLogging();
            startupLog("INFO", "Building simulator window; OpenID configured: " + openId);
            BorderPane root = new BorderPane();
            root.getStyleClass().add("workspace");
            root.setTop(header(collector));
            SplitPane results = resultWorkspace();
            SplitPane diagnostics = new SplitPane(networkPanel(), logPanel());
            diagnostics.setDividerPositions(.54);
            SplitPane workspace = new SplitPane(results, diagnostics);
            workspace.setOrientation(Orientation.VERTICAL);
            workspace.setDividerPositions(.56);
            ScrollPane workspaceScroll = new ScrollPane(workspace);
            workspaceScroll.setFitToWidth(true);
            workspaceScroll.setFitToHeight(true);
            root.setCenter(workspaceScroll);
            scenarioLink.setId("scenarioLink");
            scenarioLink.setVisible(false);
            scenarioLink.setManaged(false);
            recordingAnimation = new FadeTransition(Duration.millis(550), recordIcon);
            recordingAnimation.setFromValue(1);
            recordingAnimation.setToValue(.15);
            recordingAnimation.setAutoReverse(true);
            recordingAnimation.setCycleCount(Animation.INDEFINITE);
            Scene scene = new Scene(root, 1440, 960);
            var stylesheet = Objects.requireNonNull(getClass().getResource("simulator.css"), "Missing simulator stylesheet: /com/tibudget/test/simulator/simulator.css");
            startupLog("INFO", "Loading simulator stylesheet: " + stylesheet);
            scene.getStylesheets().add(stylesheet.toExternalForm());
            scene.widthProperty().addListener((observable, oldValue, width) -> {
                boolean narrow = width.doubleValue() < 1080;
                Orientation orientation = narrow ? Orientation.VERTICAL : Orientation.HORIZONTAL;
                workspaceScroll.setFitToHeight(!narrow);
                workspace.setMinHeight(narrow ? 1320 : 280);
                workspace.setPrefHeight(narrow ? 1420 : Region.USE_COMPUTED_SIZE);
                if (diagnostics.getOrientation() != orientation) {
                    diagnostics.setOrientation(orientation);
                    results.setOrientation(orientation);
                    diagnostics.setDividerPositions(.5);
                    results.setDividerPositions(narrow ? .62 : .72);
                }
            });
            stage.setScene(scene);
            stage.setMinWidth(820);
            stage.setMinHeight(700);
            stage.setOnCloseRequest(event -> {
                if (isRunning()) {
                    event.consume();
                    closing = true;
                    requestStop();
                }
            });
            run.setOnAction(event -> startRun(false, false));
            record.setOnAction(event -> startRun(false, true));
            scratch.setOnAction(event -> startRun(true, false));
            stopButton.setOnAction(event -> requestStop());
            settingsButton.setOnAction(event -> showSettings());
            aboutButton.setOnAction(event -> showAbout());
            setBusy(false);
            if (configured) setStatus("Configured · ready to run", "ready");
            if (openId) setStatus("OpenID is not supported yet", "warning");
            pulse = new Timeline(new KeyFrame(Duration.millis(200), event -> refreshDiagnostics()));
            pulse.setCycleCount(Animation.INDEFINITE);
            pulse.play();
            shutdownHook = new Thread(() -> {
                stopRequested = true;
                CollectorPlugin current = activeCollector;
                if (current != null) {
                    try {
                        current.stop();
                    } catch (RuntimeException failure) {
                        appendLog("ERROR", "stop() failed during shutdown: " + failure.getClass().getSimpleName());
                    }
                }
                Platform.runLater(() -> {
                    if (activePrompt != null) activePrompt.close();
                });
                Thread currentWorker = worker;
                if (currentWorker != null) {
                    try {
                        currentWorker.join(30_000);
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, "simulator-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            startupLog("INFO", "Showing simulator window.");
            stage.show();
            startupLog("INFO", "Simulator ready. Configure inputs in Settings, then start a collect.");
        } catch (Exception | LinkageError | ServiceConfigurationError failure) {
            startupLog("ERROR", "Simulator startup failed:\n" + stackTrace(failure));
            Alert alert = new Alert(Alert.AlertType.ERROR, failure.getMessage(), ButtonType.CLOSE);
            alert.setTitle("Cannot start the simulator");
            alert.showAndWait();
            Platform.exit();
        }
    }

    private Node header(CollectorPlugin collector) {
        Label name = new Label(message("title", collector.getClass().getSimpleName()));
        name.getStyleClass().add("collector-title");
        Label description = new Label(message("description.long", message("description", "Inspect, run and debug your collector.")));
        description.setWrapText(true);
        description.getStyleClass().add("description");
        Label implementation = new Label(collector.getClass().getName());
        implementation.getStyleClass().add("implementation");
        VBox identity = new VBox(3, name, description, implementation);
        HBox.setHgrow(identity, Priority.ALWAYS);
        StackPane logo = new StackPane(new Label("T"));
        logo.getStyleClass().add("logo");
        try (InputStream stream = collector.getClass().getClassLoader().getResourceAsStream("logo.png")) {
            if (stream != null) {
                ImageView image = new ImageView(new Image(stream));
                image.setFitWidth(56);
                image.setFitHeight(56);
                image.setPreserveRatio(true);
                logo.getChildren().setAll(image);
            }
        } catch (IOException failure) {
            appendLog("WARN", "Collector logo could not be loaded.");
        }
        HBox identityRow = new HBox(12, logo, identity);
        identityRow.setAlignment(Pos.CENTER_LEFT);
        run.setId("runButton");
        record.setId("recordButton");
        scratch.setId("scratchButton");
        stopButton.setId("stopButton");
        settingsButton.setId("settingsButton");
        aboutButton.setId("aboutButton");
        run.getStyleClass().add("primary");
        stopButton.getStyleClass().add("danger");
        FlowPane actions = new FlowPane(8, 6, run, record, scratch, stopButton, settingsButton, aboutButton);
        actions.setAlignment(Pos.CENTER_RIGHT);
        HBox.setHgrow(actions, Priority.ALWAYS);
        status.getStyleClass().add("status");
        HBox controls = new HBox(16, status, spacer(), actions);
        controls.setAlignment(Pos.CENTER_LEFT);
        progress.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(progress, Priority.ALWAYS);
        progressLabel.setMinWidth(45);
        progressLabel.getStyleClass().add("progress-label");
        elapsed.getStyleClass().add("muted");
        HBox progressRow = new HBox(12, progress, progressLabel, elapsed);
        progressRow.setAlignment(Pos.CENTER_LEFT);
        HBox metrics = new HBox(12, metric("ACCOUNTS", accountCount), metric("TRANSACTIONS", transactionCount), metric("RECURRING", recurringCount), metric("REQUESTS", requestCount));
        for (Node child : metrics.getChildren()) HBox.setHgrow(child, Priority.ALWAYS);
        VBox heading = new VBox(8, identityRow, controls, scenarioLink, progressRow, metrics);
        heading.getStyleClass().add("heading");
        return heading;
    }

    private Node metric(String text, Label value) {
        Label label = new Label(text);
        label.getStyleClass().add("metric-label");
        value.getStyleClass().add("metric-value");
        HBox box = new HBox(10, value, label);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("metric");
        return box;
    }

    private SplitPane resultWorkspace() {
        accountTable.setId("accountTable");
        accountTable.setShowRoot(false);
        accountTable.setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        accountTable.setPlaceholder(new Label("Run a collect to inspect accounts and their transactions."));
        treeColumn(accountTable, "Account / transaction", 270, row -> row.label());
        treeColumn(accountTable, "ID", 160, row -> row.id());
        treeColumn(accountTable, "Type", 110, row -> row.type());
        treeColumn(accountTable, "Date", 150, row -> row.date());
        treeColumn(accountTable, "State", 100, row -> row.state());
        treeColumn(accountTable, "Amount / balance", 145, row -> row.amount());
        treeColumn(accountTable, "Files", 60, row -> row.files());
        accountTable.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, selected) -> {
            if (selected != null && selected.getValue() != null) showObject(selected.getValue().value);
        });
        recurringTable.setId("recurringTable");
        recurringTable.setShowRoot(false);
        recurringTable.setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        recurringTable.setPlaceholder(new Label("Run a collect to inspect recurring payments and their linked transactions."));
        treeColumn(recurringTable, "Recurring payment / transaction", 270, FinancialRow::label);
        treeColumn(recurringTable, "ID", 150, FinancialRow::id);
        treeColumn(recurringTable, "Account UUID", 160, FinancialRow::accountUuid);
        treeColumn(recurringTable, "Amount", 130, FinancialRow::amount);
        treeColumn(recurringTable, "Every", 110, FinancialRow::recurrence);
        treeColumn(recurringTable, "State", 100, FinancialRow::state);
        treeColumn(recurringTable, "Start / debit date", 150, FinancialRow::date);
        treeColumn(recurringTable, "End", 150, FinancialRow::endDate);
        treeColumn(recurringTable, "Files", 60, FinancialRow::files);
        recurringTable.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, selected) -> {
            if (selected != null && selected.getValue() != null) showObject(selected.getValue().value);
        });
        TabPane resultTabs = tabs(tab("Accounts & transactions", accountTable), tab("Recurring payments", recurringTable));
        resultTabs.setId("resultTabs");
        VBox resultPanel = panel("Collected data", "Accounts and recurring payments expand to show their linked transactions. Select any row for all fields.", resultTabs);
        attachments.setPrefHeight(90);
        attachments.setPlaceholder(new Label("No attached files"));
        attachments.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(FileDto file, boolean empty) {
                super.updateItem(file, empty);
                setText(empty || file == null ? null : file.getType() + " · " + file.getLabel());
            }
        });
        Button open = new Button("Open selected document ↗");
        open.disableProperty().bind(attachments.getSelectionModel().selectedItemProperty().isNull());
        open.setOnAction(event -> {
            FileDto file = attachments.getSelectionModel().getSelectedItem();
            if (file.getFile() == null || !file.getFile().isFile()) showError("Document unavailable", "The local file no longer exists.");
            else getHostServices().showDocument(file.getFile().toURI().toString());
        });
        objectDetails.setId("objectDetails");
        VBox detailContent = new VBox(10, objectDetails, attachments, open);
        VBox.setVgrow(objectDetails, Priority.ALWAYS);
        VBox detailPanel = panel("Object inspector", "Complete DTO fields, nested items, payments and metadata.", detailContent);
        detailPanel.setMinWidth(230);
        SplitPane split = new SplitPane(resultPanel, detailPanel);
        split.setDividerPositions(.72);
        return split;
    }

    private Node networkPanel() {
        TextField search = new TextField();
        search.setPromptText("Filter by URL, method or HTTP status…");
        search.setId("networkFilter");
        FilteredList<NetworkInspector.Exchange> filtered = new FilteredList<>(exchanges);
        search.textProperty().addListener((observable, oldValue, value) -> filtered.setPredicate(exchange -> (exchange.method() + " " + exchange.url() + " " + exchange.statusText()).toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT))));
        SortedList<NetworkInspector.Exchange> sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(networkTable.comparatorProperty());
        networkTable.setItems(sorted);
        networkTable.setId("networkTable");
        networkTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        networkTable.setPlaceholder(new Label("Actual HTTP exchanges, including redirects, appear during the collect."));
        column(networkTable, "#", 40, value -> Long.toString(value.id()));
        column(networkTable, "Method", 70, NetworkInspector.Exchange::method);
        column(networkTable, "Status", 65, NetworkInspector.Exchange::statusText);
        column(networkTable, "URL", 320, NetworkInspector.Exchange::url);
        column(networkTable, "Time", 80, value -> value.status() == null ? "…" : value.milliseconds() + " ms");
        column(networkTable, "Size", 70, value -> value.bytes() < 0 ? "—" : value.bytes() + " B");
        Button inspect = new Button("Inspect exchange ↗");
        inspect.disableProperty().bind(networkTable.getSelectionModel().selectedItemProperty().isNull());
        inspect.setOnAction(event -> inspectExchange(networkTable.getSelectionModel().getSelectedItem()));
        networkTable.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && networkTable.getSelectionModel().getSelectedItem() != null) inspectExchange(networkTable.getSelectionModel().getSelectedItem());
        });
        HBox tools = new HBox(10, search, inspect);
        HBox.setHgrow(search, Priority.ALWAYS);
        VBox content = new VBox(10, tools, networkTable);
        VBox.setVgrow(networkTable, Priority.ALWAYS);
        return panel("HTTP requests", "Select a request to inspect headers and body. Authentication secrets are hidden.", content);
    }

    private Node logPanel() {
        logs.setId("logs");
        logs.getStyleClass().add("log-view");
        ComboBox<String> level = new ComboBox<>();
        level.getItems().addAll("ALL", "FINE", "INFO", "WARNING", "SEVERE");
        level.setValue("INFO");
        level.setOnAction(event -> Logger.getLogger("").setLevel(Level.parse(level.getValue())));
        followLogs.setSelected(true);
        Button clear = new Button("Clear");
        clear.setOnAction(event -> logs.clear());
        HBox tools = new HBox(10, level, followLogs, spacer(), clear);
        tools.setAlignment(Pos.CENTER_LEFT);
        VBox content = new VBox(10, tools, logs);
        VBox.setVgrow(logs, Priority.ALWAYS);
        return panel("Live logs", "Collector logs, standard output and errors in one place.", content);
    }

    private void inspectExchange(NetworkInspector.Exchange selected) {
        if (selected == null) return;
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(window);
        dialog.initModality(Modality.NONE);
        dialog.setTitle("HTTP " + selected.method() + " · " + selected.statusText());
        dialog.setHeaderText(selected.method() + " " + selected.url());
        dialog.setResizable(true);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("simulator.css").toExternalForm());
        TextArea summary = textArea("");
        TextArea request = textArea("");
        TextArea response = textArea("");
        WebView htmlPreview = new WebView();
        htmlPreview.setId("htmlResponsePreview");
        htmlPreview.getEngine().setJavaScriptEnabled(false);
        TextArea jsonPreview = textArea("");
        jsonPreview.setId("jsonResponsePreview");
        StackPane previewContent = new StackPane(htmlPreview, jsonPreview);
        Tab previewTab = tab("Preview", previewContent);
        Button openUrl = new Button("Open URL in browser ↗");
        openUrl.setId("openRequestUrl");
        openUrl.setTooltip(new Tooltip("Open the displayed GET URL using the browser's own session. URLs with hidden authentication values cannot be opened."));
        openUrl.setOnAction(event -> {
            NetworkInspector.Exchange current = exchanges.stream().filter(exchange -> exchange.id() == selected.id()).findFirst().orElse(selected);
            if (HtmlResponsePreview.canOpenUrl(current.method(), current.url())) getHostServices().showDocument(current.url());
        });
        String[] loadedHtml = {null};
        Runnable refresh = () -> {
            NetworkInspector.Exchange current = exchanges.stream().filter(exchange -> exchange.id() == selected.id()).findFirst().orElse(selected);
            dialog.setTitle("HTTP " + current.method() + " · " + current.statusText());
            boolean html = current.status() != null && HtmlResponsePreview.isHtml(current.responseHeaders(), current.responseBody());
            String title = html ? HtmlResponsePreview.pageTitle(current.responseBody()) : "";
            summary.setText((html ? "Page title: " + (title.isBlank() ? "(no title)" : title) + "\n" : "") + "Started: " + current.date() + "\nStatus: " + current.statusText() + "\nDuration: " + current.milliseconds() + " ms\nSize: " + current.bytes() + " bytes\n" + current.error());
            request.setText(current.requestHeaders() + "\n" + current.requestBody());
            response.setText(current.responseHeaders() + "\n" + current.responseBody());
            String json = !html && current.status() != null ? HtmlResponsePreview.formattedJson(current.responseBody()) : null;
            previewTab.setDisable(!html && json == null);
            openUrl.setDisable(!HtmlResponsePreview.canOpenUrl(current.method(), current.url()));
            htmlPreview.setVisible(html);
            htmlPreview.setManaged(html);
            jsonPreview.setVisible(!html);
            jsonPreview.setManaged(!html);
            jsonPreview.setText(json == null ? "" : json);
            if (html && previewTab.isSelected() && !Objects.equals(loadedHtml[0], current.responseBody())) {
                loadedHtml[0] = current.responseBody();
                htmlPreview.getEngine().loadContent(HtmlResponsePreview.render(redactor.body(current.responseBody())), "text/html");
            }
        };
        javafx.collections.ListChangeListener<NetworkInspector.Exchange> updates = change -> refresh.run();
        exchanges.addListener(updates);
        dialog.setOnHidden(event -> {
            exchanges.removeListener(updates);
            htmlPreview.getEngine().load(null);
        });
        refresh.run();
        TabPane details = tabs(tab("Summary", summary), tab("Request", request), tab("Response", response), previewTab);
        // TabPane selection changes before Tab.isSelected(); load after the tab itself becomes selected.
        previewTab.selectedProperty().addListener((observable, oldValue, selectedPreview) -> {
            if (selectedPreview) refresh.run();
        });
        details.setPrefSize(900, 560);
        VBox content = new VBox(10, new HBox(openUrl), details);
        VBox.setVgrow(details, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        dialog.show();
    }

    private void showAbout() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(window);
        dialog.initModality(Modality.NONE);
        dialog.setTitle("À propos");
        dialog.setHeaderText("Tibu Collector Simulator");
        dialog.setResizable(true);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("simulator.css").toExternalForm());
        TextArea information = textArea("""
                Run resumes the saved local session. Run from scratch resets collect state and cookies, but keeps saved input settings.
                Stop asks the collector to stop and save its progress. Results update after the worker finishes.

                Settings lets you change collector inputs. Validated inputs are saved encrypted for each collector under ~/.config/tibudget/simulator/.
                The encryption key is stored in the same directory. A process with access to your OS account can read it.
                OpenID sign-in is not supported yet.

                Run & Record resumes the session and records only the requests made during that run. A resumed session can start after login.
                The red status and blinking dot indicate recording. After the run, the recording banner opens the saved scenario folder.
                Cookies, collect state, downloads and HAR files are not encrypted. HAR files can contain credentials and personal data; review and redact them before sharing.

                Select an account, transaction or recurring payment to inspect its fields and files. Select an HTTP exchange to inspect its request and response.
                The network inspector keeps the latest %d HTTP exchanges in memory. Logs include collector messages, standard output and errors.

                Local state: %s
                Cookies: %s
                Downloads: %s
                HAR scenarios: %s
                """.formatted(MAX_REQUESTS, directory.resolve("state.json").toAbsolutePath(), directory.resolve("cookies.bin").toAbsolutePath(),
                directory.resolve("downloads").toAbsolutePath(), directory.resolve("scenarios").toAbsolutePath()));
        information.setWrapText(true);
        information.setPrefSize(720, 520);
        dialog.getDialogPane().setContent(information);
        dialog.show();
    }

    private void showSettings() {
        if (isRunning()) return;
        inputForm.show(window, inputValues).ifPresent(values -> {
            inputValues = new LinkedHashMap<>(values);
            inputForm.protect(inputValues, redactor);
            configured = true;
            setStatus("Configured · ready to run", "ready");
            try {
                if (savedSettings == null) throw new IOException("Settings storage is unavailable");
                savedSettings.save(inputForm.savedValues(inputValues));
                appendLog("INFO", "Collector inputs validated and saved encrypted.");
            } catch (Exception failure) {
                appendLog("WARN", "Inputs could not be saved (" + failure.getClass().getSimpleName() + "). They remain in memory for this session.");
                showError("Settings not saved", "Your inputs remain available for this session, but encrypted storage could not be written. Check the permissions of ~/.config/tibudget/simulator.");
            }
        });
    }

    private void startRun(boolean fromScratch, boolean recording) {
        if (isRunning() || openId) return;
        if (!configured) {
            showSettings();
            if (!configured) return;
        }
        stopRequested = false;
        previousProgress = -1;
        started = System.nanoTime();
        exchanges.clear();
        networkQueue.clear();
        requestTotal = 0;
        requestCount.setText("0");
        progress.setProgress(0);
        progressLabel.setText("0%");
        setBusy(true);
        if (recording) recordingAnimation.playFromStart();
        setStatus(recording ? "● RECORDING HAR" : fromScratch ? "Running from scratch" : "Running", recording ? "recording" : "running");
        Map<String, Object> values = new LinkedHashMap<>(inputValues);
        worker = new Thread(() -> collect(fromScratch, recording, values), "tibu-collect");
        worker.start();
    }

    private void collect(boolean fromScratch, boolean recording, Map<String, Object> values) {
        Path scenario = null;
        SimulatorState state = null;
        boolean complete = false;
        Throwable failure = null;
        AdvancedCookieJar cookies = null;
        try {
            Files.createDirectories(directory);
            Path stateFile = directory.resolve("state.json");
            Path cookiesFile = directory.resolve("cookies.bin");
            String identity = inputForm.identity(values);
            if (fromScratch) {
                Files.deleteIfExists(stateFile);
                Files.deleteIfExists(cookiesFile);
                appendLog("INFO", "Starting fresh: no previous cookies, settings, accounts or recurring payments.");
            }
            state = SimulatorState.load(stateFile);
            if (!identity.equals(state.identity)) {
                state = new SimulatorState();
                Files.deleteIfExists(cookiesFile);
                appendLog("INFO", "Input identity changed or no previous state exists. Using an empty session.");
            }
            state.identity = identity;
            cookies = new AdvancedCookieJar(cookiesFile.toFile());
            OkHttpClient client = new OkHttpClient.Builder().cookieJar(cookies).addNetworkInterceptor(new NetworkInspector(redactor, networkQueue::add))
                    .connectTimeout(java.time.Duration.ofSeconds(30)).readTimeout(java.time.Duration.ofSeconds(60)).callTimeout(java.time.Duration.ofSeconds(120)).build();
            OkHttpInternetProvider internet = new OkHttpInternetProvider(client).downloadDir(directory.resolve("downloads").toFile()).rateLimiter(2);
            if (recording) {
                internet.harDir(directory.resolve("scenarios").toFile());
                scenario = internet.getHarDir().toPath().toAbsolutePath();
                appendLog("INFO", "Recording HAR scenario: " + scenario);
            }
            CollectorPlugin collector = factory.get();
            activeCollector = collector;
            collector.init(internet, counterpartyProvider(), this::requestOtp, new PDFToolsProviderPDFBox(), new HashMap<>(state.settings), state.accounts, state.recurringPayments);
            inputForm.apply(collector, values);
            for (MessageDto message : collector.validate()) {
                appendLog(message.getType().toString(), localizedMessage(message));
                if (message.getType() == MessageDto.MessageType.ERROR) throw new IllegalArgumentException("Collector validation failed");
            }
            if (stopRequested) collector.stop();
            else complete = collector.collect();
        } catch (Exception | LinkageError | AssertionError | ServiceConfigurationError | StackOverflowError exception) {
            failure = exception;
            if (exception instanceof MessagesException messagesException) {
                messagesException.getMessages().getAllMessages().forEach(message -> appendLog("ERROR", localizedMessage(message)));
            }
            appendLog("ERROR", stackTrace(exception));
        } finally {
            if (state != null) {
                try {
                    if (activeCollector != null) state.update(activeCollector);
                    state.save(directory.resolve("state.json"));
                    if (cookies != null) cookies.save();
                    appendLog("INFO", "Local state saved. Accounts: " + state.accounts.size() + ", transactions: " + state.transactions.size() + ", recurring payments: " + state.recurringPayments.size());
                } catch (Exception exception) {
                    failure = exception;
                    appendLog("ERROR", "State could not be saved: " + exception.getClass().getSimpleName());
                }
            }
            SimulatorState result = state;
            boolean finished = complete;
            Throwable error = failure;
            Path recordedScenario = scenario;
            if (recordedScenario != null) appendLog("INFO", "HAR scenario saved (including partial requests if stopped or failed): " + recordedScenario);
            Platform.runLater(() -> finishRun(result, finished, error, recordedScenario));
        }
    }

    private void finishRun(SimulatorState state, boolean complete, Throwable failure, Path scenario) {
        recordingAnimation.stop();
        recordIcon.setOpacity(1);
        if (scenario != null) {
            String outcome = failure != null ? "failed" : stopRequested ? "stopped" : complete ? "complete" : "paused";
            scenarioLink.setText("● HAR recording saved: " + scenario.getFileName() + " · " + outcome + " · open folder");
            scenarioLink.setTooltip(new Tooltip(scenario.toString()));
            scenarioLink.setOnAction(event -> getHostServices().showDocument(scenario.toUri().toString()));
            scenarioLink.setVisible(true);
            scenarioLink.setManaged(true);
        }
        refreshDiagnostics();
        if (state != null) showResults(state);
        CollectorPlugin collector = activeCollector;
        if (collector != null) showProgress(collector.getProgress());
        if (complete && collector != null && collector.getProgress() != 100) appendLog("WARN", "Collect returned true but progress is not 100%.");
        activeCollector = null;
        worker = null;
        setBusy(false);
        if (failure != null) {
            setStatus("Collect failed · inspect logs", "error");
            if (!closing) {
                Alert error = new Alert(Alert.AlertType.ERROR, "The collect failed (" + failure.getClass().getSimpleName() + "). See Live logs for details.", ButtonType.OK);
                error.initOwner(window);
                error.setTitle("Collect failed");
                error.setHeaderText("The collect could not finish.");
                error.show();
            }
        }
        else if (stopRequested) setStatus("Stopped · progress saved", "warning");
        else if (complete) setStatus("Collect complete", "ready");
        else setStatus("Paused · run again to resume", "warning");
        if (closing) Platform.exit();
    }

    private void requestStop() {
        stopRequested = true;
        stopButton.setDisable(true);
        setStatus("Stopping · waiting for collector", "warning");
        if (activePrompt != null) activePrompt.close();
        CollectorPlugin collector = activeCollector;
        if (collector != null) {
            try {
                collector.stop();
            } catch (RuntimeException exception) {
                appendLog("ERROR", "stop() failed: " + exception.getClass().getSimpleName());
            }
        }
        appendLog("INFO", "Stop requested. The worker is not interrupted; waiting for collect() to return.");
    }

    private void showResults(SimulatorState state) {
        Map<String, TreeItem<FinancialRow>> parents = new LinkedHashMap<>();
        accountRoot.getChildren().clear();
        for (AccountDto account : state.accounts) {
            TreeItem<FinancialRow> item = new TreeItem<>(new FinancialRow(account));
            item.setExpanded(true);
            parents.put(account.getUuid(), item);
            accountRoot.getChildren().add(item);
        }
        TreeItem<FinancialRow> missing = new TreeItem<>(new FinancialRow("Transactions without a returned account"));
        missing.setExpanded(true);
        for (TransactionDto transaction : state.transactions) {
            TreeItem<FinancialRow> parent = parents.getOrDefault(transaction.getAccountUuid(), missing);
            parent.getChildren().add(new TreeItem<>(new FinancialRow(transaction)));
        }
        if (!missing.getChildren().isEmpty()) {
            accountRoot.getChildren().add(missing);
            appendLog("WARN", "Some transactions reference an account UUID not returned by the collector.");
        }
        Map<String, Boolean> expandedPlans = new HashMap<>();
        for (TreeItem<FinancialRow> item : recurringRoot.getChildren()) {
            if (item.getValue().value instanceof RecurringPaymentDto plan) expandedPlans.put(plan.getUuid(), item.isExpanded());
        }
        recurringRoot.getChildren().clear();
        Map<String, TreeItem<FinancialRow>> plans = new LinkedHashMap<>();
        for (RecurringPaymentDto plan : state.recurringPayments) {
            TreeItem<FinancialRow> item = new TreeItem<>(new FinancialRow(plan));
            item.setExpanded(expandedPlans.getOrDefault(plan.getUuid(), false));
            plans.put(plan.getUuid(), item);
            recurringRoot.getChildren().add(item);
        }
        List<TransactionDto> ordered = new ArrayList<>(state.transactions);
        ordered.sort(Comparator.comparing(TransactionDto::getDateTransaction, Comparator.nullsLast(Date::compareTo))
                .thenComparing(TransactionDto::getId, Comparator.nullsLast(String::compareTo)));
        int unmatched = 0;
        for (TransactionDto transaction : ordered) {
            if (transaction.getRecurrentPaymentUuid() == null) continue;
            TreeItem<FinancialRow> parent = plans.get(transaction.getRecurrentPaymentUuid());
            if (parent != null && Objects.equals(((RecurringPaymentDto) parent.getValue().value).getAccountUuid(), transaction.getAccountUuid())) {
                parent.getChildren().add(new TreeItem<>(new FinancialRow(transaction)));
            } else {
                unmatched++;
            }
        }
        if (unmatched > 0) appendLog("WARN", unmatched + " transactions reference an unknown recurring payment or a different account UUID.");
        accountCount.setText(Integer.toString(state.accounts.size()));
        transactionCount.setText(Integer.toString(state.transactions.size()));
        recurringCount.setText(Integer.toString(state.recurringPayments.size()));
    }

    private void showObject(Object value) {
        objectDetails.setText(redactor.body(SimulatorState.JSON.toJson(value)));
        attachments.getItems().clear();
        if (value instanceof TransactionDto transaction) {
            attachments.getItems().addAll(transaction.getFiles());
            transaction.getItems().forEach(item -> attachments.getItems().addAll(item.getFiles()));
        }
    }

    private void refreshDiagnostics() {
        StringBuilder incoming = new StringBuilder();
        for (int index = 0; index < 250; index++) {
            String line = logQueue.poll();
            if (line == null) break;
            incoming.append(redactor.text(line));
        }
        if (!incoming.isEmpty()) {
            if (logs.getText().startsWith("Collector logs will")) logs.clear();
            logs.appendText(incoming.toString());
            if (logs.getLength() > MAX_LOG_CHARACTERS) logs.deleteText(0, logs.getLength() - MAX_LOG_CHARACTERS);
            if (followLogs.isSelected()) logs.positionCaret(logs.getLength());
        }
        for (int count = 0; count < 250; count++) {
            NetworkInspector.Exchange exchange = networkQueue.poll();
            if (exchange == null) break;
            int existing = -1;
            for (int index = 0; index < exchanges.size(); index++) if (exchanges.get(index).id() == exchange.id()) existing = index;
            if (existing >= 0) exchanges.set(existing, exchange);
            else {
                exchanges.add(exchange);
                if (exchange.status() == null) requestTotal++;
            }
            if (exchanges.size() > MAX_REQUESTS) exchanges.removeFirst();
        }
        requestCount.setText(Long.toString(requestTotal));
        CollectorPlugin collector = activeCollector;
        if (isRunning()) {
            long seconds = (System.nanoTime() - started) / 1_000_000_000;
            elapsed.setText(String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60));
            if (collector != null) {
                try {
                    showProgress(collector.getProgress());
                } catch (RuntimeException failure) {
                    appendLog("ERROR", "Cannot read collector progress: " + failure.getClass().getSimpleName());
                }
            }
        }
    }

    private void showProgress(int value) {
        if (value == previousProgress) return;
        if (value < 0 || value > 100) appendLog("WARN", "Invalid progress " + value + "; expected 0 to 100.");
        else if (previousProgress >= 0 && value < previousProgress) appendLog("WARN", "Progress decreased from " + previousProgress + " to " + value + ".");
        previousProgress = value;
        progress.setProgress(Math.max(0, Math.min(100, value)) / 100.0);
        progressLabel.setText(value + "%");
    }

    private String requestOtp(OTPProvider.Channel channel, String keyword, String pattern, String prompt) {
        return askOnFxThread(() -> {
            TextInputDialog dialog = new TextInputDialog();
            dialog.setTitle("Authentication code · " + channel);
            dialog.setHeaderText(keyword + " · " + channel);
            dialog.setContentText(message(prompt, prompt == null ? "Enter the authentication code" : prompt));
            dialog.initOwner(window);
            dialog.initModality(Modality.NONE);
            if (pattern != null && !pattern.isBlank()) {
                dialog.getEditor().setPromptText("Expected format: " + pattern);
                dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                    if (!dialog.getEditor().getText().matches(pattern)) {
                        dialog.getEditor().setStyle("-fx-border-color: #cc4455;");
                        event.consume();
                    }
                });
            }
            activePrompt = dialog;
            String code = stopRequested ? null : dialog.showAndWait().orElse(null);
            activePrompt = null;
            redactor.remember(code);
            return code;
        });
    }

    private CounterpartyProvider counterpartyProvider() {
        return new CounterpartyProvider() {
            @Override public String find(String key, String value) { return findCounterparty(key, value); }
            @Override public String find(String key, Double value) { return findCounterparty(key, Objects.toString(value, "")); }
        };
    }

    private String findCounterparty(String key, String value) {
        String lookup = key + "=" + value;
        if (counterparties.containsKey(lookup)) return counterparties.get(lookup);
        String uuid = askOnFxThread(() -> {
            TextInputDialog dialog = new TextInputDialog();
            dialog.initOwner(window);
            dialog.initModality(Modality.NONE);
            dialog.setTitle("Counterparty lookup");
            dialog.setHeaderText("Enter the official Tibu counterparty UUID");
            dialog.setContentText(lookup);
            dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                try {
                    String text = dialog.getEditor().getText().trim();
                    if (!UUID.fromString(text).toString().equalsIgnoreCase(text)) throw new IllegalArgumentException();
                } catch (IllegalArgumentException failure) {
                    dialog.getEditor().setStyle("-fx-border-color: #cc4455;");
                    event.consume();
                }
            });
            activePrompt = dialog;
            String answer = stopRequested ? null : dialog.showAndWait().map(String::trim).orElse(null);
            activePrompt = null;
            return answer;
        });
        if (uuid != null) counterparties.put(lookup, uuid);
        return uuid;
    }

    private <T> T askOnFxThread(Callable<T> question) {
        if (stopRequested) return null;
        FutureTask<T> task = new FutureTask<>(question);
        Platform.runLater(task);
        try {
            return task.get();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException failure) {
            appendLog("ERROR", "Input dialog failed: " + failure.getCause().getClass().getSimpleName());
            return null;
        }
    }

    private void installLogging() {
        Logger root = Logger.getLogger("");
        originalLevel = root.getLevel();
        originalHandlers = root.getHandlers();
        for (Handler handler : originalHandlers) root.removeHandler(handler);
        logHandler = new Handler() {
            private final java.util.logging.Formatter formatter = new SimpleFormatter();
            @Override public void publish(LogRecord record) {
                if (!isLoggable(record)) return;
                String line = CLOCK.format(record.getInstant()) + "  " + record.getLevel() + "  " + record.getLoggerName() + " · " + formatter.formatMessage(record) + "\n";
                if (record.getThrown() != null) line += stackTrace(record.getThrown());
                if (logQueue.size() < 5000) logQueue.add(redactor.text(line));
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logHandler.setLevel(Level.ALL);
        root.setLevel(Level.INFO);
        root.addHandler(logHandler);
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(logOutput("STDOUT"), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(logOutput("STDERR"), true, StandardCharsets.UTF_8));
    }

    private OutputStream logOutput(String level) {
        return new OutputStream() {
            private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            @Override public synchronized void write(int value) {
                if (value == '\n' || buffer.size() >= 8192) {
                    appendLog(level, buffer.toString(StandardCharsets.UTF_8));
                    buffer.reset();
                } else buffer.write(value);
            }
            @Override public synchronized void flush() {
                if (buffer.size() > 0) {
                    appendLog(level, buffer.toString(StandardCharsets.UTF_8));
                    buffer.reset();
                }
            }
        };
    }

    // Keep startup diagnostics visible even when the window cannot open and stderr is redirected.
    private void startupLog(String level, String text) {
        String safeText = redactor.text(text);
        PrintStream terminal = originalErr == null ? System.err : originalErr;
        terminal.println(CLOCK.format(Instant.now()) + "  " + level + "  " + safeText);
        appendLog(level, safeText);
    }

    private void appendLog(String level, String text) {
        if (logQueue.size() < 5000) logQueue.add(CLOCK.format(Instant.now()) + "  " + level + "  " + redactor.text(text) + "\n");
    }

    @Override
    public void stop() {
        if (pulse != null) pulse.stop();
        if (recordingAnimation != null) recordingAnimation.stop();
        if (logHandler != null) {
            Logger root = Logger.getLogger("");
            root.removeHandler(logHandler);
            for (Handler handler : originalHandlers) root.addHandler(handler);
            root.setLevel(originalLevel);
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM shutdown has already started.
            }
        }
    }

    private void setBusy(boolean busy) {
        run.setDisable(busy || openId);
        record.setDisable(busy || openId);
        scratch.setDisable(busy || openId);
        settingsButton.setDisable(busy || openId);
        stopButton.setDisable(!busy);
    }

    private boolean isRunning() {
        return worker != null && worker.isAlive();
    }

    private void setStatus(String text, String kind) {
        status.setText(text);
        status.getStyleClass().setAll("status", "status-" + kind);
    }

    private String message(String key, String fallback) {
        return messages != null && key != null && messages.containsKey(key) ? messages.getString(key) : fallback;
    }

    private String localizedMessage(MessageDto message) {
        String pattern = message(message.getMessageKey(), message.getMessageKey());
        try {
            return (message.getFieldName() == null ? "" : message.getFieldName() + ": ") + MessageFormat.format(pattern, message.getMessageArguments());
        } catch (IllegalArgumentException failure) {
            return pattern;
        }
    }

    private static String stackTrace(Throwable failure) {
        StringWriter text = new StringWriter();
        failure.printStackTrace(new PrintWriter(text));
        return text.toString();
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.CLOSE);
        alert.initOwner(window);
        alert.setTitle(title);
        alert.showAndWait();
    }

    private static TextArea textArea(String text) {
        TextArea area = new TextArea(text);
        area.setEditable(false);
        area.setWrapText(false);
        area.getStyleClass().add("inspector-text");
        return area;
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    private static VBox panel(String title, String description, Node content) {
        Label heading = new Label(title);
        heading.getStyleClass().add("panel-title");
        Label hint = new Label(description);
        hint.setWrapText(true);
        hint.getStyleClass().add("muted");
        VBox panel = new VBox(8, heading, hint, content);
        panel.getStyleClass().add("panel");
        panel.setMinHeight(120);
        VBox.setVgrow(content, Priority.ALWAYS);
        return panel;
    }

    private static Tab tab(String name, Node node) {
        return new Tab(name, node);
    }

    private static TabPane tabs(Tab... tabs) {
        TabPane pane = new TabPane(tabs);
        pane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        return pane;
    }

    private static <T> void column(TableView<T> table, String name, double width, Function<T, String> text) {
        TableColumn<T, String> column = new TableColumn<>(name);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(Objects.toString(text.apply(cell.getValue()), "")));
        table.getColumns().add(column);
    }

    private static void treeColumn(TreeTableView<FinancialRow> table, String name, double width, Function<FinancialRow, String> text) {
        TreeTableColumn<FinancialRow, String> column = new TreeTableColumn<>(name);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(Objects.toString(text.apply(cell.getValue().getValue()), "")));
        table.getColumns().add(column);
    }

    private static String date(Date value) {
        return value == null ? "" : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(value.toInstant());
    }

    private record FinancialRow(Object value) {
        String label() {
            return value instanceof AccountDto account ? account.getLabel() : value instanceof TransactionDto transaction ? transaction.getLabel()
                    : value instanceof RecurringPaymentDto plan ? plan.getLabel() : value.toString();
        }
        String id() {
            return value instanceof AccountDto account ? account.getId() : value instanceof TransactionDto transaction ? transaction.getId()
                    : value instanceof RecurringPaymentDto plan ? plan.getId() : "";
        }
        String type() {
            return value instanceof AccountDto account ? Objects.toString(account.getType(), "") : value instanceof TransactionDto transaction ? Objects.toString(transaction.getType(), "") : "";
        }
        String date() {
            return value instanceof TransactionDto transaction ? SimulatorWindow.date(transaction.getDateTransaction())
                    : value instanceof RecurringPaymentDto plan ? SimulatorWindow.date(plan.getDateStart()) : "";
        }
        String state() {
            return value instanceof AccountDto account ? account.isSyncEnabled() ? "Sync on" : "Sync off"
                    : value instanceof TransactionDto transaction ? Objects.toString(transaction.getState(), "")
                    : value instanceof RecurringPaymentDto plan ? Objects.toString(plan.getState(), "") : "";
        }
        String amount() {
            if (value instanceof AccountDto account) return String.format(Locale.ROOT, "%,.2f %s", account.getCurrentBalance(), account.getCurrencyCode());
            if (value instanceof TransactionDto transaction) return String.format(Locale.ROOT, "%,.2f %s", transaction.getAmount(), transaction.getCurrencyCode());
            if (value instanceof RecurringPaymentDto plan) return plan.getExpectedAmount() == null ? "Variable" : String.format(Locale.ROOT, "%,.2f", plan.getExpectedAmount());
            return "";
        }
        String accountUuid() {
            return value instanceof RecurringPaymentDto plan ? plan.getAccountUuid() : value instanceof TransactionDto transaction ? transaction.getAccountUuid() : "";
        }
        String recurrence() {
            return value instanceof RecurringPaymentDto plan ? plan.getRecurrenceInterval() + " " + plan.getRecurrenceUnit() : "";
        }
        String endDate() {
            return value instanceof RecurringPaymentDto plan ? SimulatorWindow.date(plan.getDateEnd()) : "";
        }
        String files() {
            return value instanceof TransactionDto transaction ? Integer.toString(transaction.getFiles().size()) : "";
        }
    }
}
