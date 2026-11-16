package com.tibudget.test.simulator;

import okhttp3.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class NetworkInspectorTest {
    @Test
    void capturesHtmlBodyAfterALargeHeadWithoutConsumingCollectorResponse() throws Exception {
        String html = "<html><head><title>Login</title><style>/*" + "x".repeat(300_000) + "*/</style></head><body><h1>Sign in</h1></body></html>";
        List<NetworkInspector.Exchange> exchanges = new ArrayList<>();
        Response response = inspect(html.getBytes(StandardCharsets.UTF_8), "text/html", null, exchanges);
        String preview = exchanges.getLast().responseBody();
        assertTrue(preview.contains("<h1>Sign in</h1>"));
        assertFalse(preview.contains(NetworkInspector.TRUNCATION_NOTICE));
        assertEquals(html, response.body().string());
    }

    @Test
    void appliesLimitToDecodedGzipContent() throws Exception {
        String html = "<html><head><style>/*" + "x".repeat(300_000) + "*/</style></head><body>Visible form</body></html>";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) { gzip.write(html.getBytes(StandardCharsets.UTF_8)); }
        List<NetworkInspector.Exchange> exchanges = new ArrayList<>();
        Response response = inspect(bytes.toByteArray(), "text/html", "gzip", exchanges);
        assertTrue(exchanges.getLast().responseBody().contains("Visible form"));
        assertFalse(exchanges.getLast().responseBody().contains(NetworkInspector.TRUNCATION_NOTICE));
        assertArrayEquals(bytes.toByteArray(), response.body().bytes());
    }

    @Test
    void warnsOnlyWhenTheDecodedResponseActuallyExceedsTheLimit() throws Exception {
        for (int extra : List.of(0, 1)) {
            List<NetworkInspector.Exchange> exchanges = new ArrayList<>();
            String body = "x".repeat(NetworkInspector.RESPONSE_BODY_LIMIT + extra);
            try (Response response = inspect(body.getBytes(StandardCharsets.UTF_8), "text/plain", null, exchanges)) {
                String preview = exchanges.getLast().responseBody();
                assertEquals(extra > 0, preview.endsWith("\n" + NetworkInspector.TRUNCATION_NOTICE));
                assertEquals(body, response.body().string());
            }
        }
    }

    private Response inspect(byte[] body, String type, String encoding, List<NetworkInspector.Exchange> exchanges) throws Exception {
        Request request = new Request.Builder().url("https://example.test/page").build();
        Response.Builder builder = new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", type).body(ResponseBody.create(body, MediaType.get(type)));
        if (encoding != null) builder.header("Content-Encoding", encoding);
        Response response = builder.build();
        Interceptor.Chain chain = (Interceptor.Chain) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Interceptor.Chain.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "request" -> request;
                    case "proceed" -> response;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return new NetworkInspector(new DiagnosticRedactor(), exchanges::add).intercept(chain);
    }
}
