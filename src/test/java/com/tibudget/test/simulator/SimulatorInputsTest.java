package com.tibudget.test.simulator;

import com.google.gson.JsonObject;
import com.tibudget.api.*;
import com.tibudget.dto.*;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SimulatorInputsTest {
    @Test
    void restoresDeclaredTypesAndProtectsSavedCredentials() throws Exception {
        SimulatorInputs inputs = new SimulatorInputs(TestCollector::new, null);
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("password", "synthetic-password");
        original.put("login", "synthetic-login");
        original.put("number", Long.MAX_VALUE);
        original.put("enabled", true);
        original.put("choice", Choice.SECOND);
        original.put("letter", 'é');
        original.put("optional", null);
        JsonObject json = inputs.savedValues(original);
        DiagnosticRedactor redactor = new DiagnosticRedactor();
        Map<String, Object> restored = inputs.restore(json, redactor);
        assertEquals(original, restored);
        TestCollector collector = new TestCollector();
        inputs.apply(collector, restored);
        assertEquals(Long.MAX_VALUE, collector.getNumber());
        assertEquals(Choice.SECOND, collector.getChoice());
        assertEquals('é', collector.getLetter());
        assertEquals("[hidden] [hidden]", redactor.text("synthetic-login synthetic-password"));
    }

    @Test
    void ignoresRemovedInputsAndRejectsInvalidSavedValues() throws Exception {
        SimulatorInputs inputs = new SimulatorInputs(TestCollector::new, null);
        JsonObject saved = new JsonObject();
        saved.addProperty("removed", "old-input");
        saved.addProperty("number", 12);
        assertEquals(Map.of("number", 12L), inputs.restore(saved, new DiagnosticRedactor()));
        saved.addProperty("number", "invalid-number");
        assertThrows(Exception.class, () -> inputs.restore(saved, new DiagnosticRedactor()));
    }

    enum Choice { FIRST, SECOND }

    public static class TestCollector implements CollectorPlugin {
        @Input(password = true) private String password;
        @Input private String login;
        @Input private long number;
        @Input private boolean enabled;
        @Input private Choice choice;
        @Input private char letter;
        @Input private String optional;

        public String getPassword() { return password; }
        public void setPassword(String value) { password = value; }
        public String getLogin() { return login; }
        public void setLogin(String value) { login = value; }
        public long getNumber() { return number; }
        public void setNumber(long value) { number = value; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
        public Choice getChoice() { return choice; }
        public void setChoice(Choice value) { choice = value; }
        public char getLetter() { return letter; }
        public void setLetter(char value) { letter = value; }
        public String getOptional() { return optional; }
        public void setOptional(String value) { optional = value; }
        @Override public void init(InternetProvider internet, CounterpartyProvider counterparties, OTPProvider otp, PDFToolsProvider pdf, Map<String, String> settings,
                                   List<AccountDto> accounts, List<RecurringPaymentDto> recurringPayments) { }
        @Override public String initConnection(URI uri) { return null; }
        @Override public boolean collect() { return true; }
        @Override public void stop() { }
        @Override public Map<String, String> getSettings() { return Map.of(); }
        @Override public List<AccountDto> getAccounts() { return List.of(); }
        @Override public List<TransactionDto> getTransactions() { return List.of(); }
        @Override public List<RecurringPaymentDto> getRecurringPayments() { return List.of(); }
        @Override public String getConfigurationName() { return "Synthetic collector"; }
        @Override public String getConfigurationIdHash() { return "synthetic"; }
        @Override public int getProgress() { return 100; }
    }
}
