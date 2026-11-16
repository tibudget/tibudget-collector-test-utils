package com.tibudget.test;

import com.google.gson.*;
import com.tibudget.api.CollectorPlugin;

import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

/**
 * Check result structure against the API 0.66 DTO contracts without changing collected data.
 * Findings contain field paths and rules, never field values. Site-specific rules belong in collector tests.
 */
public final class CollectorDiagnostics {
    private static final String DOCS = "https://github.com/tibudget/tibudget-api/blob/0.66/src/main/java/com/tibudget/";

    private CollectorDiagnostics() {
    }

    /**
     * A diagnostic that can be written to a JSON report without copying personal data.
     */
    public record Finding(String severity, String code, String path, String message, String documentation) {
    }

    /**
     * Validate the current result, including links to previously known accounts and recurring payments.
     */
    public static List<Finding> validate(CollectorPlugin collector, JsonObject previous) {
        List<Finding> findings = new ArrayList<>();
        for (int i = 0; i < collector.getAccounts().size(); i++) {
            var account = collector.getAccounts().get(i);
            if (account != null && !Double.isFinite(account.getCurrentBalance())) add(findings, "ERROR", "number.invalid", "/accounts/" + i + "/currentBalance", "Expected a finite number.", DOCS + "dto/AccountDto.java");
        }
        for (int i = 0; i < collector.getTransactions().size(); i++) {
            var transaction = collector.getTransactions().get(i);
            if (transaction != null && !Double.isFinite(transaction.getAmount())) add(findings, "ERROR", "number.invalid", "/transactions/" + i + "/amount", "Expected a finite number.", DOCS + "dto/TransactionDto.java");
        }
        for (int i = 0; i < collector.getRecurringPayments().size(); i++) {
            var payment = collector.getRecurringPayments().get(i);
            if (payment != null && ((payment.getExpectedAmount() != null && !Double.isFinite(payment.getExpectedAmount()))
                    || (payment.getAcceptableAmountVariationRatio() != null && !Double.isFinite(payment.getAcceptableAmountVariationRatio())))) {
                add(findings, "ERROR", "number.invalid", "/recurringPayments/" + i, "Expected finite amounts and variation ratios.", DOCS + "dto/RecurringPaymentDto.java");
            }
        }
        if (findings.isEmpty()) findings.addAll(validate(JsonParser.parseString(CollectorResultJson.toJson(collector, false)).getAsJsonObject(), previous));
        if (collector.getProgress() < 0 || collector.getProgress() > 100) {
            findings.add(new Finding("ERROR", "progress.range", "/progress", "Progress must be between 0 and 100.", DOCS + "api/CollectorPlugin.java"));
        }
        return findings;
    }

