package com.tibudget.test;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonIOException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import com.tibudget.api.CollectorPlugin;
import com.tibudget.dto.AccountDto;
import com.tibudget.dto.RecurringPaymentDto;
import com.tibudget.dto.TransactionDto;
import org.junit.jupiter.api.Assertions;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Creates deterministic JSON snapshots of a collector's results, without running collect().
 * All DTO fields, null values and list ordering are preserved. Object keys are sorted and
 * dates use ISO-8601 UTC with millisecond precision, independently of the default locale/time zone.
 * Attached files are represented by their content (base64), rather than temporary paths.
 * UUIDs are stabilized using {@link AccountDto#getId()} and {@link RecurringPaymentDto#getId()}, including their references.
 * Counterparty UUIDs are preserved. Callers must supply stable timestamps for repeatable comparisons.
 */
public final class CollectorResultJson {
    private static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .registerTypeHierarchyAdapter(File.class, (JsonSerializer<File>) (file, type, context) -> {
                try {
                    JsonObject content = new JsonObject();
                    content.addProperty("encoding", "base64");
                    content.addProperty("content", java.util.Base64.getEncoder()
                            .encodeToString(Files.readAllBytes(file.toPath())));
                    return content;
                } catch (IOException e) {
                    throw new JsonIOException("Cannot read collected file: " + file, e);
                }
            })
            .registerTypeHierarchyAdapter(Date.class, (JsonSerializer<Date>) (date, type, context) ->
                    new JsonPrimitive(java.time.Instant.ofEpochMilli(date.getTime()).toString()))
            .create();

    private CollectorResultJson() {
    }

    /**
     * Serializes current results with stable UUIDs, without modifying the collector.
     */
    public static String toJson(CollectorPlugin collector) {
        return toJson(collector, true);
    }

    /**
     * Uses current results from {@link CollectorPlugin}; pass false to keep the original UUIDs.
     */
    public static String toJson(CollectorPlugin collector, boolean stabilizeUuids) {
        Objects.requireNonNull(collector, "collector");
        return toJson(collector.getAccounts(), collector.getTransactions(), collector.getRecurringPayments(), stabilizeUuids);
    }

    /**
     * Serializes gathered results, including nested DTOs, with stable UUIDs.
     */
    public static String toJson(Collection<? extends AccountDto> accounts,
                                Collection<? extends TransactionDto> transactions,
                                Collection<? extends RecurringPaymentDto> recurringPayments) {
        return toJson(accounts, transactions, recurringPayments, true);
    }

    /**
     * With stabilization enabled, IDs must be nonblank. UUIDs are name-based UUIDs of
     * "account:" + id or "recurringPayment:" + id (UTF-8). Exact UUID string values and
     * object keys are replaced throughout the JSON, except counterparty UUID values and stable entity IDs.
     */
    public static String toJson(Collection<? extends AccountDto> accounts,
                                Collection<? extends TransactionDto> transactions,
                                Collection<? extends RecurringPaymentDto> recurringPayments,
                                boolean stabilizeUuids) {
        JsonObject result = new JsonObject();
        result.add("accounts", GSON.toJsonTree(accounts));
        result.add("transactions", GSON.toJsonTree(transactions));
        result.add("recurringPayments", GSON.toJsonTree(recurringPayments));
        if (stabilizeUuids) {
            Map<String, String> replacements = new HashMap<>();
            collectUuidReplacements(result.get("accounts"), "account", replacements);
            collectUuidReplacements(result.get("recurringPayments"), "recurringPayment", replacements);
            JsonObject stabilized = replaceUuids(result, replacements).getAsJsonObject();
            assignStableUuids(result.get("accounts"), stabilized.get("accounts"), "account");
            assignStableUuids(result.get("recurringPayments"), stabilized.get("recurringPayments"), "recurringPayment");
            result = stabilized;
        }
        return render(result);
    }

    private static String stableUuid(JsonObject entity, String type) {
        JsonElement id = entity.get("id");
        if (id == null || id.isJsonNull() || id.getAsString().isBlank()) {
            throw new IllegalArgumentException("Cannot stabilize " + type + " UUID without a nonblank ID");
        }
        return UUID.nameUUIDFromBytes((type + ":" + id.getAsString()).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void collectUuidReplacements(JsonElement entities, String type, Map<String, String> replacements) {
        if (entities.isJsonNull()) return;
        for (JsonElement element : entities.getAsJsonArray()) {
            if (element.isJsonNull()) continue;
            JsonObject entity = element.getAsJsonObject();
            String stable = stableUuid(entity, type);
            JsonElement uuid = entity.get("uuid");
            if (uuid != null && !uuid.isJsonNull()) {
                String previous = replacements.putIfAbsent(uuid.getAsString(), stable);
                if (previous != null && !previous.equals(stable)) {
                    throw new IllegalArgumentException("Conflicting IDs for UUID " + uuid.getAsString());
                }
            }
        }
    }

    private static void assignStableUuids(JsonElement source, JsonElement target, String type) {
        if (source.isJsonNull()) return;
        for (int i = 0; i < source.getAsJsonArray().size(); i++) {
            JsonElement entity = source.getAsJsonArray().get(i);
            if (!entity.isJsonNull()) {
                target.getAsJsonArray().get(i).getAsJsonObject()
                        .addProperty("uuid", stableUuid(entity.getAsJsonObject(), type));
            }
        }
    }

    private static JsonElement replaceUuids(JsonElement element, Map<String, String> replacements) {
        if (element.isJsonObject()) {
            JsonObject result = new JsonObject();
            element.getAsJsonObject().entrySet().forEach(entry -> {
                String key = entry.getKey();
                String replacedKey = replacements.getOrDefault(key, key);
                if (result.has(replacedKey)) {
                    throw new IllegalArgumentException("UUID replacement produces duplicate JSON key: " + replacedKey);
                }
                result.add(replacedKey, ("counterpartyUuid".equalsIgnoreCase(key) || "id".equals(key))
                        ? entry.getValue() : replaceUuids(entry.getValue(), replacements));
            });
            return result;
        }
        if (element.isJsonArray()) {
            JsonArray result = new JsonArray();
            element.getAsJsonArray().forEach(value -> result.add(replaceUuids(value, replacements)));
            return result;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String value = element.getAsString();
            return new JsonPrimitive(replacements.getOrDefault(value, value));
        }
        return element;
    }

    /**
     * Writes a UTF-8 reference file. Only call this when recording/updating a scenario.
     */
    public static void save(File file, CollectorPlugin collector) throws IOException {
        save(file, collector, true);
    }

    /**
     * Writes a reference using {@link #toJson(CollectorPlugin, boolean)}. The parent directory must exist.
     */
    public static void save(File file, CollectorPlugin collector, boolean stabilizeUuids) throws IOException {
        Files.writeString(file.toPath(), toJson(collector, stabilizeUuids), StandardCharsets.UTF_8);
    }

    /**
     * Compares current results with the reference, ignoring JSON object key order and whitespace.
     * Does not create or update the reference. Missing/unreadable files fail with IOException;
     * malformed JSON fails with JsonParseException, and differing results with AssertionFailedError.
     */
    public static void assertMatches(File expectedFile, CollectorPlugin collector) throws IOException {
        assertMatches(expectedFile, collector, true);
    }

    /**
     * Compares with the same UUID option used to save the reference; never updates the file.
     */
    public static void assertMatches(File expectedFile, CollectorPlugin collector, boolean stabilizeUuids) throws IOException {
        String expected = render(JsonParser.parseString(Files.readString(expectedFile.toPath(), StandardCharsets.UTF_8)));
        Assertions.assertEquals(expected, toJson(collector, stabilizeUuids),
                "Collector results differ from scenario reference: " + expectedFile.getAbsolutePath());
    }

    private static String render(JsonElement element) {
        return GSON.toJson(sortKeys(element)) + "\n";
    }

    private static JsonElement sortKeys(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject sorted = new JsonObject();
            new TreeMap<>(element.getAsJsonObject().asMap())
                    .forEach((key, value) -> sorted.add(key, sortKeys(value)));
            return sorted;
        }
        if (element.isJsonArray()) {
            JsonArray sorted = new JsonArray();
            element.getAsJsonArray().forEach(value -> sorted.add(sortKeys(value)));
            return sorted;
        }
        return element;
    }
}
