package com.tibudget.test.simulator;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HtmlResponsePreviewTest {
    @Test
    void detectsHtmlWithoutTreatingJsonAsHtml() {
        assertTrue(HtmlResponsePreview.isHtml("Content-Type: text/html; charset=UTF-8", "<div>Hello</div>"));
        assertTrue(HtmlResponsePreview.isHtml("content-type: application/xhtml+xml", "<div>Hello</div>"));
        assertTrue(HtmlResponsePreview.isHtml("", "  <!doctype html><html></html>"));
        assertFalse(HtmlResponsePreview.isHtml("Content-Type: application/json", "{\"html\":\"<html></html>\"}"));
        assertFalse(HtmlResponsePreview.isHtml("", "Waiting for response…"));
    }

    @Test
    void preservesVisibleContentAndInlineStylesInsideAnIsolatedFrame() {
        var page = Jsoup.parse(HtmlResponsePreview.render("<html><head><style>h1{color:red}</style></head><body><h1>Sign in</h1><input placeholder='Email'></body></html>"));
        var frame = page.selectFirst("iframe");
        assertNotNull(frame);
        assertTrue(frame.hasAttr("sandbox"));
        assertEquals("", frame.attr("sandbox"));
        var snapshot = Jsoup.parse(frame.attr("srcdoc"));
        assertEquals("Sign in", snapshot.selectFirst("h1").text());
        assertEquals("Email", snapshot.selectFirst("input").attr("placeholder"));
        assertTrue(snapshot.selectFirst("style").data().contains("color:red"));
        assertTrue(snapshot.head().children().first().attr("content").contains("default-src 'none'"));
    }

    @Test
    void disablesScriptsNavigationAndExternalLoadsWithoutEscapingTheFrame() {
        String html = "<meta http-equiv='refresh' content='0;url=https://example.test'><base href='file:///tmp/'>"
                + "<link rel='stylesheet' href='https://example.test/style.css'><script>fetch('https://example.test')</script>"
                + "<iframe src='https://example.test'></iframe><form action='https://example.test'><input></form>"
                + "<a href='https://example.test' onclick='alert(1)'>Help</a><img src='https://example.test/image'>";
        var outer = Jsoup.parse(HtmlResponsePreview.render(html));
        assertEquals(1, outer.select("iframe").size());
        var snapshot = Jsoup.parse(outer.selectFirst("iframe").attr("srcdoc"));
        assertTrue(snapshot.select("script,iframe,base,link,[onclick],[href],[action]").isEmpty());
        assertEquals(1, snapshot.select("meta[http-equiv]").size());
        assertTrue(snapshot.selectFirst("meta[http-equiv]").attr("content").contains("img-src data:"));
        assertEquals("Help", snapshot.selectFirst("a").text());
    }
    @Test
    void showsTheTitleEvenWhenTheBodyIsEmpty() {
        String html = "<html><head><title>Netflix sign-in</title></head><body></body></html>";
        var preview = Jsoup.parse(HtmlResponsePreview.render(html));
        assertEquals("Netflix sign-in", HtmlResponsePreview.pageTitle(html));
        assertEquals("Page title: Netflix sign-in", preview.selectFirst("#page-title").text());
        assertTrue(preview.body().text().contains("HTML body is empty"));
    }

    @Test
    void formatsJsonAndKeepsNullsAndEscapedHtmlReadable() {
        String formatted = HtmlResponsePreview.formattedJson("{\"items\":[1,2],\"value\":null,\"html\":\"<b>Hello</b>\"}");
        assertNotNull(formatted);
        assertTrue(formatted.contains("\n"));
        assertTrue(formatted.contains("\"value\": null"));
        assertTrue(formatted.contains("<b>Hello</b>"));
        assertEquals(com.google.gson.JsonParser.parseString("{\"items\":[1,2],\"value\":null,\"html\":\"<b>Hello</b>\"}"), com.google.gson.JsonParser.parseString(formatted));
    }

    @Test
    void rejectsMalformedOrTruncatedJson() {
        assertNull(HtmlResponsePreview.formattedJson("{\"value\":"));
        assertNull(HtmlResponsePreview.formattedJson("{\"value\":1} trailing text"));
        assertNull(HtmlResponsePreview.formattedJson("<html></html>"));
        assertNull(HtmlResponsePreview.formattedJson(""));
    }

    @Test
    void opensOnlyGetWebUrlsWithoutMaskedSecrets() {
        assertTrue(HtmlResponsePreview.canOpenUrl("GET", "https://www.netflix.com/login?locale=fr"));
        assertFalse(HtmlResponsePreview.canOpenUrl("POST", "https://www.netflix.com/graphql"));
        assertFalse(HtmlResponsePreview.canOpenUrl("GET", "file:///tmp/private.html"));
        assertFalse(HtmlResponsePreview.canOpenUrl("GET", "https://example.test/?token=%5Bhidden%5D"));
        assertFalse(HtmlResponsePreview.canOpenUrl("GET", "https://example.test/?token=[hidden]"));
        assertFalse(HtmlResponsePreview.canOpenUrl("GET", "not a URL"));
    }

    @Test
    void showsTruncationNoticeOnlyForTruncatedResponses() {
        String html = "<html><head><title>Page</title></head><body>Hello</body></html>";
        var complete = Jsoup.parse(HtmlResponsePreview.render(html));
        assertTrue(complete.select("#truncation-notice").isEmpty());
        assertFalse(complete.body().text().contains("truncated"));
        var truncated = Jsoup.parse(HtmlResponsePreview.render(html + "\n" + NetworkInspector.TRUNCATION_NOTICE));
        assertEquals(NetworkInspector.TRUNCATION_NOTICE, truncated.selectFirst("#truncation-notice").text());
        assertFalse(truncated.selectFirst("iframe").attr("srcdoc").contains(NetworkInspector.TRUNCATION_NOTICE));
    }

}
