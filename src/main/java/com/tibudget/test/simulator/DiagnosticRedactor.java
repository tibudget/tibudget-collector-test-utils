package com.tibudget.test.simulator;

import com.google.gson.*;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keep credentials and session secrets out of local diagnostic views and captured logs.
 * Response DTOs remain available separately in the result inspector.
 */
final class DiagnosticRedactor {
    private static final String MASK = "[hidden]";
    // The possessive repetition avoids recursive regex backtracking on large embedded strings.
    private static final Pattern JSON_STRING = Pattern.compile("(\"([^\"\\\\]+)\"\\s*:\\s*\")((?:\\\\.|[^\"\\\\])*+)(\"|$)");
    private static final Pattern FORM_SECRET = Pattern.compile("(?i)((?:password|passwd|secret|token|access_token|refresh_token|csrf|captcha|otp|code|username|email)=)[^&\\s]*");
    private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)[a-z0-9._~+/=-]+");
    private final Set<String> secrets = ConcurrentHashMap.newKeySet();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    void remember(String value) {
        if (value != null && !value.isBlank()) secrets.add(value);
    }

    boolean sensitive(String name) {
        String key = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[-_]", "");
        return key.contains("password") || key.contains("passwd") || key.contains("secret") || key.contains("token") || key.contains("cookie")
                || key.contains("authorization") || key.contains("captcha") || key.contains("csrf") || key.contains("session")
                || key.contains("otp") || key.contains("credential") || key.contains("apikey") || key.equals("code") || key.equals("codeverifier")
                || key.equals("username") || key.equals("login") || key.equals("email") || key.equals("auth") || key.equals("jwt") || key.equals("pin") || key.equals("signature");
    }

    String header(String name, String value) {
        if (sensitive(name)) {
            remember(value);
            return MASK;
        }
        return text(value);
    }

    String body(String value) {
        if (value == null || value.isBlank()) return "(empty)";
        try {
            JsonElement element = JsonParser.parseString(value);
            if (element.isJsonObject() || element.isJsonArray()) {
                redact(element);
                return text(gson.toJson(element));
            }
        } catch (JsonParseException ignored) {
            // Truncated JSON, HTML and plain text still pass through the text redactor.
        }
        if (value.stripLeading().startsWith("<")) {
            org.jsoup.nodes.Document document = org.jsoup.Jsoup.parse(value);
            for (org.jsoup.nodes.Element input : document.select("input")) {
                if (sensitive(input.attr("name")) || input.attr("type").equalsIgnoreCase("password")) {
                    remember(input.val());
                    input.val(MASK);
                }
            }
            for (org.jsoup.nodes.Element meta : document.select("meta[name]")) {
                if (sensitive(meta.attr("name"))) {
                    remember(meta.attr("content"));
                    meta.attr("content", MASK);
                }
            }
            return text(document.outerHtml());
        }
        return text(value);
    }

    private void redact(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (String name : Set.copyOf(object.keySet())) {
                JsonElement value = object.get(name);
                if (sensitive(name)) {
                    rememberValues(value);
                    object.addProperty(name, MASK);
                } else if (name.equalsIgnoreCase("data") && value.isJsonPrimitive() && value.getAsString().length() > 1000) {
                    object.addProperty(name, "[document content omitted: " + value.getAsString().length() + " characters]");
                } else {
                    redact(value);
                }
            }
        } else if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(this::redact);
        }
    }

    private void rememberValues(JsonElement value) {
        if (value.isJsonObject()) value.getAsJsonObject().entrySet().forEach(entry -> rememberValues(entry.getValue()));
        else if (value.isJsonArray()) value.getAsJsonArray().forEach(this::rememberValues);
        else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) remember(value.getAsString());
    }

    String text(String value) {
        if (value == null) return "";
        Matcher matcher = JSON_STRING.matcher(value);
        StringBuilder safe = new StringBuilder();
        while (matcher.find()) {
            if (sensitive(matcher.group(2))) {
                remember(matcher.group(3));
                matcher.appendReplacement(safe, Matcher.quoteReplacement(matcher.group(1) + MASK + matcher.group(4)));
            } else {
                matcher.appendReplacement(safe, Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(safe);
        String result = BEARER.matcher(safe).replaceAll("$1" + MASK);
        result = FORM_SECRET.matcher(result).replaceAll("$1" + MASK);
        // Replace longer values first so a short secret cannot expose part of a longer one.
        for (String secret : secrets.stream().sorted(java.util.Comparator.comparingInt(String::length).reversed()).toList()) {
            if (secret.length() >= 4) result = result.replace(secret, MASK);
            else result = result.replaceAll("(?<![\\p{L}\\p{N}])" + Pattern.quote(secret) + "(?![\\p{L}\\p{N}])", Matcher.quoteReplacement(MASK));
        }
        return result;
    }
}
