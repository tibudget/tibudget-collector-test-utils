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
    void postDownloadsSendPayloadAndReplayBinaryFiles() throws IOException {
        byte[] bytes = {0, 1, (byte) 255, 42};
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            assertEquals("POST", chain.request().method());
            assertEquals("test", chain.request().header("X-Test"));
            assertEquals("application/json", chain.request().header("Content-Type"));
            okio.Buffer payload = new okio.Buffer();
            chain.request().body().writeTo(payload);
            assertEquals("{\"id\":1}", payload.readUtf8());
            return new okhttp3.Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").header("Content-Type", "application/pdf")
                    .body(ResponseBody.create(bytes, MediaType.parse("application/pdf"))).build();
        }).build();
        String url = "https://example.test/document";
        OkHttpInternetProvider recorder = new OkHttpInternetProvider(client).downloadDir(temp.resolve("live").toFile()).harDir(temp.resolve("recordings").toFile());
        var response = recorder.downloadFile(url, Map.of("X-Test", "test"), null, "{\"id\":1}", "application/json");
        assertEquals("POST", response.method);
        assertEquals("application/pdf", response.headers.get("Content-Type"));
        assertTrue(response.body.endsWith(".pdf"));
        assertArrayEquals(bytes, Files.readAllBytes(Path.of(response.body)));
        assertEquals(1, recorder.getRequestCount());

        OkHttpInternetProvider replay = new OkHttpInternetProvider().replayDir(recorder.getHarDir())
                .downloadDir(temp.resolve("replay").toFile()).verifyReplayRequestBody(true);
        assertThrows(IOException.class, replay::assertReplayComplete);
        Files.delete(Path.of(response.body));
        var replayed = replay.downloadFile(url, null, "application/json", "{\"id\":1}", "application/json");
        assertEquals("POST", replayed.method);
        assertTrue(replayed.body.endsWith(".json"));
        assertArrayEquals(bytes, Files.readAllBytes(Path.of(replayed.body)));
        replay.assertReplayComplete();
        OkHttpInternetProvider mismatch = new OkHttpInternetProvider().replayDir(recorder.getHarDir()).verifyReplayRequestBody(true);
        assertThrows(ReplayMismatchException.class, () -> mismatch.downloadFile(url, null, null, "different", "application/json"));
    }

    @Test
    void postDownloadReportsHttpFailureWithoutSavingAFile() throws IOException {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain ->
                new okhttp3.Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(403).message("Forbidden").body(ResponseBody.create("denied", null)).build()).build();
        OkHttpInternetProvider provider = new OkHttpInternetProvider(client).downloadDir(temp.toFile());
        IOException error = assertThrows(IOException.class, () -> provider.downloadFile("https://example.test/file", null, null, "", "text/plain"));
        assertEquals("HTTP 403", error.getMessage());
        try (var files = Files.list(temp)) {
            assertEquals(0, files.count());
        }
    }

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
