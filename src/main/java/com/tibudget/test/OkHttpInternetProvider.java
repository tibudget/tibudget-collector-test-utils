package com.tibudget.test;

import com.tibudget.api.InternetProvider;
import okhttp3.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

public class OkHttpInternetProvider implements InternetProvider {

    private final OkHttpClient client;

    public OkHttpInternetProvider(OkHttpClient client) {
        this.client = client;
    }

    public OkHttpInternetProvider() {
        this(new OkHttpClient());
    }

    /* =============================
     * GET
     * ============================= */

    @Override
    public Response get(String url,
                        Map<String, String> headers,
                        Map<String, String> cookies) throws IOException {

        Request request = buildRequest("GET", url, null, null, headers, cookies);
        return execute(request, "GET");
    }

    /* =============================
     * POST
     * ============================= */

    @Override
    public Response post(String url,
                         String body,
                         String contentType,
                         Map<String, String> headers,
                         Map<String, String> cookies) throws IOException {

        MediaType mediaType = contentType != null
                ? MediaType.parse(contentType)
                : MediaType.parse("text/plain; charset=utf-8");

        RequestBody requestBody =
                body != null ? RequestBody.create(body, mediaType) : RequestBody.create(new byte[0]);

        Request request = buildRequest("POST", url, requestBody, contentType, headers, cookies);
        return execute(request, "POST");
    }

    /* =============================
     * DOWNLOAD
     * ============================= */

    @Override
    public Response downloadFile(String url,
                                 Map<String, String> headers,
                                 Map<String, String> cookies,
                                 String forceContentType) throws IOException {

        Request request = buildRequest("GET", url, null, null, headers, cookies);

        try (okhttp3.Response response = client.newCall(request).execute()) {

            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " " + response.message());
            }

            String fileName = extractFileName(url);
            File target = Files.createTempFile("download-", "-" + fileName).toFile();

            Files.write(target.toPath(), response.body().bytes());

            return new Response(
                    response.code(),
                    response.message(),
                    "DOWNLOAD",
                    url,
                    response.protocol().toString(),
                    target.getAbsolutePath(),
                    toSingleValueMap(response.headers()),
                    extractCookies(response)
            );
        }
    }

    /* =============================
     * Internals
     * ============================= */

    private Response execute(Request request, String method) throws IOException {

        try (okhttp3.Response response = client.newCall(request).execute()) {

            String body = response.body() != null ? response.body().string() : null;

            return new Response(
                    response.code(),
                    response.message(),
                    method,
                    request.url().toString(),
                    response.protocol().toString(),
                    body,
                    toSingleValueMap(response.headers()),
                    extractCookies(response)
            );
        }
    }

    private Request buildRequest(String method,
                                 String url,
                                 RequestBody body,
                                 String contentType,
                                 Map<String, String> headers,
                                 Map<String, String> cookies) {

        Request.Builder builder = new Request.Builder().url(url);

        if (headers != null) {
            headers.forEach((name, value) -> {
                // Remove accept-encoding header so okHttp can manage compression automatically
                if (!"accept-encoding".equalsIgnoreCase(name)) {
                    builder.addHeader(name, value);
                }
            });
        }

        if (cookies != null && !cookies.isEmpty()) {
            builder.addHeader("Cookie", formatCookies(cookies));
        }

        if ("POST".equals(method)) {
            builder.post(body);
        }
        else {
            builder.get();
        }

        return builder.build();
    }

    private String formatCookies(Map<String, String> cookies) {
        StringBuilder sb = new StringBuilder();
        cookies.forEach((k, v) -> sb.append(k).append('=').append(v).append("; "));
        return sb.toString();
    }

    private Map<String, String> extractCookies(okhttp3.Response response) {
        Map<String, String> cookies = new HashMap<>();
        for (String header : response.headers("Set-Cookie")) {
            String[] parts = header.split(";", 2);
            String[] kv = parts[0].split("=", 2);
            if (kv.length == 2) {
                cookies.put(kv[0], kv[1]);
            }
        }
        return cookies;
    }

    private Map<String, String> toSingleValueMap(Headers headers) {
        Map<String, String> map = new HashMap<>();
        for (String name : headers.names()) {
            map.put(name, headers.get(name));
        }
        return map;
    }

    private String extractFileName(String url) {
        try {
            return new File(new java.net.URI(url).getPath()).getName();
        }
        catch (Exception e) {
            return "download.bin";
        }
    }
}
