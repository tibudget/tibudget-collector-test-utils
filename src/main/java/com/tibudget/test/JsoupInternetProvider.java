package com.tibudget.test;

import com.tibudget.api.InternetProvider;
import org.jsoup.Connection;
import org.jsoup.Jsoup;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class JsoupInternetProvider implements InternetProvider {

    private boolean dumpRequests = false;

    private int timeoutMs = 30_000;

    public JsoupInternetProvider() {
    }

    public JsoupInternetProvider(boolean dumpRequests) {
        this.dumpRequests = dumpRequests;
    }

    public JsoupInternetProvider(boolean dumpRequests, int timeoutMs) {
        this.timeoutMs = timeoutMs;
        this.dumpRequests = dumpRequests;
    }

    @Override
    public Response get(String url,
                        Map<String, String> headers,
                        Map<String, String> cookies) throws IOException {

        Connection connection = connect(url, headers, cookies);
        dumpConnection(connection);
        Connection.Response response = connection.execute();
        return toResponse("GET", response);
    }

    @Override
    public Response post(String url,
                         String body,
                         String contentType,
                         Map<String, String> headers,
                         Map<String, String> cookies) throws IOException {

        Connection connection = connect(url, headers, cookies)
                .method(Connection.Method.POST);

        if (contentType != null) {
            connection.header("Content-Type", contentType);
        }

        if (body != null) {
            connection.requestBody(body);
        }

        dumpConnection(connection);

        Connection.Response response = connection.execute();
        return toResponse("POST", response);
    }

    private void dumpFormEncodedBody(Connection conn) {
        if (conn.request().requestBody() != null) {
            System.out.println(conn.request().requestBody());
        }
        else {
            StringBuilder body = new StringBuilder();

            for (Connection.KeyVal kv : conn.request().data()) {
                if (body.length() > 0) body.append("&");
                body.append(URLEncoder.encode(kv.key(), StandardCharsets.UTF_8));
                body.append("=");
                body.append(URLEncoder.encode(kv.value(), StandardCharsets.UTF_8));
            }

            System.out.println(body);
        }
    }

    private void dumpConnection(Connection conn) {
        if (!dumpRequests) return;

        System.out.println("=== JSOUP REQUEST DUMP ===");
        System.out.println("URL      : " + conn.request().url());
        System.out.println("Method   : " + conn.request().method());

        System.out.println("-- Headers --");
        conn.request().headers().forEach((k, v) ->
                System.out.println(k + ": " + v)
        );

        System.out.println("-- Cookies --");
        conn.request().cookies().forEach((k, v) ->
                System.out.println(k + "=" + v)
        );

        System.out.println("-- Body --");
        dumpFormEncodedBody(conn);
        System.out.println("=== END REQUEST DUMP ===");
    }

    @Override
    public Response downloadFile(String url,
                                 Map<String, String> headers,
                                 Map<String, String> cookies,
                                 String forceContentType) throws IOException {

        Connection connection = connect(url, headers, cookies).ignoreContentType(true);
        dumpConnection(connection);
        Connection.Response response = connection.execute();

        File tmp = File.createTempFile("tibu-", ".bin");

        try (InputStream in = response.bodyStream();
             FileOutputStream out = new FileOutputStream(tmp)) {

            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
        }

        return new Response(
                response.statusCode(),
                response.statusMessage(),
                "DOWNLOAD",
                response.url().toString(),
                response.url().getProtocol(),
                tmp.getAbsolutePath(),
                response.headers(),
                response.cookies()
        );
    }

    /* =============================
     * Internals
     * ============================= */

    private Connection connect(String url,
                               Map<String, String> headers,
                               Map<String, String> cookies) {

        Connection connection = Jsoup.connect(url)
                .timeout(timeoutMs)
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .ignoreContentType(true);

        if (headers != null) {
            headers.forEach(connection::header);
        }

        if (cookies != null) {
            connection.cookies(cookies);
        }

        return connection;
    }

    private Response toResponse(String method, Connection.Response response) throws IOException {

        String body = response.body();

        return new Response(
                response.statusCode(),
                response.statusMessage(),
                method,
                response.url().toString(),
                response.url().getProtocol(),
                body,
                new HashMap<>(response.headers()),
                new HashMap<>(response.cookies())
        );
    }
}
