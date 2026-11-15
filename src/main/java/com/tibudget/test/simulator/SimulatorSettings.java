package com.tibudget.test.simulator;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;

/**
 * Save collector inputs with authenticated encryption outside the project.
 * The local key and encrypted files are restricted to the current OS user.
 */
final class SimulatorSettings {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path directory;
    private final Path file;
    private final byte[] context;

    SimulatorSettings(Path directory, String collectorName) throws GeneralSecurityException {
        this.directory = directory;
        context = ("tibu-simulator-inputs-v1:" + collectorName).getBytes(StandardCharsets.UTF_8);
        String name = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(collectorName.getBytes(StandardCharsets.UTF_8)));
        file = directory.resolve(name + ".enc");
    }

    JsonObject load() throws IOException, GeneralSecurityException {
        if (!Files.exists(file)) return new JsonObject();
        byte[] key = key(false);
        privateFile(file);
        byte[] stored = Files.readAllBytes(file);
        if (stored.length < 29 || stored[0] != 1) throw new IOException("Unsupported encrypted settings format");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, Arrays.copyOfRange(stored, 1, 13)));
        cipher.updateAAD(context);
        return JsonParser.parseString(new String(cipher.doFinal(stored, 13, stored.length - 13), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    void save(JsonObject values) throws IOException, GeneralSecurityException {
        byte[] key = key(true);
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(context);
        byte[] encrypted = cipher.doFinal(values.toString().getBytes(StandardCharsets.UTF_8));
        byte[] stored = ByteBuffer.allocate(13 + encrypted.length).put((byte) 1).put(nonce).put(encrypted).array();
        Path temporary = Files.createTempFile(directory, "inputs-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            Files.write(temporary, stored);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException failure) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private byte[] key(boolean create) throws IOException {
        // Fail closed on filesystems that cannot enforce private POSIX permissions.
        if (Files.isSymbolicLink(directory)) throw new IOException("Settings directory must not be a symbolic link");
        Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        Path lockFile = directory.resolve("settings.lock");
        if (Files.isSymbolicLink(lockFile)) throw new IOException("Settings lock must not be a symbolic link");
        try (var channel = FileChannel.open(lockFile, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); var lock = channel.lock()) {
            Path keyFile = directory.resolve("settings.key");
            if (create && !Files.exists(keyFile) && !Files.exists(file)) {
                byte[] generated = new byte[32];
                RANDOM.nextBytes(generated);
                try {
                    Files.createFile(keyFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                    Files.write(keyFile, generated);
                } catch (FileAlreadyExistsException ignored) {
                    // Another simulator already created the shared key.
                }
            }
            privateFile(keyFile);
            byte[] key = Files.readAllBytes(keyFile);
            if (key.length != 32) throw new IOException("Invalid simulator settings key");
            return key;
        }
    }

    private static void privateFile(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Expected a regular private settings file");
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }
}
