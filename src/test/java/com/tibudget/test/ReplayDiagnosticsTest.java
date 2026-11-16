package com.tibudget.test;

import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReplayDiagnosticsTest {
    @TempDir Path temp;

    @Test
    void checksPostBodiesWhenRequestedAndKeepsOldDefaultBehavior() throws Exception {
        AtomicInteger network = new AtomicInteger();
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            network.incrementAndGet();
            return new okhttp3.Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(ResponseBody.create("recorded", MediaType.parse("text/plain"))).build();
        }).build();
        OkHttpInternetProvider recorder = new OkHttpInternetProvider(client).harDir(temp.toFile());
        recorder.post("https://example.test/login", "password=synthetic-password", "application/x-www-form-urlencoded", Map.of());
        OkHttpClient forbidden = new OkHttpClient.Builder().addInterceptor(chain -> { fail("Replay must never call the client"); return null; }).build();
        OkHttpInternetProvider strict = new OkHttpInternetProvider(forbidden).replayDir(recorder.getHarDir()).verifyReplayRequestBody(true);
        ReplayMismatchException mismatch = assertThrows(ReplayMismatchException.class, () -> strict.post("https://example.test/login", "different-private-value", "application/x-www-form-urlencoded", Map.of()));
        assertEquals(1, mismatch.getRequestNumber());
        assertEquals("BODY", mismatch.getReason());
        assertFalse(mismatch.getMessage().contains("private"));
        assertEquals("recorded", new OkHttpInternetProvider(forbidden).replayDir(recorder.getHarDir()).post("https://example.test/login", "different-private-value", "text/plain", Map.of()).body);
        assertEquals("recorded", new OkHttpInternetProvider(forbidden).replayDir(recorder.getHarDir()).verifyReplayRequestBody(true).post("https://example.test/login", "password=synthetic-password", "text/plain", Map.of()).body);
        ReplayMismatchException urlMismatch = assertThrows(ReplayMismatchException.class, () -> new OkHttpInternetProvider(forbidden).replayDir(recorder.getHarDir())
                .get("https://example.test/changed?token=synthetic-private-token", Map.of()));
        assertEquals("METHOD_AND_URL", urlMismatch.getReason());
        assertFalse(urlMismatch.getMessage().contains("token"));
        assertEquals(1, network.get());
    }

    @Test
    void retriesTheSameResponseAfterAnInjectedOfflineFailure() throws Exception {
        java.nio.file.Files.writeString(temp.resolve("0001_get_example.test_retry.html"), "retried");
        OkHttpInternetProvider provider = new OkHttpInternetProvider().replayDir(temp.toFile()).failReplayRequest(1);
        assertThrows(java.io.IOException.class, () -> provider.get("https://example.test/retry", Map.of()));
        assertEquals(1, provider.getRequestCount());
        assertEquals("retried", provider.get("https://example.test/retry", Map.of()).body);
        provider.assertReplayComplete();
        assertEquals(2, provider.getRequestCount());
    }

    @Test
    void missingRecordingDiagnosticDoesNotExposeUrlSecrets() {
        OkHttpInternetProvider provider = new OkHttpInternetProvider().replayDir(temp.toFile());
        ReplayMismatchException mismatch = assertThrows(ReplayMismatchException.class, () -> provider.get("https://example.test/?token=synthetic-private-token", Map.of()));
        assertEquals("RECORDING_MISSING", mismatch.getReason());
        assertFalse(mismatch.getMessage().contains("token"));
    }
}
