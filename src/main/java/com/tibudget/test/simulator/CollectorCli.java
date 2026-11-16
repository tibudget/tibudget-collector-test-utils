package com.tibudget.test.simulator;

import com.google.gson.*;
import com.tibudget.api.CollectorPlugin;
import com.tibudget.test.*;

import java.io.PrintStream;
import java.io.OutputStream;
import java.util.logging.Handler;
import java.util.logging.Logger;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.*;
import java.util.function.Supplier;

/**
 * Headless entry point for collector development. Every command prints one JSON report to standard output.
 * Run with the collector test classpath. Secrets are read from files, encrypted saved inputs or environment variables.
 */
public final class CollectorCli {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private static final Set<String> FLAGS = Set.of("fresh", "record", "saved-inputs", "ignore-body");
    private static final Map<String, Set<String>> OPTIONS = Map.of(
            "run", Set.of("collector", "workspace", "inputs", "inputs-env", "saved-inputs", "counterparties", "otp-env", "fresh", "record", "timeout-seconds", "report", "expect", "ignore"),
            "replay", Set.of("collector", "workspace", "inputs", "inputs-env", "saved-inputs", "counterparties", "otp-env", "fresh", "timeout-seconds", "report", "expect", "ignore", "scenario", "stop-after", "fail-at", "ignore-body"),
            "resume-check", Set.of("collector", "workspace", "inputs", "inputs-env", "saved-inputs", "counterparties", "otp-env", "timeout-seconds", "report", "ignore", "scenario", "continuations", "max-runs", "ignore-body"),
            "describe", Set.of("collector", "report"),
            "stop", Set.of("workspace", "report"),
            "validate", Set.of("actual", "previous", "report"),
            "compare", Set.of("expected", "actual", "ignore", "report"),
            "audit", Set.of("scenario", "report"),
            "copy", Set.of("scenario", "destination", "plan", "report"));

    private CollectorCli() {
    }

    public static void main(String[] args) {
        System.exit(execute(args, System.out));
    }