    /**
     * Validate snapshots produced by {@link CollectorResultJson}; pass null when there is no previous result.
     */
    public static List<Finding> validate(JsonObject result, JsonObject previous) {
        List<Finding> findings = new ArrayList<>();
        Map<String, JsonObject> accounts = known(result, "accounts", "uuid");
        Map<String, JsonObject> recurring = known(previous, "recurringPayments", "uuid");
        recurring.putAll(known(result, "recurringPayments", "uuid"));
        for (String group : List.of("accounts", "transactions", "recurringPayments")) {
            String dto = group.equals("accounts") ? "AccountDto" : group.equals("transactions") ? "TransactionDto" : "RecurringPaymentDto";
            String docs = DOCS + "dto/" + dto + ".java";
            JsonElement values = result.get(group);
            if (values == null || !values.isJsonArray()) {
                add(findings, "ERROR", "result.list", "/" + group, "Expected a result array.", docs);
                continue;
            }
            Set<String> ids = new HashSet<>();
            Set<String> uuids = new HashSet<>();
            Map<String, Instant> dates = new HashMap<>();
            Map<String, JsonObject> old = known(previous, group, "id");
            for (int i = 0; i < values.getAsJsonArray().size(); i++) {
                String path = "/" + group + "/" + i;
                JsonElement element = values.getAsJsonArray().get(i);
                if (!element.isJsonObject()) {
                    add(findings, "ERROR", "result.object", path, "Expected a result object.", docs);
                    continue;
                }
                JsonObject value = element.getAsJsonObject();
                String id = string(value, "id");
                String account = string(value, "accountUuid");
                String key = (group.equals("accounts") ? string(value, "counterpartyUuid") : account) + "\u0000" + id;
                required(findings, value, "id", path, docs);
                if (!ids.add(key)) add(findings, "ERROR", "id.duplicate", path + "/id", "ID must be unique within its account (within the counterparty for accounts).", docs);
                if (!group.equals("transactions")) {
                    required(findings, value, "uuid", path, docs);
                    String uuid = string(value, "uuid");
                    if (!uuid.isBlank() && !validUuid(uuid)) add(findings, "ERROR", "uuid.format", path + "/uuid", "Expected a UUID.", docs);
                    if (!uuids.add(uuid)) add(findings, "ERROR", "uuid.duplicate", path + "/uuid", "UUID must be unique.", docs);
                    JsonObject before = old.get(key);
                    if (before != null && !string(before, "uuid").equals(uuid)) add(findings, "ERROR", "uuid.changed", path + "/uuid", "Preserve the UUID of an existing entity.", docs);
                }
                if (!group.equals("accounts")) {
                    required(findings, value, "accountUuid", path, docs);
                    if (!accounts.containsKey(account)) add(findings, "ERROR", "account.missing", path + "/accountUuid", "Associated account is missing from the current result.", docs);
                }
                if (group.equals("accounts")) {
                    required(findings, value, "counterpartyUuid", path, docs);
                    currency(findings, value, path, docs);
                    String zone = string(value, "timeZoneId");
                    if (!zone.isBlank()) {
                        try { ZoneId.of(zone); }
                        catch (RuntimeException invalid) { add(findings, "ERROR", "timezone.invalid", path + "/timeZoneId", "Expected an IANA time zone ID.", docs); }
                    }
                    finite(findings, value, "currentBalance", path, docs);
                } else if (group.equals("transactions")) {
                    currency(findings, value, path, docs);
                    finite(findings, value, "amount", path, docs);
                    required(findings, value, "type", path, docs);
                    String payment = string(value, "recurrentPaymentUuid");
                    if (!payment.isBlank() && (!recurring.containsKey(payment) || !account.equals(string(recurring.get(payment), "accountUuid")))) {
                        add(findings, "ERROR", "recurring.missing", path + "/recurrentPaymentUuid", "Recurring payment must exist and belong to the same account.", docs);
                    }
                    String date = string(value, "dateTransaction");
                    if (!date.isBlank()) {
                        try {
                            Instant current = Instant.parse(date);
                            Instant last = dates.put(account, current);
                            if (last != null && current.isBefore(last)) add(findings, "WARNING", "date.order", path + "/dateTransaction", "Transactions are not ordered oldest to newest for this account; check whether this is an intentional update.", docs);
                        } catch (RuntimeException invalid) { add(findings, "ERROR", "date.invalid", path + "/dateTransaction", "Expected an ISO-8601 date.", docs); }
                    } else add(findings, "WARNING", "date.missing", path + "/dateTransaction", "Transaction date is missing; check source data.", docs);
                    JsonObject owner = accounts.get(account);
                    if (owner != null && "PURCHASE".equals(string(value, "type")) && Set.of("SAVING", "LOAN").contains(string(owner, "type"))) {
                        add(findings, "ERROR", "type.account", path + "/type", "SAVING and LOAN accounts cannot contain PURCHASE transactions.", DOCS + "dto/AccountDto.java");
                    }
                    // Positive purchase/payment amounts can be refunds, so sign alone is not an error.
                } else {
                    finite(findings, value, "expectedAmount", path, docs);
                    finite(findings, value, "acceptableAmountVariationRatio", path, docs);
                    if (value.has("recurrenceInterval") && !value.get("recurrenceInterval").isJsonNull() && value.get("recurrenceInterval").getAsDouble() < 1) {
                        add(findings, "ERROR", "recurrence.interval", path + "/recurrenceInterval", "Recurrence interval must be positive.", docs);
                    }
                }
                length(findings, value, "label", group.equals("transactions") ? 200 : group.equals("accounts") ? 100 : Integer.MAX_VALUE, path, docs);
                if (group.equals("transactions")) {
                    length(findings, value, "labelOriginal", 200, path, docs);
                    length(findings, value, "details", 2000, path, docs);
                }
            }
        }
        return List.copyOf(findings);
    }

    private static void required(List<Finding> findings, JsonObject value, String field, String path, String docs) {
        if (string(value, field).isBlank()) add(findings, "ERROR", "field.required", path + "/" + field, "Provide a nonblank identifier or type.", docs);
    }

    private static void currency(List<Finding> findings, JsonObject value, String path, String docs) {
        String code = string(value, "currencyCode");
        try { Currency.getInstance(code); }
        catch (RuntimeException invalid) { add(findings, "ERROR", "currency.invalid", path + "/currencyCode", "Expected an ISO 4217 currency code.", docs); }
    }

    private static void finite(List<Finding> findings, JsonObject value, String field, String path, String docs) {
        JsonElement number = value.get(field);
        if (number == null || number.isJsonNull()) return;
        try {
            if (Double.isFinite(number.getAsDouble())) return;
        } catch (RuntimeException ignored) {
            // Non-numeric values have the same field rule as non-finite numbers.
        }
        add(findings, "ERROR", "number.invalid", path + "/" + field, "Expected a finite number.", docs);
    }

    private static void length(List<Finding> findings, JsonObject value, String field, int max, String path, String docs) {
        if (string(value, field).length() > max) add(findings, "WARNING", "text.length", path + "/" + field, "Text exceeds the documented maximum length of " + max + ".", docs);
    }

    private static boolean validUuid(String value) {
        try { return UUID.fromString(value).toString().equalsIgnoreCase(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private static Map<String, JsonObject> known(JsonObject result, String group, String field) {
        Map<String, JsonObject> values = new HashMap<>();
        if (result == null || !result.has(group) || !result.get(group).isJsonArray()) return values;
        for (JsonElement element : result.getAsJsonArray(group)) {
            if (!element.isJsonObject()) continue;
            JsonObject value = element.getAsJsonObject();
            String key = string(value, field);
            if (field.equals("id")) key = string(value, group.equals("accounts") ? "counterpartyUuid" : "accountUuid") + "\u0000" + key;
            values.put(key, value);
        }
        return values;
    }

    private static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? "" : value.getAsString();
    }

    private static void add(List<Finding> findings, String severity, String code, String path, String message, String docs) {
        findings.add(new Finding(severity, code, path, message, docs));
    }
}
