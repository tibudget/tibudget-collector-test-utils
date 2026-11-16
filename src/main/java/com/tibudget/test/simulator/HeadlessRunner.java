package com.tibudget.test.simulator;

import com.google.gson.*;
import com.tibudget.api.*;
import com.tibudget.dto.MessageDto;
import com.tibudget.test.*;
import okhttp3.OkHttpClient;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.*;

/**
 * Run one collect without JavaFX controls. Keep execution sequential because collector logs use global streams.
 * Reuse the desktop state format and input contract, but use a separate workspace by default in the CLI.
 */
final class HeadlessRunner {
    private static final Gson RESULT_JSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    record Options(Path workspace, Path replay, boolean recording, boolean fresh, boolean strictBody, int stopAfter, int failAt, long timeoutMillis,
                   JsonObject inputs, JsonObject counterparties, List<String> otpCodes) {
    }

    record Outcome(String status, boolean complete, int progress, int requests, String errorType, List<CollectorDiagnostics.Finding> findings,
                   Integer mismatchRequest, String mismatchReason, JsonObject result, Path scenario, int otpCodesUsed) {
        boolean valid() {
            return errorType == null && findings.stream().noneMatch(finding -> finding.severity().equals("ERROR"));
        }
    }

    private final Supplier<CollectorPlugin> factory;

    HeadlessRunner(Supplier<CollectorPlugin> factory) {
        this.factory = factory;
    }

    Outcome run(Options options) throws Exception {
        return run(options, null);
    }

