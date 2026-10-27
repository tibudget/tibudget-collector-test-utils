package com.tibudget.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StringMapSerializerTest {
    @TempDir Path temp;

    @Test
    void savesUtf8SettingsAndCreatesParentDirectories() throws Exception {
        Path file = temp.resolve("settings/local.json");
        StringMapSerializer serializer = new StringMapSerializer(file.toString());
        Map<String, String> settings = Map.of("label", "Été à Paris", "progress", "42");
        serializer.save(settings);
        assertEquals(settings, serializer.load());
        assertTrue(Files.readString(file).contains("Été à Paris"));
    }

    @Test
    void missingNullAndMalformedFilesReturnAnEmptyMap() throws Exception {
        Path file = temp.resolve("settings.json");
        StringMapSerializer serializer = new StringMapSerializer(file.toString());
        assertTrue(serializer.load().isEmpty());
        for (String content : new String[]{"null", "", "invalid JSON", "[]"}) {
            Files.writeString(file, content);
            assertTrue(serializer.load().isEmpty());
        }
    }
}
