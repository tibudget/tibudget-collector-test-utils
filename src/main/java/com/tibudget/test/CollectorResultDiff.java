package com.tibudget.test;

import com.google.gson.*;

import java.util.*;
import java.nio.charset.StandardCharsets;

/**
 * Compare result snapshots by entity ID so list reordering does not hide field changes.
 * Difference reports omit values and IDs. Input snapshots themselves remain private test artifacts.
 */
public final class CollectorResultDiff {
    private CollectorResultDiff() {
    }

    /**
     * Paths refer to the expected array for removed/changed entries and to the actual array for added entries.
     */
    public record Difference(String kind, String path) {
    }

    /**
     * Compare all DTO fields, including UUIDs. Use snapshots made with the same UUID stabilization option.
     * Ignored fields are explicit JSON pointers, for example /accounts/0/lastUpdate.
     */
    public static List<Difference> compare(JsonObject expected, JsonObject actual, Set<String> ignoredPaths) {
        List<Difference> differences = new ArrayList<>();
        for (String group : List.of("accounts", "transactions", "recurringPayments")) {
            JsonElement before = expected.get(group);
            JsonElement after = actual.get(group);
            String path = "/" + group;
            if (before == null || after == null || !before.isJsonArray() || !after.isJsonArray()) {
                compareValue(before, after, path, ignoredPaths, differences);
                continue;
            }
            Map<String, Integer> old = index(before.getAsJsonArray(), group, path, differences);
            Map<String, Integer> current = index(after.getAsJsonArray(), group, path, differences);
            for (var entry : old.entrySet()) {
                String itemPath = path + "/" + entry.getValue();
                Integer matched = current.get(entry.getKey());
                if (matched == null) differences.add(new Difference("REMOVED", itemPath));
                else compareValue(before.getAsJsonArray().get(entry.getValue()), after.getAsJsonArray().get(matched), itemPath, ignoredPaths, differences);
            }
            for (var entry : current.entrySet()) {
                if (!old.containsKey(entry.getKey())) differences.add(new Difference("ADDED", path + "/" + entry.getValue()));
            }
        }
        return List.copyOf(differences);
    }

    /**
     * Stabilize UUIDs for comparisons, including IDs reused in different accounts or counterparties.
     * Unique IDs use the same names as {@link CollectorResultJson}; scoped IDs include their owner only when needed.
     */
    public static JsonObject stabilizeUuids(JsonObject source) {
        Map<String, String> replacements = new HashMap<>();
        Map<String, String> stableAccounts = new HashMap<>();
        Map<String, String> assigned = new HashMap<>();
        for (String group : List.of("accounts", "recurringPayments")) {
            JsonArray entities = source.getAsJsonArray(group);
            Map<String, Integer> counts = new HashMap<>();
            for (JsonElement element : entities) {
                String id = id(element.getAsJsonObject());
                counts.merge(id, 1, Integer::sum);
            }
            for (int i = 0; i < entities.size(); i++) {
                JsonObject entity = entities.get(i).getAsJsonObject();
                String id = id(entity);
                String owner = group.equals("accounts") ? String.valueOf(entity.get("counterpartyUuid")) : stableAccounts.getOrDefault(String.valueOf(entity.get("accountUuid")), "");
                String type = group.equals("accounts") ? "account" : "recurringPayment";
                String name = type + ":" + (counts.get(id) > 1 ? owner + ":" : "") + id;
                String stable = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
                assigned.put(group + "/" + i, stable);
                JsonElement uuid = entity.get("uuid");
                if (uuid != null && !uuid.isJsonNull()) {
                    String previous = replacements.putIfAbsent(uuid.getAsString(), stable);
                    if (previous != null && !previous.equals(stable)) throw new IllegalArgumentException("Conflicting entity UUIDs");
                    if (group.equals("accounts")) stableAccounts.put(String.valueOf(uuid), stable);
                }
            }
        }
        JsonObject result = replaceUuids(source, replacements).getAsJsonObject();
        for (String group : List.of("accounts", "recurringPayments")) {
            JsonArray entities = result.getAsJsonArray(group);
            for (int i = 0; i < entities.size(); i++) entities.get(i).getAsJsonObject().addProperty("uuid", assigned.get(group + "/" + i));
        }
        return result;
    }

