package com.tibudget.test;

import com.tibudget.api.InternetProvider;
import com.google.gson.*;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class OkHttpInternetProvider implements InternetProvider {

    private OkHttpClient client;
    private File downloadDir = new File("target/downloads/");
    private RateLimiter rateLimiter = new RateLimiter(2);
    private File replayDir;
    private File htmlDir;
    private File harDir;
    private File cookiesFile;
    private final String recordingSession = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss_SSS"));
    private final Map<File, File> recordingDirectories = new HashMap<>();
    private final AtomicInteger trafficIndex = new AtomicInteger(0);

    public OkHttpInternetProvider() {
        this.client =  new OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
    }

    public OkHttpInternetProvider(OkHttpClient okHttpClient) {
        this.client = okHttpClient;
    }

    public OkHttpInternetProvider okHttpClient(OkHttpClient client) {
        this.client = java.util.Objects.requireNonNull(client, "client");
        return this;
    }

    /** Sets this provider's rate limiter. The default allows two requests per second. */
    public OkHttpInternetProvider rateLimiter(RateLimiter rateLimiter) {
        this.rateLimiter = java.util.Objects.requireNonNull(rateLimiter, "rateLimiter");
        return this;
    }

    /** Configures the maximum request rate in requests per second. */
    public OkHttpInternetProvider rateLimiter(double requestsPerSecond) {
        return rateLimiter(new RateLimiter(requestsPerSecond));
    }

    /** Records HTML in a dated session subdirectory of this directory. Null disables recording. Configure before requests. */
    public OkHttpInternetProvider htmlDir(File directory) {
        this.htmlDir = directory == null ? null : createRecordingDirectory(directory);
        return this;
    }

    public OkHttpInternetProvider htmlDir(String directory) {
        return htmlDir(directory == null ? null : new File(directory));
    }

    /** Records HAR in a dated session subdirectory of this directory. Null disables recording. Configure before requests. */
    public OkHttpInternetProvider harDir(File directory) {
        this.harDir = directory == null ? null : createRecordingDirectory(directory);
        return this;
    }

    public OkHttpInternetProvider harDir(String directory) {
        return harDir(directory == null ? null : new File(directory));
    }

    /** Sets the download directory, creating it if needed. Defaults to target/downloads. */
    public OkHttpInternetProvider downloadDir(File directory) {
        this.downloadDir = initDownloadDir(java.util.Objects.requireNonNull(directory, "downloadDir"));
        return this;
    }

    public OkHttpInternetProvider downloadDir(String directory) {
        return downloadDir(new File(java.util.Objects.requireNonNull(directory, "downloadDir")));
    }

    /** Installs a persistent cookie jar on a copy of the supplied client's configuration. */
    public OkHttpInternetProvider cookiesFile(File file) {
        java.util.Objects.requireNonNull(file, "cookiesFile");
        if (file.isDirectory()) {
            throw new IllegalArgumentException("Cookies file is a directory: " + file);
        }
        initDownloadDir(file.getAbsoluteFile().getParentFile());
        this.client = client.newBuilder().cookieJar(new AdvancedCookieJar(file)).build();
        this.cookiesFile = file;
        return this;
    }

    public OkHttpInternetProvider cookiesFile(String file) {
        return cookiesFile(new File(java.util.Objects.requireNonNull(file, "cookiesFile")));
    }

    /** Replays an existing scenario without network requests. Configure before requests. */
    public OkHttpInternetProvider replayDir(File directory) {
        if (directory == null || !directory.isDirectory()) {
            throw new IllegalArgumentException("Replay directory does not exist: " + directory);
        }
        this.replayDir = directory;
        return this;
    }

    public OkHttpInternetProvider replayDir(String directory) {
        return replayDir(new File(java.util.Objects.requireNonNull(directory, "replayDir")));
    }

    /** Returns the actual dated HTML session directory, or null if disabled. */
    public File getHtmlDir() {
        return htmlDir;
    }

    /** Returns the actual dated HAR session directory, or null if disabled. */
    public File getHarDir() {
        return harDir;
    }

    /** Returns the configured download directory (target/downloads by default). */
    public File getDownloadDir() {
        return downloadDir;
    }

    /** Returns the replay directory, or null if replay is disabled. */
    public File getReplayDir() {
        return replayDir;
    }

    /** Returns the cookies file configured on this provider, or null if none was configured. */
    public File getCookiesFile() {
        return cookiesFile;
    }

    private synchronized File createRecordingDirectory(File baseDirectory) {
        initDownloadDir(baseDirectory);
        try {
            File key = baseDirectory.getCanonicalFile();
            File existing = recordingDirectories.get(key);
            if (existing != null) {
                return existing;
            }
            for (int index = 0; ; index++) {
                String name = recordingSession + (index == 0 ? "" : "_" + index);
                File sessionDirectory = new File(baseDirectory, name);
                try {
                    Files.createDirectory(sessionDirectory.toPath());
                    recordingDirectories.put(key, sessionDirectory);
                    return sessionDirectory;
                } catch (java.nio.file.FileAlreadyExistsException e) {
                    // Try the next index without reusing another session's directory.
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create recording directory in " + baseDirectory, e);
        }
    }

    private static File initDownloadDir(File dir) {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Unable to create download directory: " + dir.getAbsolutePath());
        }
        if (!dir.isDirectory()) {
            throw new IllegalStateException("Not a directory: " + dir.getAbsolutePath());
        }
        return dir;
    }

    private String formatIndex(int index) {
        return String.format("%04d", index);
    }

    private String trafficFileName(int index, String method, String url) {
        return formatIndex(index) + "_" + method.toLowerCase(Locale.ROOT) + "_" + normalizeUrl(url) + ".html";
    }

    private void recordIfEnabled(String method, Request original, Response response, File download) {
        if (htmlDir == null && harDir == null) {
            return;
        }
        String url = original.url().toString();
        String fileName = trafficFileName(trafficIndex.incrementAndGet(), method, url);
        if (htmlDir != null) {
            try {
                Files.writeString(new File(htmlDir, fileName).toPath(), response.body, StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // Never break HTTP flow if HTML recording fails
            }
        }
        if (harDir == null) {
            return;
        }
        try {
            File out = new File(harDir, fileName
                    .replaceFirst("\\.html$", ".har"));
            JsonObject request = new JsonObject();
            request.addProperty("method", "dl".equals(method) ? "GET" : method);
            request.addProperty("url", url);
            request.addProperty("httpVersion", response.protocol);
            request.add("cookies", new JsonArray());
            JsonArray requestHeaders = new JsonArray();
            for (int i = 0; i < original.headers().size(); i++) {
                JsonObject header = new JsonObject();
                header.addProperty("name", original.headers().name(i));
                header.addProperty("value", original.headers().value(i));
                requestHeaders.add(header);
            }
            request.add("headers", requestHeaders);
            JsonArray query = new JsonArray();
            for (int i = 0; i < original.url().querySize(); i++) {
                JsonObject parameter = new JsonObject();
                parameter.addProperty("name", original.url().queryParameterName(i));
                String value = original.url().queryParameterValue(i);
                parameter.addProperty("value", value == null ? "" : value);
                query.add(parameter);
            }
            request.add("queryString", query);
            if (original.body() != null) {
                okio.Buffer buffer = new okio.Buffer();
                original.body().writeTo(buffer);
                JsonObject postData = new JsonObject();
                postData.addProperty("mimeType", original.body().contentType() == null
                        ? "application/octet-stream" : original.body().contentType().toString());
                postData.addProperty("text", buffer.readUtf8());
                request.add("postData", postData);
            }
            request.addProperty("headersSize", -1);
            request.addProperty("bodySize", -1);

            JsonArray headers = new JsonArray();
            response.headers.forEach((name, value) -> {
                JsonObject header = new JsonObject();
                header.addProperty("name", name);
                header.addProperty("value", value);
                headers.add(header);
            });
            byte[] bytes = download == null ? response.body.getBytes(StandardCharsets.UTF_8)
                    : Files.readAllBytes(download.toPath());
            JsonObject content = new JsonObject();
            content.addProperty("size", bytes.length);
            String mimeType = "application/octet-stream";
            for (Map.Entry<String, String> header : response.headers.entrySet()) {
                if ("Content-Type".equalsIgnoreCase(header.getKey())) mimeType = header.getValue();
            }
            content.addProperty("mimeType", mimeType);
            content.addProperty("text", Base64.getEncoder().encodeToString(bytes));
            content.addProperty("encoding", "base64");
            JsonObject recorded = new JsonObject();
            recorded.addProperty("status", response.code);
            recorded.addProperty("statusText", response.message);
            recorded.addProperty("httpVersion", response.protocol);
            recorded.add("headers", headers);
            recorded.add("cookies", new JsonArray());
            recorded.add("content", content);
            recorded.addProperty("redirectURL", "");
            recorded.addProperty("headersSize", -1);
            recorded.addProperty("bodySize", bytes.length);
            recorded.addProperty("_location", response.location);
            JsonObject entry = new JsonObject();
            entry.addProperty("startedDateTime", java.time.OffsetDateTime.now().toString());
            entry.addProperty("time", 0);
            entry.add("request", request);
            entry.add("response", recorded);
            entry.add("cache", new JsonObject());
            JsonObject timings = new JsonObject();
            timings.addProperty("send", 0);
            timings.addProperty("wait", 0);
            timings.addProperty("receive", 0);
            entry.add("timings", timings);
            JsonArray entries = new JsonArray();
            entries.add(entry);
            JsonObject log = new JsonObject();
            log.addProperty("version", "1.2");
            JsonObject creator = new JsonObject();
            creator.addProperty("name", "OkHttpInternetProvider");
            creator.addProperty("version", "1.0");
            log.add("creator", creator);
            log.add("entries", entries);
            JsonObject har = new JsonObject();
            har.add("log", log);
            Files.writeString(out.toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(har));
        } catch (Exception ignored) {
            // Never break HTTP flow if recording fails
        }
    }

    private Response replay(String method, String url, String forceContentType) throws IOException {
        int index = trafficIndex.incrementAndGet();
        File legacy = new File(replayDir, trafficFileName(index, method, url));
        File file = new File(replayDir, legacy.getName().replaceFirst("\\.html$", ".har"));
        if (!file.isFile()) {
            if (legacy.isFile()) {
                String body = Files.readString(legacy.toPath(), StandardCharsets.UTF_8);
                if ("dl".equals(method) && !new File(body).isFile()) {
                    throw new IOException("Downloaded file from legacy recording is missing: " + body);
                }
                return new Response(200, "OK", "dl".equals(method) ? "GET" : method,
                        url, "http/1.1", body, new HashMap<>());
            }
            throw new IOException("Scenario mismatch at request " + index + " (" + method + " " + url
                    + "): expected recording " + file.getAbsolutePath());
        }
        try {
            JsonObject entry = JsonParser.parseString(Files.readString(file.toPath()))
                    .getAsJsonObject().getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();
            JsonObject request = entry.getAsJsonObject("request");
            String httpMethod = "dl".equals(method) ? "GET" : method;
            if (!url.equals(request.get("url").getAsString())
                    || !httpMethod.equals(request.get("method").getAsString())) {
                throw new IOException("Scenario mismatch at request " + index + " (" + method + " " + url + ")");
            }
            JsonObject response = entry.getAsJsonObject("response");
            Map<String, String> headers = new HashMap<>();
            for (JsonElement element : response.getAsJsonArray("headers")) {
                JsonObject header = element.getAsJsonObject();
                headers.put(header.get("name").getAsString(), header.get("value").getAsString());
            }
            JsonObject content = response.getAsJsonObject("content");
            String text = content.has("text") ? content.get("text").getAsString() : "";
            byte[] bytes = content.has("encoding") && "base64".equals(content.get("encoding").getAsString())
                    ? Base64.getDecoder().decode(text) : text.getBytes(StandardCharsets.UTF_8);
            String body;
            if ("dl".equals(method)) {
                File download = File.createTempFile("dl_", "." + guessExtension(forceContentType != null
                        ? forceContentType : content.get("mimeType").getAsString()), initDownloadDir(downloadDir));
                Files.write(download.toPath(), bytes);
                body = download.getAbsolutePath();
            } else {
                body = new String(bytes, StandardCharsets.UTF_8);
            }
            String location = response.has("_location") ? response.get("_location").getAsString() : url;
            return new Response(response.get("status").getAsInt(), response.get("statusText").getAsString(),
                    httpMethod, location, response.get("httpVersion").getAsString(), body, headers);
        } catch (RuntimeException e) {
            throw new IOException("Invalid HAR recording: " + file, e);
        }
    }

    private String normalizeUrl(String url) {
        return url
                .replace("https://", "")
                .replace("http://", "")
                .replaceAll("[^a-zA-Z0-9\\.\\-_/]", "_")
                .replace("/", "_");
    }

    /* =========================
     * GET
     * ========================= */

    @Override
    public Response get(
            String url,
            Map<String, String> headers
    ) throws IOException {
        Request request = buildRequest("GET", url, null, headers);
        return execute(request, "GET");
    }

    /* =========================
     * POST
     * ========================= */

    @Override
    public Response post(
            String url,
            String body,
            String contentType,
            Map<String, String> headers
    ) throws IOException {

        RequestBody requestBody = RequestBody.create(
                body,
                MediaType.parse(contentType)
        );

        Map<String, String> hdrs = new HashMap<>(headers);
        hdrs.put("Content-Type", contentType);

        Request request = buildRequest("POST", url, requestBody, hdrs);
        return execute(request, "POST");
    }

    /* =========================
     * DOWNLOAD
     * ========================= */

    @Override
    public Response downloadFile(
            String url,
            Map<String, String> headers,
            String forceContentType
    ) throws IOException {

        Request request = buildRequest("GET", url, null, headers);

        if (replayDir != null) {
            return replay("dl", request.url().toString(), forceContentType);
        }

        try (okhttp3.Response response = client.newCall(request).execute()) {

            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }

            String contentType = response.header("Content-Type");
            if (forceContentType != null) {
                contentType = forceContentType;
            }

            String extension = guessExtension(contentType);
            File out = File.createTempFile("dl_", "." + extension, initDownloadDir(downloadDir));

            try (InputStream in = response.body().byteStream();
                 OutputStream os = new FileOutputStream(out)) {
                in.transferTo(os);
            }

            Response result = buildResponse(response, "GET", out.getAbsolutePath());
            recordIfEnabled("dl", request, result, out);
            return result;
        }
    }

    /* =========================
     * CORE EXECUTION
     * ========================= */

    private Response execute(Request request, String method) throws IOException {
        if (replayDir != null) {
            return replay(method, request.url().toString(), null);
        }
        rateLimiter.acquire();
        try (okhttp3.Response response = client.newCall(request).execute()) {
            String body = response.body() != null
                    ? response.body().string()
                    : "";
            Response result = buildResponse(response, method, body);
            recordIfEnabled(method, request, result, null);
            return result;
        }
    }

    /* =========================
     * REQUEST BUILDING
     * ========================= */

    private Request buildRequest(
            String method,
            String url,
            RequestBody body,
            Map<String, String> headers
    ) {

        Request.Builder builder = new Request.Builder().url(url);

        // Headers (filter Accept-Encoding)
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (!e.getKey().equalsIgnoreCase("Accept-Encoding")
                    && !e.getKey().equalsIgnoreCase("Set-Cookie")) {
                builder.addHeader(e.getKey(), e.getValue());
            }
        }

        if ("POST".equals(method)) {
            builder.post(body != null ? body : RequestBody.create(new byte[0]));
        } else {
            builder.get();
        }

        return builder.build();
    }

    /* =========================
     * RESPONSE BUILDING
     * ========================= */

    private Response buildResponse(
            okhttp3.Response response,
            String method,
            String body
    ) {
        // Extract the last known location (URL) from all possible redirections.
        // If no redirection then the location is the URL we provided
        String location = extractLastLocationFromResponseChain(response);
        if (location == null || location.isBlank()) {
            location = response.request().url().toString();
        }

        // Simply keep one value
        Map<String, String> headers = new HashMap<>();
        for (String name : response.headers().names()) {
            String value = response.header(name);
            headers.put(name, value != null ? value : "");
        }

        return new InternetProvider.Response(
                response.code(),
                response.message(),
                method,
                location,
                response.protocol().toString(),
                body,
                headers
        );
    }

    /* =========================
     * HELPERS
     * ========================= */

    private static String extractLastLocationFromResponseChain(okhttp3.Response response) {
        okhttp3.Response current = response;
        while (current != null) {
            String location = current.header("Location");
            if (location != null && !location.isBlank()) {
                return location;
            }
            current = current.priorResponse();
        }
        return null;
    }

    private String guessExtension(String contentType) {
        if (contentType == null) return "bin";
        if (contentType.contains("pdf")) return "pdf";
        if (contentType.contains("json")) return "json";
        if (contentType.contains("html")) return "html";
        return "bin";
    }
}
