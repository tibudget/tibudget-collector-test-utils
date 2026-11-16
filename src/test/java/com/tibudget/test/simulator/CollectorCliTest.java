package com.tibudget.test.simulator;

import com.google.gson.*;
import com.tibudget.api.*;
import com.tibudget.api.exceptions.CollectError;
import com.tibudget.dto.*;
import com.tibudget.test.*;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CollectorCliTest {
    @TempDir Path temp;

    @Test
    void replaysWithoutNetworkAndReportsOnlyJson() throws Exception {
        Path scenario = scenario();
        Path inputs = inputs();
        Path workspace = temp.resolve("run");
        Reply reply = cli("replay", "--collector", ReplayCollector.class.getName(), "--scenario", scenario.toString(), "--inputs", inputs.toString(), "--workspace", workspace.toString());
        assertEquals(0, reply.code);
        assertTrue(reply.json.getAsJsonObject("run").get("complete").getAsBoolean());
        assertEquals(2, reply.json.getAsJsonObject("run").get("requests").getAsInt());
        JsonObject result = CollectorCli.readObject(workspace.resolve("result.json"));
        assertEquals(2, result.getAsJsonArray("transactions").size());
        assertTrue(result.getAsJsonArray("accounts").get(0).getAsJsonObject().get("lastUpdate").isJsonNull());
        assertTrue(CollectorCli.readObject(workspace.resolve("result-raw.json")).getAsJsonArray("accounts").get(0).getAsJsonObject().get("lastUpdate").isJsonNull());
        assertFalse(reply.text.contains("synthetic-password"));
        assertFalse(Files.readString(workspace.resolve("diagnostics.log")).contains("synthetic-password"));
        assertEquals(reply.json, CollectorCli.readObject(workspace.resolve("report.json")));
    }

    @Test
    void resumesInANewInstanceAndKeepsAccountAndRecurringUuids() throws Exception {
        Path scenario = scenario();
        Path workspace = temp.resolve("resume");
        JsonObject values = new JsonObject();
        values.addProperty("password", "synthetic-password");
        HeadlessRunner runner = new HeadlessRunner(ReplayCollector::new);
        OkHttpInternetProvider replay = new OkHttpInternetProvider().replayDir(scenario.toFile());
        HeadlessRunner.Options first = options(workspace, scenario, values, true, 1);
        HeadlessRunner.Outcome stopped = runner.run(first, replay);
        assertEquals("PAUSED", stopped.status());
        assertTrue(stopped.valid());
        assertEquals(1, stopped.result().getAsJsonArray("transactions").size());
        String accountUuid = stopped.result().getAsJsonArray("accounts").get(0).getAsJsonObject().get("uuid").getAsString();
        String recurringUuid = stopped.result().getAsJsonArray("recurringPayments").get(0).getAsJsonObject().get("uuid").getAsString();
        HeadlessRunner.Outcome resumed = runner.run(options(workspace, scenario, values, false, 0), replay);
        assertTrue(resumed.complete());
        assertTrue(resumed.valid());
        assertEquals(2, resumed.result().getAsJsonArray("transactions").size());
        assertEquals(accountUuid, resumed.result().getAsJsonArray("accounts").get(0).getAsJsonObject().get("uuid").getAsString());
        assertEquals(recurringUuid, resumed.result().getAsJsonArray("recurringPayments").get(0).getAsJsonObject().get("uuid").getAsString());
    }

    @Test
    void checksEveryRequestBoundaryAgainstAnUninterruptedRun() throws Exception {
        Path scenario = scenario();
        Reply reply = cli("resume-check", "--collector", ReplayCollector.class.getName(), "--scenario", scenario.toString(), "--workspace", temp.resolve("matrix").toString());
        assertEquals(0, reply.code, reply.text);
        JsonObject verification = reply.json.getAsJsonObject("verification");
        assertTrue(verification.get("valid").getAsBoolean());
        assertEquals(2, verification.getAsJsonArray("checkpoints").size());
        assertEquals(2, verification.getAsJsonArray("checkpoints").get(0).getAsJsonObject().getAsJsonArray("runs").size());
    }

    @Test
    void detectsBrokenUuidPreservationAndMissingContinuation() throws Exception {
        Path scenario = scenario();
        Reply broken = cli("resume-check", "--collector", BrokenIdentityCollector.class.getName(), "--scenario", scenario.toString(), "--workspace", temp.resolve("broken").toString());
        assertEquals(3, broken.code);
        assertTrue(broken.text.contains("uuid.changed"));
        Reply missing = cli("resume-check", "--collector", ReplayCollector.class.getName(), "--scenario", scenario.toString(), "--workspace", temp.resolve("missing-continuation").toString(), "--continuations", temp.resolve("absent").toString());
        assertEquals(3, missing.code);
        assertTrue(missing.text.contains("CONTINUATION_MISSING"));
    }

    @Test
    void mergesUpdatedTransactionsWithoutAddingDuplicates() throws Exception {
        Path scenario = scenario();
        Path workspace = temp.resolve("updates");
        assertEquals(0, cli("replay", "--collector", UpdateCollector.class.getName(), "--scenario", scenario.toString(), "--workspace", workspace.toString()).code);
        JsonObject plan = JsonParser.parseString("""
                {"responses":{"1":{"body":"-19.50"}}}
                """).getAsJsonObject();
        Path changed = temp.resolve("changed");
        HarScenario.copy(scenario, changed, plan);
        assertEquals(0, cli("replay", "--collector", UpdateCollector.class.getName(), "--scenario", changed.toString(), "--workspace", workspace.toString()).code);
        JsonArray transactions = CollectorCli.readObject(workspace.resolve("result.json")).getAsJsonArray("transactions");
        assertEquals(2, transactions.size());
        assertEquals(-19.50, transactions.get(0).getAsJsonObject().get("amount").getAsDouble());
    }

    @Test
    void reportsOfflineFailureAndTimeoutWithoutExposingExceptionText() throws Exception {
        Path scenario = scenario();
        Reply failed = cli("replay", "--collector", ReplayCollector.class.getName(), "--scenario", scenario.toString(), "--workspace", temp.resolve("failure").toString(), "--fail-at", "1");
        assertEquals(4, failed.code);
        assertEquals("CollectError", failed.json.getAsJsonObject("run").get("errorType").getAsString());
        assertFalse(failed.text.contains("synthetic-password"));
        Reply timeout = cli("replay", "--collector", SlowCollector.class.getName(), "--scenario", scenario.toString(), "--workspace", temp.resolve("timeout").toString(), "--timeout-seconds", "1");
        assertEquals(124, timeout.code);
        assertEquals("TIMEOUT", timeout.json.getAsJsonObject("run").get("status").getAsString());
        assertFalse(Files.exists(temp.resolve("timeout/result.json")));
    }

    @Test
    void supportsAnExternalStopRequestAndNeverReportsStoppedWorkAsComplete() throws Exception {
        Path scenario = scenario();
        Path workspace = temp.resolve("external-stop");
        StopFileCollector.stopFile = workspace.resolve("stop.request");
        Reply stopped = cli("replay", "--collector", StopFileCollector.class.getName(), "--scenario", scenario.toString(), "--workspace", workspace.toString());
        assertEquals(2, stopped.code);
        assertFalse(stopped.json.getAsJsonObject("run").get("complete").getAsBoolean());
        assertEquals(64, cli("stop", "--workspace", workspace.toString()).code);
    }

    @Test
    void rejectsUnknownInputsAndInvalidOptionsAndDescribesInputsWithoutDefaults() throws Exception {
        Path bad = temp.resolve("bad-inputs.json");
        Files.writeString(bad, "{\"misspelled-password\":\"synthetic-password\"}");
        Reply invalid = cli("replay", "--collector", ReplayCollector.class.getName(), "--scenario", scenario().toString(), "--workspace", temp.resolve("bad-inputs").toString(), "--inputs", bad.toString());
        assertEquals(4, invalid.code);
        assertFalse(invalid.text.contains("synthetic-password"));
        assertEquals(64, cli("compare", "--actual", "absent").code);
        assertEquals(64, cli("run", "--password", "synthetic-password").code);
        assertEquals(64, cli("replay", "--timeout-seconds", "-1", "--collector", ReplayCollector.class.getName(), "--scenario", scenario().toString()).code);
        Reply describe = cli("describe", "--collector", ReplayCollector.class.getName());
        assertEquals(0, describe.code);
        assertEquals("password", describe.json.getAsJsonArray("inputs").get(0).getAsJsonObject().get("name").getAsString());
        assertFalse(describe.text.contains("default"));
    }

    @Test
    void reportsInvalidIdsEvenWhenAStableSnapshotCannotBeWritten() throws Exception {
        Path scenario = scenario();
        Reply invalid = cli("replay", "--collector", MissingIdCollector.class.getName(), "--scenario", scenario.toString(), "--workspace", temp.resolve("invalid-id").toString());
        assertEquals(3, invalid.code, invalid.text);
        assertTrue(invalid.text.contains("field.required"));
        assertTrue(invalid.text.contains("snapshot.identity"));
    }

    @Test
    void validatesOtpPatternsAndUsesConfiguredCounterpartyMappings() throws Exception {
        Path scenario = scenario();
        JsonObject counterparties = JsonParser.parseString("{\"synthetic:key\":{\"synthetic-value\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\"}}").getAsJsonObject();
        HeadlessRunner runner = new HeadlessRunner(OtpCollector::new);
        HeadlessRunner.Options config = new HeadlessRunner.Options(temp.resolve("otp"), scenario, false, true, true, 0, 0, 5000, new JsonObject(), counterparties, List.of("123456"));
        HeadlessRunner.Outcome outcome = runner.run(config);
        assertTrue(outcome.complete());
        assertTrue(outcome.valid());
        assertEquals(1, outcome.otpCodesUsed());
        assertFalse(Files.readString(config.workspace().resolve("diagnostics.log")).contains("123456"));
        config = new HeadlessRunner.Options(temp.resolve("invalid-otp"), scenario, false, true, true, 0, 0, 5000, new JsonObject(), counterparties, List.of("123"));
        assertEquals("IllegalArgumentException", runner.run(config).errorType());
    }

    @Test
    void comparesAndValidatesSavedResultsThroughTheCli() throws Exception {
        Path workspace = temp.resolve("comparison");
        assertEquals(0, cli("replay", "--collector", ReplayCollector.class.getName(), "--scenario", scenario().toString(), "--workspace", workspace.toString()).code);
        Path result = workspace.resolve("result.json");
        assertEquals(0, cli("validate", "--actual", result.toString()).code);
        assertEquals(0, cli("compare", "--expected", result.toString(), "--actual", result.toString()).code);
        JsonObject changed = CollectorCli.readObject(result);
        changed.getAsJsonArray("transactions").get(0).getAsJsonObject().addProperty("amount", -999);
        Path actual = temp.resolve("actual.json");
        Files.writeString(actual, changed.toString());
        assertEquals(3, cli("compare", "--expected", result.toString(), "--actual", actual.toString()).code);
    }

    @Test
    void usesRecordedContinuationScenariosWhenResumeRestartsRequestNumbering() throws Exception {
        Path source = scenario();
        Path continuation = temp.resolve("continuations/1/1");
        Files.createDirectories(continuation);
        Path second = HarScenario.recordings(source).get(1);
        Files.copy(second, continuation.resolve(second.getFileName().toString().replace("0002_", "0001_")));
        Reply reply = cli("resume-check", "--collector", ReplayCollector.class.getName(), "--scenario", source.toString(), "--workspace", temp.resolve("with-continuation").toString(),
                "--continuations", temp.resolve("continuations").toString());
        assertEquals(0, reply.code, reply.text);
    }

    private HeadlessRunner.Options options(Path workspace, Path scenario, JsonObject values, boolean fresh, int stop) {
        return new HeadlessRunner.Options(workspace, scenario, false, fresh, true, stop, 0, 5000, values, new JsonObject(), List.of());
    }

    private Path inputs() throws Exception {
        Path file = temp.resolve("inputs.json");
        Files.writeString(file, "{\"password\":\"synthetic-password\"}");
        return file;
    }

    private Path scenario() throws Exception {
        Path base = Files.createTempDirectory(temp, "scenario-");
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> new okhttp3.Response.Builder().request(chain.request()).code(200).message("OK")
                .protocol(Protocol.HTTP_1_1).body(ResponseBody.create("-12.50", MediaType.parse("text/plain"))).build()).build();
        OkHttpInternetProvider recorder = new OkHttpInternetProvider(client).harDir(base.toFile());
        recorder.get("https://example.test/item/1", Map.of());
        recorder.get("https://example.test/item/2", Map.of());
        return recorder.getHarDir().toPath();
    }

    private Reply cli(String... args) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int code = CollectorCli.execute(args, new PrintStream(bytes, true, StandardCharsets.UTF_8));
        String text = bytes.toString(StandardCharsets.UTF_8);
        return new Reply(code, JsonParser.parseString(text).getAsJsonObject(), text);
    }

    private record Reply(int code, JsonObject json, String text) {
    }

    public static class ReplayCollector implements CollectorPlugin {
        @Input(password = true) private String password;
        protected InternetProvider internet;
        protected Map<String, String> settings;
        protected List<AccountDto> accounts;
        protected List<RecurringPaymentDto> recurring;
        protected List<TransactionDto> transactions = new ArrayList<>();
        protected volatile boolean stopped;
        protected int next;

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }

        @Override
        public void init(InternetProvider internet, CounterpartyProvider counterparties, OTPProvider otp, PDFToolsProvider pdf, Map<String, String> settings,
                         List<AccountDto> accounts, List<RecurringPaymentDto> recurring) {
            this.internet = internet;
            this.settings = settings;
            this.accounts = new ArrayList<>(accounts);
            this.recurring = new ArrayList<>(recurring);
            next = Integer.parseInt(settings.getOrDefault("next", "0"));
            if (this.accounts.isEmpty()) {
                AccountDto account = new AccountDto(AccountDto.AccountDtoType.PAYMENT, "Synthetic account", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "EUR", "Europe/Paris", 0);
                account.setId("synthetic-account");
                this.accounts.add(account);
                RecurringPaymentDto payment = new RecurringPaymentDto("subscription", UUID.randomUUID().toString(), account.getUuid());
                payment.setRecurrenceUnit(RecurringPaymentDto.RecurrenceUnit.MONTH);
                this.recurring.add(payment);
            }
        }

        @Override
        public boolean collect() throws CollectError {
            System.out.println("Configured password: " + password);
            try {
                while (next < 2) {
                    String amount = internet.get("https://example.test/item/" + (next + 1), Map.of()).body;
                    Date date = new Date(1000L * next);
                    TransactionDto transaction = new TransactionDto("transaction-" + next, accounts.getFirst().getUuid(), TransactionDto.TransactionDtoType.PAYMENT,
                            date, date, "Synthetic transaction", null, Double.parseDouble(amount), "EUR");
                    transaction.setRecurrentPaymentUuid(recurring.getFirst().getUuid());
                    transactions.add(transaction);
                    settings.put("next", String.valueOf(++next));
                    if (stopped) return next == 2;
                }
                return true;
            } catch (IOException failure) {
                throw new CollectError("synthetic-password", failure);
            }
        }

        @Override public void stop() { stopped = true; }
        @Override public Map<String, String> getSettings() { return settings == null ? Map.of() : settings; }
        @Override public List<AccountDto> getAccounts() { return accounts == null ? List.of() : accounts; }
        @Override public List<TransactionDto> getTransactions() { return transactions; }
        @Override public List<RecurringPaymentDto> getRecurringPayments() { return recurring == null ? List.of() : recurring; }
        @Override public int getProgress() { return next * 50; }
        @Override public String initConnection(URI uri) { return null; }
        @Override public String getConfigurationName() { return "Synthetic test"; }
        @Override public String getConfigurationIdHash() { return "synthetic-test"; }
    }

    public static class OtpCollector extends ReplayCollector {
        @Override
        public void init(InternetProvider internet, CounterpartyProvider counterparties, OTPProvider otp, PDFToolsProvider pdf, Map<String, String> settings,
                         List<AccountDto> accounts, List<RecurringPaymentDto> recurring) {
            super.init(internet, counterparties, otp, pdf, settings, accounts, recurring);
            assertEquals("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", counterparties.find("synthetic:key", "synthetic-value"));
            String code = otp.getCode(OTPProvider.Channel.EMAIL, "Synthetic", OTPProvider.PATTERN_6_DIGIT, "Check synthetic email");
            System.out.println(code);
        }
    }

    public static class BrokenIdentityCollector extends ReplayCollector {
        @Override
        public void init(InternetProvider internet, CounterpartyProvider counterparties, OTPProvider otp, PDFToolsProvider pdf, Map<String, String> settings,
                         List<AccountDto> accounts, List<RecurringPaymentDto> recurring) {
            super.init(internet, counterparties, otp, pdf, settings, accounts, recurring);
            this.recurring.getFirst().setUuid(UUID.randomUUID().toString());
        }
    }

    public static class UpdateCollector extends ReplayCollector {
        @Override
        public void init(InternetProvider internet, CounterpartyProvider counterparties, OTPProvider otp, PDFToolsProvider pdf, Map<String, String> settings,
                         List<AccountDto> accounts, List<RecurringPaymentDto> recurring) {
            super.init(internet, counterparties, otp, pdf, settings, accounts, recurring);
            next = 0;
        }
    }

    public static class MissingIdCollector extends ReplayCollector {
        @Override public boolean collect() throws CollectError { boolean complete = super.collect(); accounts.getFirst().setId(null); return complete; }
    }

    public static class SlowCollector extends ReplayCollector {
        @Override public boolean collect() {
            while (!stopped) {
                try { Thread.sleep(10); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
            }
            return false;
        }
    }

    public static class StopFileCollector extends SlowCollector {
        static Path stopFile;
        @Override public boolean collect() {
            try { Files.writeString(stopFile, "stop"); }
            catch (IOException failure) { throw new IllegalStateException(failure); }
            return super.collect();
        }
    }
}
