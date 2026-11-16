package com.tibudget.test.simulator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticRedactorTest {
    @Test
    void handlesLargeEscapedStringsWithoutUsingTheJavaCallStack() {
        String large = "word\\\"quoted\\\\path".repeat(100_000);
        String script = "<script>window.context={\"content\":\"" + large + "\",\"authToken\":\"synthetic-private-token\"};</script>";
        DiagnosticRedactor redactor = new DiagnosticRedactor();
        String result = assertDoesNotThrow(() -> redactor.text(script));
        assertTrue(result.contains(large));
        assertFalse(result.contains("synthetic-private-token"));
        assertTrue(result.contains("\"authToken\":\"[hidden]\""));
        assertEquals("Token: [hidden]", redactor.text("Token: synthetic-private-token"));
    }

    @Test
    void masksLargeSecretsAndIncompleteStrings() {
        String secret = "secret\\\"value\\\\path".repeat(100_000);
        DiagnosticRedactor redactor = new DiagnosticRedactor();
        assertEquals("{\"access_token\":\"[hidden]\"}", redactor.text("{\"access_token\":\"" + secret + "\"}"));
        assertEquals("{\"password\":\"[hidden]", redactor.text("{\"password\":\"unfinished-secret"));
    }

    @Test
    void keepsHtmlContentAfterLargeEmbeddedData() {
        String html = "<html><head><script>window.context={\"content\":\"" + "escaped\\\"text".repeat(100_000)
                + "\",\"sessionToken\":\"synthetic-session\"};</script></head><body><h1>Account</h1></body></html>";
        String result = assertDoesNotThrow(() -> new DiagnosticRedactor().body(html));
        assertTrue(result.contains("<h1>Account</h1>"));
        assertFalse(result.contains("synthetic-session"));
    }
}