    Outcome run(Options options, OkHttpInternetProvider replaySession) throws Exception {
        Files.createDirectories(options.workspace());
        try (FileChannel channel = FileChannel.open(options.workspace().resolve("collect.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IOException("A collect is already running in this workspace");
            Files.deleteIfExists(options.workspace().resolve("stop.request"));
            return runLocked(options, replaySession);
        }
    }

    private Outcome runLocked(Options options, OkHttpInternetProvider replaySession) throws Exception {
        Path stateFile = options.workspace().resolve("state.json");
        Path cookiesFile = options.workspace().resolve("cookies.bin");
        CollectorPlugin collector = factory.get();
        if (collector.getOpenIdJSONConfiguration() != null) throw new IllegalArgumentException("Headless OpenID sign-in is not supported");
        DiagnosticRedactor redactor = new DiagnosticRedactor();
        SimulatorInputs inputs = new SimulatorInputs(factory, null);
        inputs.checkNames(options.inputs());
        Map<String, Object> values = inputs.restore(options.inputs(), redactor);
        values.values().forEach(value -> { if (value instanceof String text) redactor.remember(text); });
        String identity = inputs.identity(values);
        SimulatorState state = options.fresh() ? new SimulatorState() : SimulatorState.load(stateFile);
        if (options.fresh() || !identity.equals(state.identity)) {
            state = new SimulatorState();
            Files.deleteIfExists(cookiesFile);
            Files.deleteIfExists(stateFile);
        }
        state.identity = identity;
        // Save the pre-run snapshot before init can modify any provided object.
        JsonObject previous = snapshot(state);
        AdvancedCookieJar cookies = new AdvancedCookieJar(cookiesFile.toFile());
        OkHttpClient client = new OkHttpClient.Builder().cookieJar(cookies).connectTimeout(Duration.ofSeconds(30)).readTimeout(Duration.ofSeconds(60)).callTimeout(Duration.ofMillis(options.timeoutMillis())).build();
        OkHttpInternetProvider internet = replaySession != null ? replaySession : new OkHttpInternetProvider(client).downloadDir(options.workspace().resolve("downloads").toFile());
        if (options.replay() != null && replaySession == null) internet.replayDir(options.replay().toFile()).verifyReplayRequestBody(options.strictBody());
        if (options.recording()) internet.harDir(options.workspace().resolve("scenarios").toFile());
        if (options.stopAfter() > 0) internet.stopAfterRequest(options.stopAfter(), collector::stop);
        if (options.failAt() > 0) internet.failReplayRequest(options.failAt());
        return runInitialized(collector, inputs, values, state, previous, internet, cookies, options, redactor);
    }

    private Outcome runInitialized(CollectorPlugin collector, SimulatorInputs inputs, Map<String, Object> values, SimulatorState state, JsonObject previous,
                                   OkHttpInternetProvider internet, AdvancedCookieJar cookies, Options options, DiagnosticRedactor redactor) throws Exception {
        BoundedLog captured = new BoundedLog();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        Logger root = Logger.getLogger("");
        Handler[] handlers = root.getHandlers();
        Handler captureHandler = new StreamHandler(captured, new SimpleFormatter());
        captureHandler.setLevel(Level.ALL);
        AtomicInteger otpCodesUsed = new AtomicInteger();
        boolean complete = false;
        boolean timedOut = false;
        Throwable error = null;
        List<CollectorDiagnostics.Finding> findings = new ArrayList<>();
        try (PrintStream capture = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            System.setErr(capture);
            for (Handler handler : handlers) root.removeHandler(handler);
            root.addHandler(captureHandler);
            List<String> codes = new ArrayList<>(options.otpCodes());
            codes.forEach(redactor::remember);
            Iterator<String> otp = codes.iterator();
            CounterpartyProvider counterparties = new CounterpartyProvider() {
                @Override public String find(String key, String value) { return lookup(options.counterparties(), key, value); }
                @Override public String find(String key, Double value) { return lookup(options.counterparties(), key, String.valueOf(value)); }
            };
            FutureTask<Boolean> task = new FutureTask<>(() -> {
                collector.init(internet, counterparties, (channel, keyword, pattern, prompt) -> {
                    if (channel == null) throw new IllegalArgumentException("OTP channel is required");
                    if (!otp.hasNext()) throw new IllegalStateException("No configured OTP code remains");
                    String code = otp.next();
                    otpCodesUsed.incrementAndGet();
                    if (pattern != null && !pattern.isBlank() && !code.matches(pattern)) throw new IllegalArgumentException("Configured OTP code does not match the requested pattern");
                    return code;
                }, new PDFToolsProviderPDFBox(), new HashMap<>(state.settings), state.accounts, state.recurringPayments);
                inputs.apply(collector, values);
                if (collector.validate().stream().anyMatch(message -> message.getType() == MessageDto.MessageType.ERROR)) throw new IllegalArgumentException("Collector input validation failed");
                return collector.collect();
            });
            Thread worker = new Thread(task, "tibu-headless-collect");
            worker.setDaemon(true);
            worker.start();
            try {
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(options.timeoutMillis());
                boolean stopRequested = false;
                while (true) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) throw new TimeoutException();
                    try {
                        complete = task.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(200)), TimeUnit.NANOSECONDS);
                        break;
                    } catch (TimeoutException waiting) {
                        if (!stopRequested && Files.exists(options.workspace().resolve("stop.request"))) {
                            stopRequested = true;
                            collector.stop();
                        }
                    }
                }
            } catch (TimeoutException timeout) {
                timedOut = true;
                collector.stop();
                worker.join(1000);
                error = timeout;
            } catch (ExecutionException failure) {
                error = failure.getCause();
            }
            if (task.isDone()) {
                try {
                    findings.addAll(CollectorDiagnostics.validate(collector, previous));
                    if (complete && collector.getProgress() != 100) findings.add(new CollectorDiagnostics.Finding("WARNING", "progress.complete", "/progress", "Collect completed without reporting 100% progress.", "https://github.com/tibudget/tibudget-api/blob/0.66/src/main/java/com/tibudget/api/CollectorPlugin.java"));
                    state.update(collector);
                    state.save(options.workspace().resolve("state.json"));
                    cookies.save();
                    if (options.replay() != null && complete && error == null) internet.assertReplayComplete();
                } catch (Exception | AssertionError failure) {
                    if (error == null) error = failure;
                }
            }
        } finally {
            captureHandler.flush();
            root.removeHandler(captureHandler);
            for (Handler handler : handlers) root.addHandler(handler);
            System.setOut(originalOut);
            System.setErr(originalErr);
            Files.writeString(options.workspace().resolve("diagnostics.log"), redactor.text(captured.toString(StandardCharsets.UTF_8)));
        }
        JsonObject result;
        boolean snapshotAvailable = !timedOut;
        try {
            result = timedOut ? previous : snapshot(state);
        } catch (RuntimeException invalidResult) {
            result = previous;
            snapshotAvailable = false;
            if (error == null) error = invalidResult;
        }
        try {
            if (!snapshotAvailable) Files.deleteIfExists(options.workspace().resolve("result.json"));
            else Files.writeString(options.workspace().resolve("result.json"), RESULT_JSON.toJson(CollectorResultDiff.stabilizeUuids(result)));
        } catch (RuntimeException invalidIds) {
            Files.deleteIfExists(options.workspace().resolve("result.json"));
            findings.add(new CollectorDiagnostics.Finding("ERROR", findings.stream().anyMatch(finding -> finding.path().endsWith("/id")) ? "snapshot.identity" : "snapshot.invalid", "/", "Cannot create a stable result snapshot; check IDs, numeric values and attached files.", "https://github.com/tibudget/tibudget-api/blob/0.66/src/main/java/com/tibudget/dto/AccountDto.java"));
        }
        if (snapshotAvailable) Files.writeString(options.workspace().resolve("result-raw.json"), RESULT_JSON.toJson(result));
        else Files.deleteIfExists(options.workspace().resolve("result-raw.json"));
        Throwable mismatch = error;
        while (mismatch != null && !(mismatch instanceof ReplayMismatchException)) mismatch = mismatch.getCause();
        ReplayMismatchException replayError = mismatch instanceof ReplayMismatchException found ? found : null;
        return new Outcome(timedOut ? "TIMEOUT" : error != null ? "FAILED" : complete ? "COMPLETE" : "PAUSED", complete, timedOut ? -1 : collector.getProgress(), internet.getRequestCount(),
                error == null ? null : error.getClass().getSimpleName(), List.copyOf(findings), replayError == null ? null : replayError.getRequestNumber(),
                replayError == null ? null : replayError.getReason(), result, internet.getHarDir() == null ? null : internet.getHarDir().toPath(), otpCodesUsed.get());
    }

    static JsonObject snapshot(SimulatorState state) {
        return JsonParser.parseString(CollectorResultJson.toJson(state.accounts, state.transactions, state.recurringPayments, false)).getAsJsonObject();
    }

    private static String lookup(JsonObject mappings, String key, String value) {
        if (!mappings.has(key) || !mappings.get(key).isJsonObject()) return null;
        JsonElement uuid = mappings.getAsJsonObject(key).get(value);
        return uuid == null || uuid.isJsonNull() ? null : uuid.getAsString();
    }

    private static final class BoundedLog extends ByteArrayOutputStream {
        private static final int MAX_BYTES = 1_048_576;
        @Override public synchronized void write(int value) { if (count < MAX_BYTES) super.write(value); }
        @Override public synchronized void write(byte[] value, int offset, int length) { super.write(value, offset, Math.min(length, MAX_BYTES - count)); }
    }
}
