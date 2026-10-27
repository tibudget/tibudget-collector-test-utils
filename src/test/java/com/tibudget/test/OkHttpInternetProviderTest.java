package com.tibudget.test;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OkHttpInternetProviderTest {
    @TempDir Path temp;

    @Test
    void getAndPostAcceptOptionalHeadersAndPreserveResponseDetails() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            calls.incrementAndGet();
            assertNull(chain.request().header("Accept-Encoding"));
            assertNull(chain.request().header("Set-Cookie"));
            return new okhttp3.Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(202).message("Accepted").header("X-Test", "value")
                    .body(ResponseBody.create("réponse", MediaType.parse("text/plain; charset=utf-8"))).build();
        }).build();
        OkHttpInternetProvider internet = new OkHttpInternetProvider(client).rateLimiter(1000);
        var response = internet.get("https://example.test/page", null);
        assertEquals(202, response.code);
        assertEquals("Accepted", response.message);
        assertEquals("réponse", response.body);
        assertEquals("value", response.headers.get("X-Test"));
        assertEquals("https://example.test/page", response.location);
        assertEquals("POST", internet.post("https://example.test/page", "data", "text/plain", null).method);
        internet.get("https://example.test/page", Map.of("Accept-Encoding", "gzip", "Set-Cookie", "ignored"));
        assertEquals(3, calls.get());
    }

    @Test
    void recordingFailureIsReportedInsteadOfCreatingAnIncompleteScenario() throws IOException {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain ->
                new okhttp3.Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK").body(ResponseBody.create("body", null)).build()).build();
        OkHttpInternetProvider provider = new OkHttpInternetProvider(client).harDir(temp.toFile());
        Files.delete(provider.getHarDir().toPath());
        Files.writeString(provider.getHarDir().toPath(), "not a directory");
        IOException error = assertThrows(IOException.class, () -> provider.get("https://example.test/page", Map.of()));
        assertTrue(error.getMessage().contains("Unable to record HAR"));
    }

    @Test
    void longUrlsCanBeRecordedAndReplayed() throws IOException {
        String url = "https://example.test/page?token=" + "x".repeat(1000);
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain ->
                new okhttp3.Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK").body(ResponseBody.create("long URL", null)).build()).build();
        OkHttpInternetProvider recorder = new OkHttpInternetProvider(client).harDir(temp.toFile());
        recorder.get(url, Map.of());
        OkHttpInternetProvider replay = new OkHttpInternetProvider().replayDir(recorder.getHarDir());
        assertEquals("long URL", replay.get(url, Map.of()).body);
        replay.assertReplayComplete();
    }
}
