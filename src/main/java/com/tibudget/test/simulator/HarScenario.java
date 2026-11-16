package com.tibudget.test.simulator;

import com.google.gson.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Inspect and derive offline HAR scenarios without changing the original recordings.
 * Replacement copies are candidates for review, not a guarantee that all personal data was removed.
 */
public final class HarScenario {
    private static final Pattern NAME = Pattern.compile("^(\\d+)_(get|post|dl_post|dl)_.+\\.har$");
    private static final Pattern PERSONAL = Pattern.compile("(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}|bearer\\s+\\S+|\\b[A-Z]{2}\\d{2}[A-Z0-9]{11,30}\\b");
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create();

    private HarScenario() {
    }

    /**
     * Reports never include the suspicious value or the source filename.
     */
    public record Review(int request, String path, String code) {
    }

    /**
     * List HAR files in request order and require a complete sequence starting at one.
     */
    public static List<Path> recordings(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) throw new IOException("Scenario directory does not exist");
        Map<Integer, Path> files = new TreeMap<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.toList()) {
                var matcher = NAME.matcher(path.getFileName().toString());
                if (!matcher.matches()) continue;
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) throw new IOException("Scenario recording must be a regular file");
                int index = Integer.parseInt(matcher.group(1));
                if (files.putIfAbsent(index, path) != null) throw new IOException("Duplicate HAR request number " + index);
            }
        }
        if (files.isEmpty()) throw new IOException("Scenario contains no numbered HAR recordings");
        int expected = 1;
        for (int number : files.keySet()) {
            if (number != expected++) throw new IOException("Missing HAR request number " + (expected - 1));
        }
        return List.copyOf(files.values());
    }

    /**
     * Read one HAR 1.2 entry, rejecting ambiguous recordings.
     */
    public static JsonObject entry(Path file) throws IOException {
        try {
            JsonObject document = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            JsonObject log = document.getAsJsonObject("log");
            if (!"1.2".equals(log.get("version").getAsString()) || log.getAsJsonArray("entries").size() != 1) throw new IOException("Expected one HAR 1.2 entry");
            JsonObject entry = log.getAsJsonArray("entries").get(0).getAsJsonObject();
            if (!entry.has("request") || !entry.has("response")) throw new IOException("HAR request or response is missing");
            return entry;
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid HAR structure", invalid);
        }
    }

    /**
     * Flag sensitive field names, common personal-data patterns and binary payloads needing manual review.
     * Names, addresses, document contents and application-specific secrets also need manual review.
     */
    public static List<Review> audit(Path directory) throws IOException {
        List<Review> reviews = new ArrayList<>();
        List<Path> files = recordings(directory);
        for (int i = 0; i < files.size(); i++) {
            JsonObject entry = entry(files.get(i));
            auditValue(entry, "", i + 1, reviews, new DiagnosticRedactor());
            if (PERSONAL.matcher(files.get(i).getFileName().toString()).find()) reviews.add(new Review(i + 1, "/filename", "PERSONAL_PATTERN"));
        }
        return List.copyOf(reviews);
    }

    private static void auditValue(JsonElement value, String path, int request, List<Review> reviews, DiagnosticRedactor redactor) {
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (object.has("name") && object.has("value") && object.get("name").isJsonPrimitive() && redactor.sensitive(object.get("name").getAsString())) {
                reviews.add(new Review(request, path + "/value", "SENSITIVE_FIELD"));
            }
            for (var field : object.entrySet()) {
                // Unknown JSON key names can themselves contain personal data; report only the containing object.
                if (redactor.sensitive(field.getKey()) && !field.getValue().isJsonNull()) reviews.add(new Review(request, path, "SENSITIVE_FIELD"));
                String keyPath = Set.of("request", "response", "headers", "cookies", "queryString", "postData", "content", "text", "url", "name", "value").contains(field.getKey()) ? path + "/" + field.getKey() : path + "/field";
                auditValue(field.getValue(), keyPath, request, reviews, redactor);
            }
            if (object.has("encoding") && "base64".equals(object.get("encoding").getAsString()) && object.has("text")) {
                String decoded = decodeText(object);
                if (decoded != null) {
                    if (!redactor.body(decoded).equals(decoded) || PERSONAL.matcher(decoded).find()) reviews.add(new Review(request, path + "/text", "ENCODED_CONTENT_REVIEW"));
                } else reviews.add(new Review(request, path + "/text", "BINARY_CONTENT_REVIEW"));
            }
        } else if (value.isJsonArray()) {
            for (int i = 0; i < value.getAsJsonArray().size(); i++) auditValue(value.getAsJsonArray().get(i), path + "/" + i, request, reviews, redactor);
        } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String text = value.getAsString();
            if (PERSONAL.matcher(text).find() || !redactor.text(text).equals(text)) reviews.add(new Review(request, path, "PERSONAL_PATTERN"));
        }
    }

    /**
     * Create a new HAR-only directory. The plan contains literal replacements and optional per-request response overrides.
     * Response overrides accept status, statusText, body, mimeType, headers and location. Original files are never edited.
     */
    public static void copy(Path source, Path destination, JsonObject plan) throws IOException {
        List<Path> files = recordings(source);
        if (!Set.of("replacements", "responses").containsAll(plan.keySet())) throw new IllegalArgumentException("Unknown scenario plan field");
        Path target = destination.toAbsolutePath().normalize();
        if (Files.exists(target)) throw new IOException("Destination already exists");
        Map<String, String> replacements = new LinkedHashMap<>();
        if (plan.has("replacements")) {
            plan.getAsJsonObject("replacements").entrySet().stream().sorted(Comparator.comparingInt((Map.Entry<String, JsonElement> field) -> field.getKey().length()).reversed()).forEach(field -> {
                if (field.getKey().isEmpty()) throw new IllegalArgumentException("Replacement source must not be empty");
                replacements.put(field.getKey(), field.getValue().getAsString());
            });
        }
        JsonObject responses = plan.has("responses") ? plan.getAsJsonObject("responses") : new JsonObject();
        for (String number : responses.keySet()) {
            if (Integer.parseInt(number) < 1 || Integer.parseInt(number) > files.size()) throw new IOException("Response override request is outside the scenario");
        }
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempDirectory(target.getParent(), ".har-copy-");
        try {
            for (int i = 0; i < files.size(); i++) {
                JsonObject original = JsonParser.parseString(Files.readString(files.get(i))).getAsJsonObject();
                entry(files.get(i));
                JsonObject copy = replace(original, replacements).getAsJsonObject();
                JsonObject entry = copy.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();
                JsonObject response = entry.getAsJsonObject("response");
                if (responses.has(String.valueOf(i + 1))) override(response, responses.getAsJsonObject(String.valueOf(i + 1)));
                JsonObject request = entry.getAsJsonObject("request");
                var matcher = NAME.matcher(files.get(i).getFileName().toString());
                if (!matcher.matches()) throw new IOException("Invalid HAR filename");
                String method = matcher.group(2);
                String url = request.get("url").getAsString();
                String normalized = url.replace("https://", "").replace("http://", "").replaceAll("[^a-zA-Z0-9.\\-_/]", "_").replace("/", "_");
                if (normalized.length() > 160) normalized = normalized.substring(0, 120) + "_" + UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8));
                String name = String.format(Locale.ROOT, "%04d_%s_%s.har", i + 1, method, normalized);
                Files.writeString(temporary.resolve(name), JSON.toJson(copy), StandardOpenOption.CREATE_NEW);
            }
            Files.move(temporary, target);
        } finally {
            if (Files.exists(temporary)) {
                try (var paths = Files.list(temporary)) { for (Path file : paths.toList()) Files.delete(file); }
                Files.delete(temporary);
            }
        }
    }

    private static JsonElement replace(JsonElement value, Map<String, String> replacements) {
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            boolean encoded = object.has("encoding") && "base64".equals(object.get("encoding").getAsString()) && object.has("text");
            String body = encoded ? decodeText(object) : null;
            JsonObject result = new JsonObject();
            for (var field : object.entrySet()) result.add(replaceText(field.getKey(), replacements), encoded && field.getKey().equals("text") ? field.getValue().deepCopy() : replace(field.getValue(), replacements));
            if (body != null) result.addProperty("text", Base64.getEncoder().encodeToString(replaceText(body, replacements).getBytes(StandardCharsets.UTF_8)));
            return result;
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            value.getAsJsonArray().forEach(item -> result.add(replace(item, replacements)));
            return result;
        }
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? new JsonPrimitive(replaceText(value.getAsString(), replacements)) : value.deepCopy();
    }

    private static String replaceText(String value, Map<String, String> replacements) {
        String result = value;
        for (var replacement : replacements.entrySet()) result = result.replace(replacement.getKey(), replacement.getValue());
        return result;
    }

    private static String decodeText(JsonObject content) {
        if (!isText(content)) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(content.get("text").getAsString());
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalidText) {
            // Do not change payload bytes when the character encoding is unknown.
            return null;
        }
    }

    private static boolean isText(JsonObject content) {
        String type = content.has("mimeType") ? content.get("mimeType").getAsString().toLowerCase(Locale.ROOT) : "";
        return type.startsWith("text/") || type.contains("json") || type.contains("xml") || type.contains("javascript") || type.contains("x-www-form-urlencoded");
    }

    private static void override(JsonObject response, JsonObject changes) {
        Set<String> allowed = Set.of("status", "statusText", "body", "mimeType", "headers", "location");
        if (!allowed.containsAll(changes.keySet())) throw new IllegalArgumentException("Unknown response override field");
        for (String field : List.of("status", "statusText")) if (changes.has(field)) response.add(field, changes.get(field));
        JsonObject content = response.getAsJsonObject("content");
        if (changes.has("body")) {
            content.add("text", changes.get("body"));
            content.remove("encoding");
            content.addProperty("size", changes.get("body").getAsString().getBytes(StandardCharsets.UTF_8).length);
        }
        if (changes.has("mimeType")) content.add("mimeType", changes.get("mimeType"));
        if (changes.has("headers")) response.add("headers", changes.get("headers"));
        if (changes.has("location")) response.add("_location", changes.get("location"));
    }
}
