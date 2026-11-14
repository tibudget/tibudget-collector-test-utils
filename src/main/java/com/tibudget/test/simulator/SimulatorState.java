package com.tibudget.test.simulator;

import com.google.gson.*;
import com.tibudget.api.CollectorPlugin;
import com.tibudget.dto.AccountDto;
import com.tibudget.dto.RecurringPaymentDto;
import com.tibudget.dto.TransactionDto;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.Function;

/**
 * Keep local simulator state separate for each service implementation and input identity.
 * Preserve account UUIDs across runs so that resume behaves like the Tibu host.
 */
final class SimulatorState {
    static final Gson JSON = new GsonBuilder().setPrettyPrinting().setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
            .registerTypeHierarchyAdapter(File.class, (JsonSerializer<File>) (file, type, context) -> new JsonPrimitive(file.getAbsolutePath()))
            .registerTypeHierarchyAdapter(File.class, (JsonDeserializer<File>) (json, type, context) -> new File(json.getAsString())).create();
    String identity;
    Map<String, String> settings = new HashMap<>();
    List<AccountDto> accounts = new ArrayList<>();
    List<TransactionDto> transactions = new ArrayList<>();
    List<RecurringPaymentDto> recurringPayments = new ArrayList<>();

    static SimulatorState load(Path file) throws IOException {
        if (!Files.exists(file)) return new SimulatorState();
        try {
            SimulatorState state = JSON.fromJson(Files.readString(file), SimulatorState.class);
            if (state == null || state.settings == null || state.accounts == null || state.transactions == null || state.recurringPayments == null) {
                throw new JsonParseException("Missing simulator state fields");
            }
            return state;
        } catch (JsonParseException exception) {
            throw new IOException("Cannot read simulator state. Use Run from scratch to reset it.", exception);
        }
    }

    void update(CollectorPlugin collector) {
        settings = new HashMap<>(collector.getSettings());
        accounts = merge(accounts, collector.getAccounts(), AccountDto::getId);
        transactions = merge(transactions, collector.getTransactions(), transaction -> transaction.getAccountUuid() + ":" + transaction.getId());
        recurringPayments = merge(recurringPayments, collector.getRecurringPayments(), payment -> payment.getAccountUuid() + ":" + payment.getId());
    }

    void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "simulator-state-", ".tmp");
        try {
            Files.writeString(temporary, JSON.toJson(this));
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static <T> List<T> merge(List<T> previous, List<T> current, Function<T, String> key) {
        Map<String, T> result = new LinkedHashMap<>();
        previous.forEach(value -> result.put(key.apply(value), value));
        current.forEach(value -> result.put(key.apply(value), value));
        return new ArrayList<>(result.values());
    }
}
