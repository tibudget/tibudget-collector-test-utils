package com.tibudget.test.simulator;

import com.google.gson.*;
import com.tibudget.test.OkHttpInternetProvider;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class HarScenarioTest {
    @TempDir Path temp;

    @Test
    void derivesAnOfflineErrorScenarioWithoutChangingTheOriginal() throws Exception {
        Path source = record();
        Path original = HarScenario.recordings(source).getFirst();
        String content = Files.readString(original);
        Path copy = temp.resolve("expired");
        HarScenario.copy(source, copy, JsonParser.parseString("""
                {"responses":{"1":{"status":401,"statusText":"Unauthorized","body":"Session expired","mimeType":"text/plain","headers":[]}}}
                """).getAsJsonObject());
        var response = new OkHttpInternetProvider().replayDir(copy.toFile()).get("https://example.test/account?email=synthetic@example.test", Map.of());
        assertEquals(401, response.code);
        assertEquals("Session expired", response.body);
        assertEquals(content, Files.readString(original));
        assertThrows(java.io.IOException.class, () -> HarScenario.copy(source, copy, new JsonObject()));
    }

    @Test
    void replacesUrlsHeadersAndEncodedTextAndKeepsReplayWorking() throws Exception {
        Path source = record();
        Path file = HarScenario.recordings(source).getFirst();
        JsonObject document = CollectorCli.readObject(file);
        JsonObject content = document.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonObject("response").getAsJsonObject("content");
        content.addProperty("encoding", "base64");
        content.addProperty("text", Base64.getEncoder().encodeToString("synthetic@example.test".getBytes(StandardCharsets.UTF_8)));
        Files.writeString(file, document.toString());
        JsonObject plan = JsonParser.parseString("""
                {"replacements":{"synthetic@example.test":"fixture@example.test","synthetic-session":"fixture-session"}}
                """).getAsJsonObject();
        Path copy = temp.resolve("review-copy");
        HarScenario.copy(source, copy, plan);
        var response = new OkHttpInternetProvider().replayDir(copy.toFile()).get("https://example.test/account?email=fixture@example.test", Map.of());
        assertEquals("fixture@example.test", response.body);
        assertEquals("fixture-session", response.headers.get("Set-Cookie"));
        assertFalse(Files.readString(HarScenario.recordings(copy).getFirst()).contains("synthetic"));
        var reviews = HarScenario.audit(copy);
        assertFalse(reviews.isEmpty());
        assertFalse(new Gson().toJson(reviews).contains("fixture@example.test"));
        assertFalse(new Gson().toJson(reviews).contains("fixture-session"));
    }

    @Test
    void rejectsGapsDuplicatesAndInvalidHarWithoutPublishingAPartialCopy() throws Exception {
        Path source = record();
        Path first = HarScenario.recordings(source).getFirst();
        Path duplicate = source.resolve(first.getFileName().toString().replace("_get_", "_post_"));
        Files.copy(first, duplicate);
        assertThrows(java.io.IOException.class, () -> HarScenario.recordings(source));
        Files.delete(duplicate);
        Path second = source.resolve(first.getFileName().toString().replace("0001", "0002"));
        Files.move(first, second);
        assertThrows(java.io.IOException.class, () -> HarScenario.recordings(source));
        Files.move(second, first);
        Files.writeString(first, "{\"log\":{\"version\":\"1.2\",\"entries\":[]}}");
        Path destination = temp.resolve("invalid-copy");
        assertThrows(java.io.IOException.class, () -> HarScenario.copy(source, destination, new JsonObject()));
        assertFalse(Files.exists(destination));
    }

    @Test
    void flagsBinaryContentForManualReview() throws Exception {
        Path source = record();
        Path file = HarScenario.recordings(source).getFirst();
        JsonObject document = CollectorCli.readObject(file);
        JsonObject content = document.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonObject("response").getAsJsonObject("content");
        content.addProperty("encoding", "base64");
        content.addProperty("mimeType", "application/pdf");
        content.addProperty("text", Base64.getEncoder().encodeToString(new byte[]{0, 1, 2}));
        Files.writeString(file, document.toString());
        assertTrue(HarScenario.audit(source).stream().anyMatch(review -> review.code().equals("BINARY_CONTENT_REVIEW")));
    }

    @Test
    void preservesEncodedPayloadsWithAnUnknownCharacterEncoding() throws Exception {
        Path source = record();
        Path file = HarScenario.recordings(source).getFirst();
        JsonObject document = CollectorCli.readObject(file);
        JsonObject content = document.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonObject("response").getAsJsonObject("content");
        byte[] original = {(byte) 0xe9, (byte) 0xff};
        content.addProperty("encoding", "base64");
        content.addProperty("mimeType", "text/plain; charset=iso-8859-1");
        content.addProperty("text", Base64.getEncoder().encodeToString(original));
        Files.writeString(file, document.toString());
        Path destination = temp.resolve("unknown-encoding");
        HarScenario.copy(source, destination, new JsonObject());
        JsonObject copied = HarScenario.entry(HarScenario.recordings(destination).getFirst()).getAsJsonObject("response").getAsJsonObject("content");
        assertArrayEquals(original, Base64.getDecoder().decode(copied.get("text").getAsString()));
        assertTrue(HarScenario.audit(destination).stream().anyMatch(review -> review.code().equals("BINARY_CONTENT_REVIEW")));
    }

    private Path record() throws Exception {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> new okhttp3.Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "text/plain").header("Set-Cookie", "synthetic-session").body(ResponseBody.create("synthetic@example.test", MediaType.parse("text/plain"))).build()).build();
        OkHttpInternetProvider recorder = new OkHttpInternetProvider(client).harDir(temp.resolve("source").toFile());
        recorder.get("https://example.test/account?email=synthetic@example.test", Map.of());
        return recorder.getHarDir().toPath();
    }
}
