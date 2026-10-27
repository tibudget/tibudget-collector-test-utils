package com.tibudget.test;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Stores local collector settings as UTF-8 JSON. Missing or unreadable files load as an empty map.
 * Keep files containing login data outside committed test resources.
 */
public class StringMapSerializer {

    private static final Logger LOG = Logger.getLogger(StringMapSerializer.class.getName());

    private static final Gson gson = new Gson();

    private final File file;

    /**
     * Constructor that defines the file path for storing values.
     *
     * @param filePath The file path where values will be stored.
     */
    public StringMapSerializer(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            throw new IllegalArgumentException("File path cannot be null or empty");
        } else {
            this.file = new File(filePath);
            LOG.info("values will be stored in " + this.file.getAbsolutePath());
        }
    }

    /**
     * Saves the given {@code Map<String, String>} as a JSON file.
     *
     * @param values settings to save
     */
    public void save(Map<String, String> values) {
        try {
            Files.createDirectories(file.toPath().toAbsolutePath().getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create settings directory", e);
        }
        try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            gson.toJson(values, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save settings to " + file.getAbsolutePath(), e);
        }
    }

    /**
     * Loads values from the JSON file into a {@code Map<String, String>}.
     * If the file does not exist or an error occurs, it returns an empty map.
     *
     * @return A map containing the stored values, or an empty map if the file does not exist.
     */
    public Map<String, String> load() {
        if (!file.exists()) {
            return Collections.emptyMap();
        }

        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            Type type = new TypeToken<Map<String, String>>() {}.getType();
            Map<String, String> values = gson.fromJson(reader, type);
            return values == null ? Collections.emptyMap() : values;
        } catch (IOException | JsonParseException e) {
            return Collections.emptyMap();
        }
    }
}
