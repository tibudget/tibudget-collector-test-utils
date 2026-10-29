package com.tibudget.test;

import com.tibudget.api.InternetProvider;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OkHttpInternetProviderReplayTest {
    @TempDir Path temp;

    @Test
    void requestsStopAfterTheSelectedReplayResponse() throws Exception {
        Files.writeString(temp.resolve("0001_get_example.test_first.html"), "first");
        Files.writeString(temp.resolve("0002_post_example.test_second.html"), "second");
        Path download = temp.resolve("download.txt");
        Files.writeString(download, "file");
        Files.writeString(temp.resolve("0003_dl_example.test_file.html"), download.toString());
        java.util.concurrent.atomic.AtomicInteger stops = new java.util.concurrent.atomic.AtomicInteger();
        OkHttpInternetProvider provider = new OkHttpInternetProvider().replayDir(temp.toFile());
        assertSame(provider, provider.stopAfterRequest(2, stops::incrementAndGet));

        assertEquals("first", provider.get("https://example.test/first", Map.of()).body);
        assertEquals(0, stops.get());
        assertEquals("second", provider.post("https://example.test/second", "", "text/plain", Map.of()).body);
        assertEquals(1, stops.get());
        provider.downloadFile("https://example.test/file", Map.of(), null);
        assertEquals(1, stops.get());
        provider.assertReplayComplete();
    }

    @Test
    void doesNotRequestStopWhenReplayFails() {
        java.util.concurrent.atomic.AtomicInteger stops = new java.util.concurrent.atomic.AtomicInteger();
        OkHttpInternetProvider provider = new OkHttpInternetProvider().replayDir(temp.toFile()).stopAfterRequest(1, stops::incrementAndGet);

        assertThrows(IOException.class, () -> provider.get("https://example.test/missing", Map.of()));
        assertEquals(0, stops.get());
        assertThrows(IllegalArgumentException.class, () -> provider.stopAfterRequest(0, () -> {}));
        assertThrows(NullPointerException.class, () -> provider.stopAfterRequest(1, null));
    }

    @Test
    void recordsAndReplaysResponsesAndBinaryDownloadsWithoutNetwork() throws Exception {
        byte[] binary = {0, 1, (byte) 255, 42};
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain ->
                new okhttp3.Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(201).message("Created").header("Content-Type", "application/pdf")
                        .header("X-Test", "recorded")
                        .body(ResponseBody.create(chain.request().url().encodedPath().equals("/file")
                                ? binary : "réponse".getBytes(java.nio.charset.StandardCharsets.UTF_8), null))
                        .build()).build();
        File directory = temp.resolve("har").toFile();
        OkHttpInternetProvider recorder = new OkHttpInternetProvider().okHttpClient(client)
                .downloadDir(temp.toFile()).harDir(directory);
        directory = recorder.getHarDir();
        recorder.post("https://example.test/replay", "input", "text/plain", Map.of());
        recorder.downloadFile("https://example.test/file", Map.of(), null);
        try {
            OkHttpInternetProvider replay = new OkHttpInternetProvider().okHttpClient(new OkHttpClient())
                    .replayDir(directory).downloadDir(temp.resolve("replay-downloads").toString());
            InternetProvider.Response response = replay.post("https://example.test/replay", "input", "text/plain", Map.of());
            assertEquals(201, response.code);
            assertEquals("Created", response.message);
            assertEquals("réponse", response.body);
            assertEquals("recorded", response.headers.get("X-Test"));
            assertEquals("https://example.test/replay", response.location);
            InternetProvider.Response download = replay.downloadFile("https://example.test/file", Map.of(), null);
            assertEquals(temp.resolve("replay-downloads").toAbsolutePath(), Path.of(download.body).getParent());
            assertArrayEquals(binary, Files.readAllBytes(Path.of(download.body)));
            Files.delete(Path.of(download.body));
            assertThrows(IOException.class, () -> replay.get("https://example.test/extra", Map.of()));
        } finally {
            Files.deleteIfExists(directory.toPath().resolve("0001_post_example.test_replay.har"));
            Files.deleteIfExists(directory.toPath().resolve("0002_dl_example.test_file.har"));
        }
    }

    @Test
    void htmlAndHarCanBeEnabledIndependently() throws Exception {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain ->
                new okhttp3.Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK")
                        .body(ResponseBody.create("<html>développement</html>", MediaType.parse("text/html")))
                        .build()).build();
        for (boolean recordHtml : new boolean[]{false, true}) {
            for (boolean recordHar : new boolean[]{false, true}) {
                File directory = temp.resolve("recording-" + recordHtml + "-" + recordHar).toFile();
                OkHttpInternetProvider recorder = new OkHttpInternetProvider().okHttpClient(client);
                if (recordHtml) recorder.htmlDir(directory);
                if (recordHar) recorder.harDir(directory);
                recorder.get("https://example.test/page", Map.of());
                if (!recordHtml && !recordHar) {
                    assertFalse(directory.exists());
                    continue;
                }
                directory = recordHar ? recorder.getHarDir() : recorder.getHtmlDir();
                if (recordHtml && recordHar) assertEquals(recorder.getHtmlDir(), recorder.getHarDir());
                Path html = directory.toPath().resolve("0001_get_example.test_page.html");
                Path har = directory.toPath().resolve("0001_get_example.test_page.har");
                try {
                    assertEquals(recordHtml, Files.exists(html));
                    assertEquals(recordHar, Files.exists(har));
                    if (recordHtml) {
                        assertEquals("<html>développement</html>", Files.readString(html));
                    }
                    assertEquals("<html>développement</html>", new OkHttpInternetProvider().okHttpClient(new OkHttpClient()).replayDir(directory)
                            .get("https://example.test/page", Map.of()).body);
                } finally {
                    Files.deleteIfExists(html);
                    Files.deleteIfExists(har);
                    Files.delete(directory.toPath());
                }
            }
        }
    }

    @Test
    void fluentDirectoriesEnableFormatsAndKeepMatchingIndexes() throws Exception {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain ->
                new okhttp3.Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK")
                        .body(ResponseBody.create("<html>test</html>", MediaType.parse("text/html")))
                        .build()).build();
        Path html = temp.resolve("debug/html");
        Path har = temp.resolve("scenario/har");
        OkHttpInternetProvider provider = new OkHttpInternetProvider().okHttpClient(client);
        assertSame(provider, provider.htmlDir(html.toFile()).harDir(har.toString()));
        assertEquals(html.toFile(), provider.getHtmlDir().getParentFile());
        assertEquals(har.toFile(), provider.getHarDir().getParentFile());
        assertEquals(provider.getHtmlDir().getName(), provider.getHarDir().getName());
        assertTrue(provider.getHarDir().getName().matches("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}_\\d{3}(_\\d+)?"));
        html = provider.getHtmlDir().toPath();
        har = provider.getHarDir().toPath();
        provider.get("https://example.test/page", Map.of());
        assertEquals("<html>test</html>", Files.readString(html.resolve("0001_get_example.test_page.html")));
        assertTrue(Files.isRegularFile(har.resolve("0001_get_example.test_page.har")));
        assertFalse(Files.exists(html.resolve("0001_get_example.test_page.har")));
        assertFalse(Files.exists(har.resolve("0001_get_example.test_page.html")));
        assertEquals("<html>test</html>", new OkHttpInternetProvider().okHttpClient(new OkHttpClient()).replayDir(har.toFile())
                .get("https://example.test/page", Map.of()).body);

        Path htmlOnly = temp.resolve("html-only");
        OkHttpInternetProvider htmlProvider = new OkHttpInternetProvider().okHttpClient(client).htmlDir(htmlOnly.toString());
        htmlProvider.get("https://example.test/page", Map.of());
        htmlOnly = htmlProvider.getHtmlDir().toPath();
        assertTrue(Files.exists(htmlOnly.resolve("0001_get_example.test_page.html")));
        assertFalse(Files.exists(htmlOnly.resolve("0001_get_example.test_page.har")));
        Path harOnly = temp.resolve("har-only");
        OkHttpInternetProvider harProvider = new OkHttpInternetProvider().okHttpClient(client).harDir(harOnly.toFile());
        harProvider.get("https://example.test/page", Map.of());
        harOnly = harProvider.getHarDir().toPath();
        assertTrue(Files.exists(harOnly.resolve("0001_get_example.test_page.har")));
        assertFalse(Files.exists(harOnly.resolve("0001_get_example.test_page.html")));

        Path file = temp.resolve("file");
        Files.writeString(file, "not a directory");
        assertThrows(IllegalStateException.class, () -> provider.htmlDir(file.toFile()));
        assertThrows(IllegalStateException.class, () -> provider.harDir(file.toFile()));
    }

    @Test
    void providersUseSeparateSessionsWithoutOverwritingExistingRecordings() throws Exception {
        OkHttpInternetProvider first = new OkHttpInternetProvider().htmlDir(temp.toFile()).harDir(temp.toFile());
        Path recording = first.getHarDir().toPath().resolve("existing.har");
        Files.writeString(recording, "existing recording");
        OkHttpInternetProvider second = new OkHttpInternetProvider().htmlDir(temp.toFile()).harDir(temp.toFile());
        assertNotEquals(first.getHarDir(), second.getHarDir());
        assertEquals(first.getHtmlDir(), first.getHarDir());
        assertEquals(second.getHtmlDir(), second.getHarDir());
        first.harDir(temp.toFile());
        assertEquals(recording.getParent(), first.getHarDir().toPath());
        assertEquals("existing recording", Files.readString(recording));
    }

    @Test
    void existingSessionNamesReceiveAnIncrementingSuffix() throws Exception {
        OkHttpInternetProvider provider = new OkHttpInternetProvider().htmlDir(temp.resolve("first").toFile());
        String timestamp = provider.getHtmlDir().getName();
        assertTrue(timestamp.matches("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}_\\d{3}"));
        Path base = temp.resolve("collisions");
        Files.createDirectories(base.resolve(timestamp));
        Files.createDirectory(base.resolve(timestamp + "_1"));
        Path sentinel = base.resolve(timestamp).resolve("existing.html");
        Files.writeString(sentinel, "preserved");
        provider.harDir(base.toFile());
        assertEquals(timestamp + "_2", provider.getHarDir().getName());
        assertEquals("preserved", Files.readString(sentinel));
        provider.htmlDir(base.toString());
        assertEquals(provider.getHarDir(), provider.getHtmlDir());
        provider.harDir(base.toFile());
        assertEquals(timestamp + "_2", provider.getHarDir().getName());
    }

    @Test
    void nullRecordingDirectoriesDisableRecording() throws Exception {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain ->
                new okhttp3.Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK").body(ResponseBody.create("test", null)).build()).build();
        Path directory = temp.resolve("disabled");
        OkHttpInternetProvider provider = new OkHttpInternetProvider().okHttpClient(client)
                .htmlDir(directory.toFile()).harDir(directory.toString())
                .htmlDir((File) null).harDir((String) null);
        assertNull(provider.getHtmlDir());
        assertNull(provider.getHarDir());
        provider.get("https://example.test/page", Map.of());
        Path session;
        try (java.util.stream.Stream<Path> directories = Files.list(directory)) {
            session = directories.findFirst().orElseThrow();
        }
        try (java.util.stream.Stream<Path> files = Files.list(session)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void cookiesFilePreservesClientOptionsAndPersistsCookies() throws Exception {
        OkHttpClient original = new OkHttpClient.Builder()
                .followRedirects(false).callTimeout(12, java.util.concurrent.TimeUnit.SECONDS).build();
        File cookies = temp.resolve("cookies/session.bin").toFile();
        OkHttpInternetProvider provider = new OkHttpInternetProvider().okHttpClient(original);
        assertSame(provider, provider.cookiesFile(cookies.toString()));
        java.lang.reflect.Field field = OkHttpInternetProvider.class.getDeclaredField("client");
        field.setAccessible(true);
        OkHttpClient configured = (OkHttpClient) field.get(provider);
        assertFalse(configured.followRedirects());
        assertEquals(original.callTimeoutMillis(), configured.callTimeoutMillis());
        assertSame(CookieJar.NO_COOKIES, original.cookieJar());
        HttpUrl url = HttpUrl.get("https://example.test/");
        configured.cookieJar().saveFromResponse(url, java.util.List.of(new Cookie.Builder()
                .name("session").value("test").domain("example.test").build()));
        assertTrue(cookies.isFile());
        assertEquals("test", new AdvancedCookieJar(cookies).loadForRequest(url).get(0).value());
    }

    @Test
    void replaySkipsRateLimiterForGetPostAndDownloads() throws Exception {
        Files.writeString(temp.resolve("0001_get_example.test_page.html"), "GET response");
        Files.writeString(temp.resolve("0002_post_example.test_page.html"), "POST response");
        Path download = temp.resolve("document.pdf");
        Files.write(download, new byte[]{0, 1, 2});
        Files.writeString(temp.resolve("0003_dl_example.test_file.html"), download.toString());
        RateLimiter limiter = new RateLimiter(1.0 / 60);
        limiter.acquire();
        OkHttpInternetProvider replay = new OkHttpInternetProvider()
                .rateLimiter(limiter).replayDir(temp.toFile());

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            assertEquals("GET response", replay.get("https://example.test/page", Map.of()).body);
            assertEquals("POST response", replay.post("https://example.test/page", "", "text/plain", Map.of()).body);
            assertEquals(download.toString(), replay.downloadFile("https://example.test/file", Map.of(), null).body);
        });
    }

    @Test
    void completionCheckRejectsShortenedScenarios() throws Exception {
        Files.writeString(temp.resolve("0001_get_example.test_page.html"), "first response");
        Files.writeString(temp.resolve("0002_get_example.test_page.html"), "second response");
        OkHttpInternetProvider replay = new OkHttpInternetProvider().replayDir(temp.toFile());
        assertThrows(IOException.class, replay::assertReplayComplete);
        replay.get("https://example.test/page", null);
        assertThrows(IOException.class, replay::assertReplayComplete);
        replay.get("https://example.test/page", null);
        assertDoesNotThrow(replay::assertReplayComplete);
        assertThrows(IllegalStateException.class, new OkHttpInternetProvider()::assertReplayComplete);
    }

    @Test
    void rejectsDifferentIndexMethodOrUrl() throws Exception {
        Files.writeString(temp.resolve("0001_get_example.test_page.html"), "legacy body");
        assertEquals("legacy body", new OkHttpInternetProvider().okHttpClient(new OkHttpClient()).replayDir(temp.toFile())
                .get("https://example.test/page", Map.of()).body);
        assertThrows(IOException.class, () -> new OkHttpInternetProvider().okHttpClient(new OkHttpClient()).replayDir(temp.toFile())
                .get("https://example.test/other", Map.of()));
        assertThrows(IOException.class, () -> new OkHttpInternetProvider().okHttpClient(new OkHttpClient()).replayDir(temp.toFile())
                .post("https://example.test/page", "", "text/plain", Map.of()));
        OkHttpInternetProvider replay = new OkHttpInternetProvider().okHttpClient(new OkHttpClient()).replayDir(temp.toFile());
        replay.get("https://example.test/page", Map.of());
        IOException error = assertThrows(IOException.class, () -> replay.get("https://example.test/page", Map.of()));
        assertTrue(error.getMessage().contains("request 2"));
    }

    @Test
    void rejectsWrongUrlsEvenWhenNormalizedFilenamesMatchAndRejectsUnsupportedHar() throws Exception {
        String fixture = """
                {"log":{"version":"1.2","entries":[{
                  "request":{"method":"GET","url":"https://example.test/page?a=b"},
                  "response":{"status":200,"statusText":"OK","httpVersion":"http/1.1","headers":[],
                    "content":{"text":"body","mimeType":"text/plain"}}
                }]}}
                """;
        Path recording = temp.resolve("0001_get_example.test_page_a_b.har");
        Files.writeString(recording, fixture);
        OkHttpInternetProvider replay = new OkHttpInternetProvider().replayDir(temp.toFile());
        assertEquals("body", replay.get("https://example.test/page?a=b", Map.of()).body);
        // These two URLs normalize to the same filename; only exact HAR matching rejects this one.
        assertThrows(IOException.class, () -> new OkHttpInternetProvider().replayDir(temp.toFile())
                .get("https://example.test/page?a_b", Map.of()));
        com.google.gson.JsonObject har = com.google.gson.JsonParser.parseString(fixture).getAsJsonObject();
        var log = har.getAsJsonObject("log");
        log.addProperty("version", "1.1");
        Files.writeString(recording, har.toString());
        assertThrows(IOException.class, () -> new OkHttpInternetProvider().replayDir(temp.toFile())
                .get("https://example.test/page?a=b", Map.of()));
        log.addProperty("version", "1.2");
        var entries = log.getAsJsonArray("entries");
        entries.add(entries.get(0).deepCopy());
        Files.writeString(recording, har.toString());
        assertThrows(IOException.class, () -> new OkHttpInternetProvider().replayDir(temp.toFile())
                .get("https://example.test/page?a=b", Map.of()));
        entries.remove(1);
        entries.get(0).getAsJsonObject().getAsJsonObject("response").getAsJsonObject("content").addProperty("encoding", "gzip");
        Files.writeString(recording, har.toString());
        assertThrows(IOException.class, () -> new OkHttpInternetProvider().replayDir(temp.toFile())
                .get("https://example.test/page?a=b", Map.of()));
    }

    @Test
    void rejectsMissingDirectoryAndMalformedHar() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new OkHttpInternetProvider().okHttpClient(new OkHttpClient()).replayDir(temp.resolve("missing").toFile()));
        Files.writeString(temp.resolve("0001_get_example.test_page.har"), "invalid");
        assertThrows(IOException.class, () -> new OkHttpInternetProvider().okHttpClient(new OkHttpClient()).replayDir(temp.toFile())
                .get("https://example.test/page", Map.of()));
    }
}
