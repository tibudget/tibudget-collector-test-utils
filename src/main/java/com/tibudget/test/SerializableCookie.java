package com.tibudget.test;

import okhttp3.Cookie;

public class SerializableCookie {

    public final String name;
    public final String value;
    public final String domain;
    public final String path;
    public final long expiresAt;
    public final boolean secure;
    public final boolean httpOnly;
    public final boolean hostOnly;

    public SerializableCookie(
            String name,
            String value,
            String domain,
            String path,
            long expiresAt,
            boolean secure,
            boolean httpOnly,
            boolean hostOnly
    ) {
        this.name = name;
        this.value = value;
        this.domain = domain;
        this.path = path;
        this.expiresAt = expiresAt;
        this.secure = secure;
        this.httpOnly = httpOnly;
        this.hostOnly = hostOnly;
    }

    public static SerializableCookie fromCookie(Cookie cookie) {
        return new SerializableCookie(
                cookie.name(),
                cookie.value(),
                cookie.domain(),
                cookie.path(),
                cookie.expiresAt(),
                cookie.secure(),
                cookie.httpOnly(),
                cookie.hostOnly()
        );
    }

    public Cookie toCookie() {
        Cookie.Builder builder = new Cookie.Builder()
                .name(name)
                .value(value)
                .path(path)
                .expiresAt(expiresAt);

        if (hostOnly) {
            builder.hostOnlyDomain(domain);
        } else {
            builder.domain(domain);
        }

        if (secure) {
            builder.secure();
        }

        if (httpOnly) {
            builder.httpOnly();
        }

        return builder.build();
    }
}
