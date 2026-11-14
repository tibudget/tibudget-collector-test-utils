package com.tibudget.test.simulator;

import okhttp3.*;
import okio.Buffer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Observe actual OkHttp exchanges, including redirects, without enabling raw HAR recordings.
 * The client is injected into the supplied OkHttpInternetProvider; no collector request bypasses that provider.
 */
final class NetworkInspector implements Interceptor {
    private static final long BODY_LIMIT = 262_144;
    private final AtomicLong sequence = new AtomicLong();
    private final DiagnosticRedactor redactor;
    private final Consumer<Exchange> listener;

    NetworkInspector(DiagnosticRedactor redactor, Consumer<Exchange> listener) {
        this.redactor = redactor;
        this.listener = listener;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        long id = sequence.incrementAndGet();
        long start = System.nanoTime();
        Instant date = Instant.now();
        String url = safeUrl(request.url());
        String requestHeaders = headers(request.headers());
        String requestBody = "(empty)";
        if (request.body() != null) {
            long length = request.body().contentLength();
            if (length >= 0 && length <= BODY_LIMIT && !request.body().isDuplex() && !request.body().isOneShot()) {
                Buffer buffer = new Buffer();
                request.body().writeTo(buffer);
                requestBody = redactor.body(buffer.readString(StandardCharsets.UTF_8));
            } else {
                requestBody = "[request body not previewed; size " + length + " bytes]";
            }
        }
        Exchange pending = new Exchange(id, date, request.method(), url, null, 0, 0, requestHeaders, requestBody, "", "Waiting for response…", "");
        listener.accept(pending);
        try {
            Response response = chain.proceed(request);
            String responseHeaders = headers(response.headers());
            String type = response.header("Content-Type", "");
            String body = "[binary response; " + type + "]";
            long size = response.body() == null ? 0 : response.body().contentLength();
            if (type.contains("json") || type.contains("text") || type.contains("xml") || type.contains("javascript")) {
                // Peeking does not consume the response used by the collector.
                try {
                    byte[] preview = response.peekBody(BODY_LIMIT + 1).bytes();
                    String encoding = response.header("Content-Encoding", "");
                    if (encoding.equalsIgnoreCase("gzip") || encoding.equalsIgnoreCase("deflate")) {
                        // Network interceptors see compressed bytes before OkHttp's transparent gzip handling.
                        try (InputStream decoded = encoding.equalsIgnoreCase("gzip") ? new GZIPInputStream(new ByteArrayInputStream(preview))
                                : new InflaterInputStream(new ByteArrayInputStream(preview))) {
                            preview = decoded.readNBytes((int) BODY_LIMIT + 1);
                        }
                    }
                    body = redactor.body(new String(preview, 0, (int) Math.min(preview.length, BODY_LIMIT), StandardCharsets.UTF_8));
                    if (preview.length > BODY_LIMIT) body += "\n[preview limited to 256 KiB]";
                } catch (IOException failure) {
                    // A failed diagnostic preview must not turn a valid request into a collect failure.
                    body = "[text preview unavailable or compressed body truncated]";
                }
            }
            listener.accept(new Exchange(id, date, request.method(), url, response.code(), elapsed(start), size, requestHeaders, requestBody, responseHeaders, body, ""));
            return response;
        } catch (IOException exception) {
            listener.accept(new Exchange(id, date, request.method(), url, -1, elapsed(start), 0, requestHeaders, requestBody, "", "", redactor.text(exception.toString())));
            throw exception;
        }
    }

    private String safeUrl(HttpUrl url) {
        HttpUrl.Builder safe = url.newBuilder().username("").password("");
        for (String key : url.queryParameterNames()) {
            if (redactor.sensitive(key)) {
                url.queryParameterValues(key).forEach(redactor::remember);
                safe.setQueryParameter(key, "[hidden]");
            }
        }
        return redactor.text(safe.build().toString());
    }

    private String headers(Headers headers) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < headers.size(); index++) {
            text.append(headers.name(index)).append(": ").append(redactor.header(headers.name(index), headers.value(index))).append('\n');
        }
        return text.toString();
    }

    private long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    record Exchange(long id, Instant date, String method, String url, Integer status, long milliseconds, long bytes,
                    String requestHeaders, String requestBody, String responseHeaders, String responseBody, String error) {
        String statusText() {
            return status == null ? "Pending" : status == -1 ? "Failed" : status.toString();
        }
    }
}