    /**
     * Execute sequentially. A timeout requests stop; the CLI process exits even if a broken collector ignores it.
     * Exit codes: 0 success, 2 paused, 3 invalid result/difference, 4 execution failure, 5 HAR review, 64 usage, 124 timeout.
     */
    public static synchronized int execute(String[] args, PrintStream output) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        Logger root = Logger.getLogger("");
        Handler[] handlers = root.getHandlers();
        try (PrintStream quiet = new PrintStream(OutputStream.nullOutputStream())) {
            System.setOut(quiet);
            System.setErr(quiet);
            for (Handler handler : handlers) root.removeHandler(handler);
            return executeCommand(args, output);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            for (Handler handler : handlers) root.addHandler(handler);
        }
    }

    private static int executeCommand(String[] args, PrintStream output) {
        JsonObject report = new JsonObject();
        report.addProperty("schemaVersion", 1);
        Map<String, String> options = Map.of();
        int code;
        try {
            if (args.length == 0 || args[0].equals("help") || args[0].equals("--help")) {
                report.addProperty("command", "help");
                report.add("commands", JSON.toJsonTree(new TreeSet<>(OPTIONS.keySet())));
                Map<String, List<String>> availableOptions = new TreeMap<>();
                OPTIONS.forEach((command, names) -> availableOptions.put(command, names.stream().sorted().toList()));
                report.add("options", JSON.toJsonTree(availableOptions));
                report.addProperty("documentation", "docs/agent-tools.md");
                report.addProperty("usage", "CollectorCli <command> --option value; use TibuSimulator --headless <command> with the same test classpath");
                report.add("exitCodes", JSON.toJsonTree(Map.of("success", 0, "paused", 2, "invalid", 3, "failure", 4, "review", 5, "usage", 64, "timeout", 124)));
                output.println(JSON.toJson(report));
                return 0;
            }
            String command = args[0];
            if (!OPTIONS.containsKey(command)) throw new UsageException("Unknown command; use help.");
            report.addProperty("command", command);
            options = parse(args, OPTIONS.get(command));
            code = switch (command) {
                case "run", "replay", "resume-check" -> collect(command, options, report);
                case "describe" -> {
                    Supplier<CollectorPlugin> factory = factory(options.get("collector"));
                    report.add("inputs", new SimulatorInputs(factory, null).schema());
                    report.addProperty("openId", factory.get().getOpenIdJSONConfiguration() != null);
                    yield 0;
                }
                case "stop" -> {
                    Path workspace = requiredPath(options, "workspace");
                    Path lockFile = workspace.resolve("collect.lock");
                    if (!Files.isRegularFile(lockFile)) throw new UsageException("No running headless collect found in this workspace.");
                    boolean busy;
                    try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE)) {
                        try (FileLock lock = channel.tryLock()) { busy = lock == null; }
                        catch (OverlappingFileLockException active) { busy = true; }
                    }
                    if (!busy) throw new UsageException("No running headless collect found in this workspace.");
                    Files.writeString(workspace.resolve("stop.request"), "stop\n");
                    report.addProperty("stopRequested", true);
                    yield 0;
                }
                case "validate" -> {
                    var findings = CollectorDiagnostics.validate(readObject(requiredPath(options, "actual")), options.containsKey("previous") ? readObject(Path.of(options.get("previous"))) : null);
                    report.add("findings", JSON.toJsonTree(findings));
                    yield findings.stream().anyMatch(finding -> finding.severity().equals("ERROR")) ? 3 : 0;
                }
                case "compare" -> {
                    var differences = CollectorResultDiff.compare(readObject(requiredPath(options, "expected")), readObject(requiredPath(options, "actual")), ignored(options));
                    report.add("differences", JSON.toJsonTree(differences));
                    yield differences.isEmpty() ? 0 : 3;
                }
                case "audit" -> {
                    var reviews = HarScenario.audit(requiredPath(options, "scenario"));
                    report.add("review", JSON.toJsonTree(reviews));
                    report.addProperty("manualReviewRequired", true);
                    yield reviews.isEmpty() ? 0 : 5;
                }
                case "copy" -> {
                    HarScenario.copy(requiredPath(options, "scenario"), requiredPath(options, "destination"), readObject(requiredPath(options, "plan")));
                    report.addProperty("copied", true);
                    report.add("review", JSON.toJsonTree(HarScenario.audit(Path.of(options.get("destination")))));
                    report.addProperty("manualReviewRequired", true);
                    yield 0;
                }
                default -> throw new AssertionError();
            };
        } catch (UsageException invalid) {
            report.addProperty("error", invalid.getMessage());
            code = 64;
        } catch (Exception | LinkageError | ServiceConfigurationError failure) {
            // Exception messages from collectors, JSON readers and paths can contain credentials. Keep the type only.
            report.addProperty("errorType", failure.getClass().getSimpleName());
            report.addProperty("error", "Command failed. Check local configuration, input files and private diagnostics.log when available.");
            code = 4;
        }
        report.addProperty("exitCode", code);
        try {
            if (options.containsKey("report")) writeReport(Path.of(options.get("report")), report);
        } catch (Exception failure) {
            report.addProperty("reportWriteError", failure.getClass().getSimpleName());
            code = 4;
            report.addProperty("exitCode", code);
        }
        output.println(JSON.toJson(report));
        return code;
    }

    private static int collect(String command, Map<String, String> options, JsonObject report) throws Exception {
        if (options.containsKey("stop-after") && options.containsKey("fail-at")) throw new UsageException("Use stop-after or fail-at in a single replay.");
        Supplier<CollectorPlugin> factory = factory(options.get("collector"));
        CollectorPlugin collector = factory.get();
        Path workspace = options.containsKey("workspace") ? Path.of(options.get("workspace")) : Path.of("target", "agent-simulator", collector.getClass().getName());
        if (command.equals("resume-check") && Files.exists(workspace)) throw new UsageException("Resume verification requires a new workspace.");
        options.putIfAbsent("report", workspace.resolve("report.json").toString());
        int sources = (options.containsKey("inputs") ? 1 : 0) + (options.containsKey("inputs-env") ? 1 : 0) + (options.containsKey("saved-inputs") ? 1 : 0);
        if (sources > 1) throw new UsageException("Choose one input source: inputs, inputs-env or saved-inputs.");
        JsonObject inputs = new JsonObject();
        if (options.containsKey("inputs")) inputs = readObject(Path.of(options.get("inputs")));
        else if (options.containsKey("inputs-env")) inputs = JsonParser.parseString(environment(options.get("inputs-env"))).getAsJsonObject();
        else if (options.containsKey("saved-inputs")) {
            inputs = new SimulatorSettings(Path.of(System.getProperty("user.home"), ".config", "tibudget", "simulator"), collector.getClass().getName()).load();
        }
        JsonObject counterparties = options.containsKey("counterparties") ? readObject(Path.of(options.get("counterparties"))) : new JsonObject();
        List<String> codes = new ArrayList<>();
        if (options.containsKey("otp-env")) JsonParser.parseString(environment(options.get("otp-env"))).getAsJsonArray().forEach(value -> codes.add(value.getAsString()));
        Path scenario = command.equals("run") ? null : requiredPath(options, "scenario");
        if (scenario != null) HarScenario.recordings(scenario);
        HeadlessRunner.Options config = new HeadlessRunner.Options(workspace, scenario, options.containsKey("record"), options.containsKey("fresh"), !options.containsKey("ignore-body"),
                number(options, "stop-after", 0), number(options, "fail-at", 0), number(options, "timeout-seconds", 300) * 1000L, inputs, counterparties, codes);
        if (command.equals("resume-check")) {
            JsonObject verification = ResumeVerifier.verify(factory, config, options.containsKey("continuations") ? Path.of(options.get("continuations")) : null, number(options, "max-runs", 10), ignored(options));
            report.add("verification", verification);
            return verification.get("valid").getAsBoolean() ? 0 : 3;
        }
        HeadlessRunner.Outcome outcome = new HeadlessRunner(factory).run(config);
        report.add("run", summary(outcome));
        int code = 0;
        if (outcome.status().equals("TIMEOUT")) code = 124;
        else if (outcome.errorType() != null) code = 4;
        else if (!outcome.valid()) code = 3;
        else if (!outcome.complete()) code = 2;
        if (options.containsKey("expect") && outcome.errorType() == null && Files.isRegularFile(workspace.resolve("result.json"))) {
            var differences = CollectorResultDiff.compare(readObject(Path.of(options.get("expect"))), readObject(workspace.resolve("result.json")), ignored(options));
            report.add("differences", JSON.toJsonTree(differences));
            if (!differences.isEmpty()) code = 3;
        }
        return code;
    }

    static JsonObject summary(HeadlessRunner.Outcome outcome) {
        JsonObject result = new JsonObject();
        result.addProperty("status", outcome.status());
        result.addProperty("complete", outcome.complete());
        result.addProperty("progress", outcome.progress());
        result.addProperty("requests", outcome.requests());
        result.addProperty("otpCodesUsed", outcome.otpCodesUsed());
        result.addProperty("errorType", outcome.errorType());
        result.addProperty("mismatchRequest", outcome.mismatchRequest());
        result.addProperty("mismatchReason", outcome.mismatchReason());
        result.add("findings", JSON.toJsonTree(outcome.findings()));
        if (outcome.scenario() != null) result.addProperty("scenario", outcome.scenario().toString());
        result.addProperty("valid", outcome.valid());
        return result;
    }

    private static Supplier<CollectorPlugin> factory(String name) throws Exception {
        if (name != null) {
            var constructor = Class.forName(name).asSubclass(CollectorPlugin.class).getConstructor();
            return () -> {
                try { return constructor.newInstance(); }
                catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot create collector", failure); }
            };
        }
        var providers = ServiceLoader.load(CollectorPlugin.class).stream().toList();
        if (providers.size() != 1) throw new UsageException("Expected one CollectorPlugin service; use --collector to select a public implementation.");
        return providers.getFirst()::get;
    }

    private static Map<String, String> parse(String[] args, Set<String> allowed) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) throw new UsageException("Expected a named option; use help.");
            String name = args[i].substring(2);
            if (!allowed.contains(name)) throw new UsageException("Unsupported option for this command; see docs/agent-tools.md.");
            if (options.containsKey(name)) throw new UsageException("Options must not be repeated.");
            String value = "true";
            if (!FLAGS.contains(name)) {
                if (++i == args.length || args[i].startsWith("--")) throw new UsageException("Missing option value.");
                value = args[i];
            }
            options.put(name, value);
        }
        return options;
    }

    private static int number(Map<String, String> options, String key, int fallback) {
        if (!options.containsKey(key)) return fallback;
        try {
            int value = Integer.parseInt(options.get(key));
            if (value > 0) return value;
        } catch (NumberFormatException ignored) {
            // Keep user values out of diagnostic messages.
        }
        throw new UsageException("Numeric options must be positive integers.");
    }

    private static Set<String> ignored(Map<String, String> options) {
        if (!options.containsKey("ignore")) return Set.of();
        Set<String> paths = new HashSet<>(List.of(options.get("ignore").split(",")));
        if (paths.stream().anyMatch(path -> !path.startsWith("/"))) throw new UsageException("Ignored fields must be JSON pointers separated by commas.");
        return paths;
    }

    static JsonObject readObject(Path path) throws Exception {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    private static Path requiredPath(Map<String, String> options, String key) {
        if (!options.containsKey(key)) throw new UsageException("Missing required option --" + key + ".");
        return Path.of(options.get(key));
    }

    private static String environment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new UsageException("Configured environment variable is missing or empty.");
        return value;
    }

    private static void writeReport(Path file, JsonObject report) throws Exception {
        Path target = file.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "collector-report-", ".tmp");
        try {
            Files.writeString(temporary, JSON.toJson(report) + "\n");
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static final class UsageException extends IllegalArgumentException {
        private UsageException(String message) {
            super(message);
        }
    }
}
