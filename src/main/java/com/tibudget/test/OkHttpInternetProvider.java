package com.tibudget.test;

import com.tibudget.api.InternetProvider;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

public class OkHttpInternetProvider implements InternetProvider {

    private final OkHttpClient client;
    private final File downloadDir;
    private static final RateLimiter rateLimiter = new RateLimiter(2);

    public OkHttpInternetProvider() {
        this.client = new OkHttpClient.Builder()
                // This AdvancedCookieJar is almost the same as in ti'bu app
                .cookieJar(new AdvancedCookieJar())
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
        this.downloadDir = initDownloadDir();
    }

    public OkHttpInternetProvider(OkHttpClient client) {
        this.client = client;
        this.downloadDir = initDownloadDir();
    }

    private static File initDownloadDir() {
        File dir = new File("target/downloads/");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Unable to create download directory: " + dir.getAbsolutePath());
        }
        return dir;
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

        try (okhttp3.Response response = client.newCall(request).execute()) {

            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }

            String contentType = response.header("Content-Type");
            if (forceContentType != null) {
                contentType = forceContentType;
            }

            String extension = guessExtension(contentType);
            File out = File.createTempFile("dl_", "." + extension, downloadDir);

            try (InputStream in = response.body().byteStream();
                 OutputStream os = new FileOutputStream(out)) {
                in.transferTo(os);
            }

            return buildResponse(
                    response,
                    "GET",
                    out.getAbsolutePath()
            );
        }
    }

    /* =========================
     * CORE EXECUTION
     * ========================= */

    private Response execute(Request request, String method) throws IOException {
        rateLimiter.acquire();
        try (okhttp3.Response response = client.newCall(request).execute()) {
            String body = response.body() != null
                    ? response.body().string()
                    : "";
            return buildResponse(response, method, body);
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
