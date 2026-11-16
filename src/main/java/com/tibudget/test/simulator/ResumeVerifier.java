package com.tibudget.test.simulator;

import com.google.gson.*;
import com.tibudget.api.CollectorPlugin;
import com.tibudget.test.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;

/**
 * Compare an uninterrupted replay with a new collect interrupted at every request boundary.
 * A continuation directory can supply separate recordings when resuming repeats login or navigation requests.
 */
final class ResumeVerifier {
    private ResumeVerifier() {
    }

    static JsonObject verify(Supplier<CollectorPlugin> factory, HeadlessRunner.Options options, Path continuations, int maxRuns, Set<String> ignored) throws Exception {
        if (Files.exists(options.workspace())) throw new IOException("Resume verification requires a new workspace");
        List<Path> recordings = HarScenario.recordings(options.replay());
        Files.createDirectories(options.workspace().toAbsolutePath().getParent());
        Files.createDirectory(options.workspace());
        HeadlessRunner runner = new HeadlessRunner(factory);
        Path baselineDir = options.workspace().resolve("baseline");
        HeadlessRunner.Outcome baseline = runner.run(withWorkspace(options, baselineDir, options.replay(), true, 0));
        JsonObject report = new JsonObject();
        report.add("baseline", CollectorCli.summary(baseline));
        JsonArray checkpoints = new JsonArray();
        report.add("checkpoints", checkpoints);
        boolean valid = baseline.valid() && baseline.complete();
        if (!valid) {
            report.addProperty("valid", false);
            return report;
        }
        JsonObject expected = CollectorCli.readObject(baselineDir.resolve("result.json"));
        for (int request = 1; request <= recordings.size(); request++) {
            Path workspace = options.workspace().resolve("stop-" + request);
            OkHttpInternetProvider replay = new OkHttpInternetProvider().replayDir(options.replay().toFile()).verifyReplayRequestBody(options.strictBody()).downloadDir(workspace.resolve("downloads").toFile());
            HeadlessRunner.Outcome outcome = runner.run(withWorkspace(options, workspace, options.replay(), true, request), replay);
            JsonObject checkpoint = new JsonObject();
            checkpoint.addProperty("stopAfter", request);
            JsonArray runs = new JsonArray();
            runs.add(CollectorCli.summary(outcome));
            checkpoint.add("runs", runs);
            boolean checkpointValid = outcome.valid();
            int resumed = 0;
            int codesUsed = outcome.otpCodesUsed();
            while (checkpointValid && !outcome.complete() && resumed < maxRuns) {
                resumed++;
                Path continuation = continuations == null ? null : continuations.resolve(String.valueOf(request)).resolve(String.valueOf(resumed));
                if (continuation != null && !Files.isDirectory(continuation)) {
                    checkpoint.addProperty("error", "CONTINUATION_MISSING");
                    checkpointValid = false;
                    break;
                }
                HeadlessRunner.Options resumedOptions = withWorkspace(options, workspace, continuation == null ? options.replay() : continuation, false, 0);
                resumedOptions = new HeadlessRunner.Options(resumedOptions.workspace(), resumedOptions.replay(), false, false, resumedOptions.strictBody(), 0, 0,
                        resumedOptions.timeoutMillis(), resumedOptions.inputs(), resumedOptions.counterparties(), options.otpCodes().subList(Math.min(codesUsed, options.otpCodes().size()), options.otpCodes().size()));
                outcome = runner.run(resumedOptions, continuation == null ? replay : null);
                codesUsed += outcome.otpCodesUsed();
                runs.add(CollectorCli.summary(outcome));
                checkpointValid = outcome.valid();
            }
            if (!outcome.complete()) checkpointValid = false;
            if (checkpointValid) {
                List<CollectorResultDiff.Difference> differences = CollectorResultDiff.compare(expected, CollectorCli.readObject(workspace.resolve("result.json")), ignored);
                checkpoint.add("differences", SimulatorState.JSON.toJsonTree(differences));
                checkpointValid = differences.isEmpty();
            }
            checkpoint.addProperty("valid", checkpointValid);
            checkpoints.add(checkpoint);
            valid &= checkpointValid;
            if (outcome.status().equals("TIMEOUT")) break;
        }
        report.addProperty("valid", valid);
        return report;
    }

    private static HeadlessRunner.Options withWorkspace(HeadlessRunner.Options original, Path workspace, Path replay, boolean fresh, int stopAfter) {
        return new HeadlessRunner.Options(workspace, replay, false, fresh, original.strictBody(), stopAfter, 0, original.timeoutMillis(), original.inputs(), original.counterparties(), original.otpCodes());
    }
}