    private static String id(JsonObject entity) {
        JsonElement id = entity.get("id");
        if (id == null || id.isJsonNull() || !id.isJsonPrimitive() || id.getAsString().isBlank()) throw new IllegalArgumentException("Cannot stabilize an entity without a nonblank ID");
        return id.getAsString();
    }

    private static JsonElement replaceUuids(JsonElement source, Map<String, String> replacements) {
        if (source.isJsonObject()) {
            JsonObject result = new JsonObject();
            for (var field : source.getAsJsonObject().entrySet()) {
                String key = replacements.getOrDefault(field.getKey(), field.getKey());
                if (result.has(key)) throw new IllegalArgumentException("UUID replacement produces duplicate keys");
                result.add(key, field.getKey().equals("id") || field.getKey().equals("counterpartyUuid") ? field.getValue().deepCopy() : replaceUuids(field.getValue(), replacements));
            }
            return result;
        }
        if (source.isJsonArray()) {
            JsonArray result = new JsonArray();
            source.getAsJsonArray().forEach(value -> result.add(replaceUuids(value, replacements)));
            return result;
        }
        if (source.isJsonPrimitive() && source.getAsJsonPrimitive().isString()) return new JsonPrimitive(replacements.getOrDefault(source.getAsString(), source.getAsString()));
        return source.deepCopy();
    }

    private static Map<String, Integer> index(JsonArray values, String group, String path, List<Difference> differences) {
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < values.size(); i++) {
            JsonElement value = values.get(i);
            if (!value.isJsonObject() || !value.getAsJsonObject().has("id") || value.getAsJsonObject().get("id").isJsonNull() || !value.getAsJsonObject().get("id").isJsonPrimitive() || value.getAsJsonObject().get("id").getAsString().isBlank()) {
                differences.add(new Difference("INVALID_ID", path + "/" + i));
                continue;
            }
            JsonObject object = value.getAsJsonObject();
            // Transactions and recurring IDs are scoped to accounts; account IDs are scoped to counterparties.
            String owner = group.equals("accounts") ? "counterpartyUuid" : "accountUuid";
            String key = String.valueOf(object.get(owner)) + "\u0000" + object.get("id");
            if (index.putIfAbsent(key, i) != null) differences.add(new Difference("DUPLICATE_ID", path + "/" + i));
        }
        return index;
    }

    private static void compareValue(JsonElement expected, JsonElement actual, String path, Set<String> ignored, List<Difference> differences) {
        if (ignored.contains(path) || Objects.equals(expected, actual)) return;
        if (path.endsWith("/metadatas")) {
            differences.add(new Difference("CHANGED", path));
            return;
        }
        if (expected != null && actual != null && expected.isJsonObject() && actual.isJsonObject()) {
            Set<String> keys = new TreeSet<>(expected.getAsJsonObject().keySet());
            keys.addAll(actual.getAsJsonObject().keySet());
            for (String key : keys) compareValue(expected.getAsJsonObject().get(key), actual.getAsJsonObject().get(key), path + "/" + key.replace("~", "~0").replace("/", "~1"), ignored, differences);
        } else if (expected != null && actual != null && expected.isJsonArray() && actual.isJsonArray() && expected.getAsJsonArray().size() == actual.getAsJsonArray().size()) {
            for (int i = 0; i < expected.getAsJsonArray().size(); i++) compareValue(expected.getAsJsonArray().get(i), actual.getAsJsonArray().get(i), path + "/" + i, ignored, differences);
        } else differences.add(new Difference(expected == null ? "ADDED" : actual == null ? "REMOVED" : "CHANGED", path));
    }
}
