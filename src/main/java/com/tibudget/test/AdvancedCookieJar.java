package com.tibudget.test;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Cookie jar for local tests, optionally saved to a trusted local file.
 * The file uses Java serialization and must not come from an untrusted source.
 */
public class AdvancedCookieJar implements CookieJar {

    private static final Logger LOG = Logger.getLogger(AdvancedCookieJar.class.getName());

    private final Object lock = new Object();
    private final List<Cookie> cookies = new ArrayList<>();
    private final File cookiesFile;

    public AdvancedCookieJar() {
        this(null);
    }

    public AdvancedCookieJar(File cookiesFile) {
        this.cookiesFile = cookiesFile;
        if (cookiesFile != null) {
            load(cookiesFile);
        }
    }

    public void save() {
        if (cookiesFile == null) return;
        synchronized (lock) {
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(cookiesFile))) {
                oos.writeObject(export());
            } catch (IOException e) {
                LOG.log(Level.SEVERE, "Cannot save cookies in " + cookiesFile.getAbsolutePath() + ": " + e.getMessage(), e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public void load(File filePath) {
        synchronized (lock) {
            if (filePath.canRead()) {
                try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(filePath))) {
                    List<SerializableCookie> serializableCookies = (List<SerializableCookie>) ois.readObject();
                    for (SerializableCookie sc : serializableCookies) {
                        cookies.add(sc.toCookie());
                    }
                } catch (IOException | ClassNotFoundException e) {
                    LOG.log(Level.SEVERE, "Cannot load cookies from " + filePath + ": " + e.getMessage(), e);
                }
            }
        }
    }

    @Override
    public void saveFromResponse(@NotNull HttpUrl url, List<Cookie> newCookies) {
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
            save();
        }
    }

    @NotNull
    @Override
    public List<Cookie> loadForRequest(@NotNull HttpUrl url) {
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

    private List<SerializableCookie> export() {
        List<SerializableCookie> result = new ArrayList<>();
        for (Cookie cookie : cookies) {
            result.add(SerializableCookie.fromCookie(cookie));
        }
        return result;
    }

    public void clear() {
        synchronized (lock) {
            cookies.clear();
            save();
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
