package com.tibudget.test.simulator;

import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class SimulatorSettingsTest {
    @TempDir Path temporary;

    @Test
    void missingSettingsDoNotCreateAKey() throws Exception {
        Path directory = temporary.resolve("config");
        assertTrue(new SimulatorSettings(directory, "Collector").load().isEmpty());
        assertFalse(Files.exists(directory));
    }

    @Test
    void encryptsInputsAndRestoresThemWithANewStoreInstance() throws Exception {
        Path directory = temporary.resolve("config");
        JsonObject values = values();
        new SimulatorSettings(directory, "Collector").save(values);
        assertEquals(values, new SimulatorSettings(directory, "Collector").load());
        try (var paths = Files.list(directory)) {
            for (Path file : paths.toList()) {
                byte[] stored = Files.readAllBytes(file);
                assertFalse(new String(stored, StandardCharsets.ISO_8859_1).contains("synthetic-password"));
                assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
            }
        }
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
    }

    @Test
    void usesANewNonceForEachSaveAndKeepsTheKey() throws Exception {
        SimulatorSettings settings = new SimulatorSettings(temporary, "Collector");
        settings.save(values());
        Path file = encryptedFile(temporary);
        byte[] first = Files.readAllBytes(file);
        byte[] key = Files.readAllBytes(temporary.resolve("settings.key"));
        settings.save(values());
        assertFalse(Arrays.equals(first, Files.readAllBytes(file)));
        assertArrayEquals(key, Files.readAllBytes(temporary.resolve("settings.key")));
        assertEquals(values(), settings.load());
    }

    @Test
    void rejectsTamperedDataWithoutLosingTheOriginalFile() throws Exception {
        SimulatorSettings settings = new SimulatorSettings(temporary, "Collector");
        settings.save(values());
        Path file = encryptedFile(temporary);
        byte[] stored = Files.readAllBytes(file);
        stored[stored.length - 1] ^= 1;
        Files.write(file, stored);
        assertThrows(GeneralSecurityException.class, settings::load);
        assertArrayEquals(stored, Files.readAllBytes(file));
    }

    @Test
    void isolatesCollectorsAndRejectsACopiedSettingsFile() throws Exception {
        SimulatorSettings first = new SimulatorSettings(temporary, "First");
        SimulatorSettings second = new SimulatorSettings(temporary, "Second");
        first.save(values());
        assertTrue(second.load().isEmpty());
        Path firstFile = encryptedFile(temporary);
        JsonObject other = new JsonObject();
        other.addProperty("login", "another-synthetic-user");
        second.save(other);
        assertEquals(values(), first.load());
        assertEquals(other, second.load());
        try (var paths = Files.list(temporary)) {
            Path secondFile = paths.filter(path -> path.toString().endsWith(".enc") && !path.equals(firstFile)).findFirst().orElseThrow();
            Files.copy(firstFile, secondFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        assertThrows(GeneralSecurityException.class, second::load);
    }

    @Test
    void missingKeyDoesNotSilentlyReplaceExistingEncryptedSettings() throws Exception {
        SimulatorSettings settings = new SimulatorSettings(temporary, "Collector");
        settings.save(values());
        Files.delete(temporary.resolve("settings.key"));
        assertThrows(IOException.class, settings::load);
        assertThrows(IOException.class, () -> settings.save(values()));
        assertFalse(Files.exists(temporary.resolve("settings.key")));
    }

    @Test
    void rejectsTruncatedFilesAndSymbolicKeys() throws Exception {
        SimulatorSettings settings = new SimulatorSettings(temporary, "Collector");
        settings.save(values());
        Path file = encryptedFile(temporary);
        Files.write(file, new byte[] {1, 2, 3});
        assertThrows(IOException.class, settings::load);
        Path key = temporary.resolve("settings.key");
        Path other = temporary.resolve("other.key");
        Files.move(key, other);
        Files.createSymbolicLink(key, other);
        assertThrows(IOException.class, () -> settings.save(values()));
    }

    private static JsonObject values() {
        JsonObject values = new JsonObject();
        values.addProperty("login", "synthetic-user-é");
        values.addProperty("password", "synthetic-password");
        values.addProperty("remember", true);
        values.addProperty("number", Long.MAX_VALUE);
        values.add("optional", JsonNull.INSTANCE);
        return values;
    }

    private static Path encryptedFile(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(file -> file.toString().endsWith(".enc")).findFirst().orElseThrow();
        }
    }
}
