package com.tibudget.test.simulator;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Renders the diagnostic HTML snapshot locally without scripts, navigation or network requests.
 */
final class HtmlResponsePreview {
    private HtmlResponsePreview() {
    }

    static boolean isHtml(String headers, String body) {
        if (headers != null && headers.toLowerCase(java.util.Locale.ROOT).matches("(?s).*content-type:[^\\r\\n]*(?:text/html|application/xhtml\\+xml).*")) return true;
        return body != null && body.stripLeading().matches("(?is)^(?:<!doctype\\s+html\\b|<html\\b|<head\\b|<body\\b).*" );
    }

    static String pageTitle(String body) {
        return Jsoup.parse(body).title();
    }

    static String formattedJson(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            com.google.gson.stream.JsonReader reader = new com.google.gson.stream.JsonReader(new java.io.StringReader(body));
            reader.setStrictness(com.google.gson.Strictness.STRICT);
            com.google.gson.JsonElement json = com.google.gson.JsonParser.parseReader(reader);
            if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) return null;
            return new com.google.gson.GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create().toJson(json);
        } catch (com.google.gson.JsonParseException | java.io.IOException | IllegalStateException exception) {
            return null;
        }
    }

    static boolean canOpenUrl(String method, String url) {
        if (!"GET".equalsIgnoreCase(method) || url == null || url.toLowerCase(java.util.Locale.ROOT).matches(".*(?:\\[hidden\\]|%5bhidden%5d).*")) return false;
        try {
            java.net.URI uri = java.net.URI.create(url);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) && uri.getHost() != null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    static String render(String body) {
        String truncation = "\n" + NetworkInspector.TRUNCATION_NOTICE;
        boolean truncated = body.endsWith(truncation);
        Document snapshot = Jsoup.parse(truncated ? body.substring(0, body.length() - truncation.length()) : body);
        snapshot.select("script,base,meta[http-equiv],iframe,frame,frameset,object,embed,link").remove();
        for (Element element : snapshot.getAllElements()) {
            for (var attribute : new java.util.ArrayList<>(element.attributes().asList())) {
                String name = attribute.getKey().toLowerCase(java.util.Locale.ROOT);
                if (name.startsWith("on") || name.equals("href") || name.equals("action") || name.equals("formaction") || name.equals("srcdoc")) element.removeAttr(attribute.getKey());
            }
        }
        snapshot.head().prependElement("meta").attr("http-equiv", "Content-Security-Policy")
                .attr("content", "default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; img-src data:; base-uri 'none'; form-action 'none'");
        Document preview = Jsoup.parse("<!doctype html><html><head><meta charset=\"UTF-8\"><title>HTTP response preview</title></head><body></body></html>");
        preview.head().appendElement("style").text("html,body{height:100%;margin:0}body{display:flex;flex-direction:column}p{margin:0;padding:12px;font:14px sans-serif;background:#eef2f6;color:#24324a}iframe{flex:1;width:100%;border:0}");
        String title = snapshot.title();
        preview.body().appendElement("p").attr("id", "page-title").text("Page title: " + (title.isBlank() ? "(no title)" : title));
        if (snapshot.body().children().isEmpty() && snapshot.body().text().isBlank()) {
            preview.body().appendElement("p").text("The captured HTML body is empty. This page may require JavaScript.");
        }
        preview.body().appendElement("p").text("Static HTTP response preview. Authentication secrets are hidden. Scripts, links and network resources are disabled.");
        if (truncated) preview.body().appendElement("p").attr("id", "truncation-notice").text(NetworkInspector.TRUNCATION_NOTICE);
        // srcdoc keeps relative resources away from the local filesystem; the sandbox also blocks form submission.
        preview.body().appendElement("iframe").attr("title", "Captured HTML response").attr("sandbox", "").attr("srcdoc", snapshot.outerHtml());
        return preview.outerHtml();
    }
}
