package com.tibudget.test;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;

public class AdvancedCookieJar implements CookieJar {

    private final Object lock = new Object();
    private final List<Cookie> cookies = new ArrayList<>();

    public AdvancedCookieJar(List<SerializableCookie> initialCookies) {
        if (initialCookies != null) {
            for (SerializableCookie sc : initialCookies) {
                cookies.add(sc.toCookie());
            }
        }
    }

    public AdvancedCookieJar() {
    }

    @Override
    public void saveFromResponse(@NonNull HttpUrl url, List<Cookie> newCookies) {
        synchronized (lock) {
            long now = System.currentTimeMillis();

            for (Cookie cookie : newCookies) {

                if (cookie.expiresAt() < now) {
                    remove(cookie);
                    continue;
                }

                remove(cookie);
                cookies.add(cookie);
            }
        }
    }

    @Override
    public @NonNull List<Cookie> loadForRequest(@NonNull HttpUrl url) {
        synchronized (lock) {
            long now = System.currentTimeMillis();

            cookies.removeIf(cookie -> cookie.expiresAt() < now);

            List<Cookie> result = new ArrayList<>();
            for (Cookie cookie : cookies) {
                if (cookie.matches(url)) {
                    result.add(cookie);
                }
            }
            return result;
        }
    }

    public List<SerializableCookie> export() {
        synchronized (lock) {
            List<SerializableCookie> result = new ArrayList<>();
            for (Cookie cookie : cookies) {
                result.add(SerializableCookie.fromCookie(cookie));
            }
            return result;
        }
    }

    public void clear() {
        synchronized (lock) {
            cookies.clear();
        }
    }

    private void remove(Cookie cookie) {
        cookies.removeIf(it ->
                it.name().equals(cookie.name()) &&
                        it.domain().equals(cookie.domain()) &&
                        it.path().equals(cookie.path())
        );
    }
}
