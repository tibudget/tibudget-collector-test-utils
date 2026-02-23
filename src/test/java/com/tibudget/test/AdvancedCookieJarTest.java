package com.tibudget.test;

import okhttp3.Cookie;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AdvancedCookieJarTest {

    private AdvancedCookieJar cookieJar;
    private HttpUrl baseUrl;

    @BeforeEach
    void setUp() {
        cookieJar = new AdvancedCookieJar();
        baseUrl = HttpUrl.get("https://example.com/");
    }

    @Test
    void shouldSaveAndLoadCookie() {
        Cookie cookie = createCookie("session", "abc", "example.com", "/", false);

        cookieJar.saveFromResponse(baseUrl, List.of(cookie));
        List<Cookie> loaded = cookieJar.loadForRequest(baseUrl);

        assertEquals(1, loaded.size());
        assertEquals("session", loaded.get(0).name());
        assertEquals("abc", loaded.get(0).value());
    }

    @Test
    void shouldReplaceExistingCookieWithSameNameDomainAndPath() {
        Cookie first = createCookie("token", "v1", "example.com", "/", false);
        Cookie second = createCookie("token", "v2", "example.com", "/", false);

        cookieJar.saveFromResponse(baseUrl, List.of(first));
        cookieJar.saveFromResponse(baseUrl, List.of(second));

        List<Cookie> loaded = cookieJar.loadForRequest(baseUrl);

        assertEquals(1, loaded.size());
        assertEquals("v2", loaded.get(0).value());
    }

    @Test
    void shouldNotReturnExpiredCookies() {
        Cookie expired = createCookie("old", "x", "example.com", "/", true);

        cookieJar.saveFromResponse(baseUrl, List.of(expired));
        List<Cookie> loaded = cookieJar.loadForRequest(baseUrl);

        assertTrue(loaded.isEmpty());
    }

    @Test
    void shouldRemoveExpiredCookieOnSave() {
        Cookie valid = createCookie("valid", "1", "example.com", "/", false);
        Cookie expired = createCookie("valid", "2", "example.com", "/", true);

        cookieJar.saveFromResponse(baseUrl, List.of(valid));
        cookieJar.saveFromResponse(baseUrl, List.of(expired));

        List<Cookie> loaded = cookieJar.loadForRequest(baseUrl);
        assertTrue(loaded.isEmpty());
    }

    @Test
    void shouldMatchCookieByDomainAndPath() {
        Cookie cookie = new Cookie.Builder()
                .name("pathCookie")
                .value("ok")
                .domain("example.com")
                .path("/api")
                .expiresAt(System.currentTimeMillis() + 60_000)
                .build();

        cookieJar.saveFromResponse(baseUrl, List.of(cookie));

        HttpUrl matchingUrl = HttpUrl.get("https://example.com/api/test");
        HttpUrl nonMatchingUrl = HttpUrl.get("https://example.com/other");

        assertEquals(1, cookieJar.loadForRequest(matchingUrl).size());
        assertEquals(0, cookieJar.loadForRequest(nonMatchingUrl).size());
    }

    @Test
    void shouldClearAllCookies() {
        Cookie cookie = createCookie("toClear", "x", "example.com", "/", false);

        cookieJar.saveFromResponse(baseUrl, List.of(cookie));
        assertFalse(cookieJar.loadForRequest(baseUrl).isEmpty());

        cookieJar.clear();
        assertTrue(cookieJar.loadForRequest(baseUrl).isEmpty());
    }

    @Test
    void shouldHandleMultipleCookies() {
        Cookie a = createCookie("a", "1", "example.com", "/", false);
        Cookie b = createCookie("b", "2", "example.com", "/", false);

        cookieJar.saveFromResponse(baseUrl, List.of(a, b));

        List<Cookie> loaded = cookieJar.loadForRequest(baseUrl);
        assertEquals(2, loaded.size());
    }

    private Cookie createCookie(
            String name,
            String value,
            String domain,
            String path,
            boolean expired
    ) {
        long expiresAt = expired
                ? System.currentTimeMillis() - 1_000
                : System.currentTimeMillis() + 60_000;

        return new Cookie.Builder()
                .name(name)
                .value(value)
                .domain(domain)
                .path(path)
                .expiresAt(expiresAt)
                .build();
    }
}
