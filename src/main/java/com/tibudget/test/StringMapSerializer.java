package com.tibudget.test;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Map;
import java.util.logging.Logger;

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
     * Saves the given Map<String, String> as a JSON file.
     *
     * @param cookies A map containing cookie key-value pairs.
     */
    public void save(Map<String, String> cookies) {
        try (Writer writer = new FileWriter(file)) {
            gson.toJson(cookies, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save cookies to " + file.getAbsolutePath(), e);
        }
    }

    /**
     * Loads values from the JSON file into a Map<String, String>.
     * If the file does not exist or an error occurs, it returns an empty map.
     *
     * @return A map containing the stored values, or an empty map if the file does not exist.
     */
    public Map<String, String> load() {
        if (!file.exists()) {
            return Collections.emptyMap();
        }

        try (Reader reader = new FileReader(file)) {
            Type type = new TypeToken<Map<String, String>>() {}.getType();
            return gson.fromJson(reader, type);
        } catch (IOException e) {
            return Collections.emptyMap();
        }
    }
}
