package com.tibudget.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tibudget.api.CollectorPlugin;
import com.tibudget.dto.AccountDto;
import com.tibudget.dto.FileDto;
import com.tibudget.dto.PaymentDto;
import com.tibudget.dto.RecurringPaymentDto;
import com.tibudget.dto.TransactionDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CollectorResultJsonTest {
    @TempDir Path temp;

    @Test
    void serializesAllThreeResultsIncludingNestedDataAndUtcDates() {
        AccountDto account = account();
        TransactionDto transaction = transaction();
        PaymentDto payment = new PaymentDto();
        payment.setAmount(12.34);
        payment.setPaymentDate(new Date(123));
        transaction.addPayment(payment);
        RecurringPaymentDto recurring = recurring();
        JsonObject json = JsonParser.parseString(CollectorResultJson.toJson(
                List.of(account), List.of(transaction), List.of(recurring), false)).getAsJsonObject();

        JsonObject savedAccount = json.getAsJsonArray("accounts").get(0).getAsJsonObject();
        assertEquals("Compte été", savedAccount.get("label").getAsString());
        assertEquals("1970-01-01T00:00:00.123Z", savedAccount.get("lastUpdate").getAsString());
        assertEquals("account-uuid", savedAccount.get("uuid").getAsString());
        JsonObject savedTransaction = json.getAsJsonArray("transactions").get(0).getAsJsonObject();
        assertEquals("account-uuid", savedTransaction.get("accountUuid").getAsString());
        assertEquals("subscription-uuid", savedTransaction.get("recurrentPaymentUuid").getAsString());
        assertEquals(12.34, savedTransaction.getAsJsonArray("payments").get(0)
                .getAsJsonObject().get("amount").getAsDouble());
        assertTrue(savedTransaction.get("details").isJsonNull());
        assertEquals(9.99, json.getAsJsonArray("recurringPayments").get(0)
                .getAsJsonObject().get("expectedAmount").getAsDouble());
        assertEquals("Compte été", account.getLabel());
        assertEquals(new Date(123), account.getLastUpdate());
    }

    @Test
    void writesReferenceAndComparesWithoutOverwritingIt() throws Exception {
        AccountDto account = account();
        TransactionDto transaction = transaction();
        RecurringPaymentDto recurring = recurring();
        CollectorPlugin collector = collector(List.of(account), List.of(transaction), List.of(recurring));
        File reference = temp.resolve("collection.json").toFile();
        CollectorResultJson.save(reference, collector);
        String expected = Files.readString(reference.toPath());
        CollectorResultJson.assertMatches(reference, collector);

        account.setCurrentBalance(20);
        assertThrows(AssertionFailedError.class, () -> CollectorResultJson.assertMatches(reference, collector));
        account.setCurrentBalance(0);
        transaction.setAmount(10);
        assertThrows(AssertionFailedError.class, () -> CollectorResultJson.assertMatches(reference, collector));
        transaction.setAmount(0);
        recurring.setExpectedAmount(15.0);
        assertThrows(AssertionFailedError.class, () -> CollectorResultJson.assertMatches(reference, collector));
        assertEquals(expected, Files.readString(reference.toPath()));
        assertThrows(IOException.class, () -> CollectorResultJson.assertMatches(temp.resolve("missing.json").toFile(), collector));
    }

    @Test
    void ignoresObjectKeyOrderAndWhitespaceButPreservesListOrderAndNulls() throws Exception {
        AccountDto first = account();
        AccountDto second = account();
        second.setUuid("second-account");
        second.setId("second-id");
        first.setMetadata("Aa", "one");
        first.setMetadata("BB", "two");
        second.setMetadata("BB", "two");
        second.setMetadata("Aa", "one");
        String initial = CollectorResultJson.toJson(List.of(first, second), List.of(), List.of());
        AccountDto reordered = account();
        reordered.setMetadata("BB", "two");
        reordered.setMetadata("Aa", "one");
        assertEquals(initial, CollectorResultJson.toJson(List.of(reordered, second), List.of(), List.of()));
        assertNotEquals(initial, CollectorResultJson.toJson(List.of(second, first), List.of(), List.of()));

        File reference = temp.resolve("collection.json").toFile();
        Files.writeString(reference.toPath(), "{\"transactions\":[],\"recurringPayments\":[],\"accounts\":[]}");
        CollectorResultJson.assertMatches(reference, collector(List.of(), List.of(), List.of()));
        assertNotEquals(CollectorResultJson.toJson(List.of(), List.of(), List.of()),
                CollectorResultJson.toJson(null, List.of(), List.of()));
    }

    @Test
    void comparesDownloadedFileContentsIndependentlyOfTemporaryPaths() throws Exception {
        Path original = temp.resolve("original.pdf");
        Path replay = temp.resolve("replay.pdf");
        byte[] bytes = {0, 1, (byte) 255, 42};
        Files.write(original, bytes);
        Files.write(replay, bytes);
        FileDto attachment = new FileDto();
        attachment.setFile(original.toFile());
        TransactionDto transaction = transaction();
        transaction.addFile(attachment);
        String expected = CollectorResultJson.toJson(List.of(), List.of(transaction), List.of());
        attachment.setFile(replay.toFile());
        assertEquals(expected, CollectorResultJson.toJson(List.of(), List.of(transaction), List.of()));
        Files.write(replay, new byte[]{42});
        assertNotEquals(expected, CollectorResultJson.toJson(List.of(), List.of(transaction), List.of()));
    }

    @Test
    void stabilizesUuidsAndAllReferencesWithoutChangingCounterpartiesOrCollector() throws Exception {
        AccountDto account = account();
        account.setCounterPartyUuid("account-uuid");
        account.setMetadata("nested-reference", "subscription-uuid");
        TransactionDto transaction = transaction();
        transaction.setCounterPartyUuid("subscription-uuid");
        transaction.setMetadata("account-uuid", "subscription-uuid");
        RecurringPaymentDto recurring = recurring();
        CollectorPlugin collector = collector(List.of(account), List.of(transaction), List.of(recurring));
        String first = CollectorResultJson.toJson(collector);
        JsonObject json = JsonParser.parseString(first).getAsJsonObject();
        String stableAccount = java.util.UUID.nameUUIDFromBytes("account:account-id"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        String stableRecurring = java.util.UUID.nameUUIDFromBytes("recurringPayment:subscription-id"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        JsonObject savedAccount = json.getAsJsonArray("accounts").get(0).getAsJsonObject();
        JsonObject savedTransaction = json.getAsJsonArray("transactions").get(0).getAsJsonObject();
        JsonObject savedRecurring = json.getAsJsonArray("recurringPayments").get(0).getAsJsonObject();
        assertEquals(stableAccount, savedAccount.get("uuid").getAsString());
        assertEquals(stableRecurring, savedRecurring.get("uuid").getAsString());
        assertEquals(stableAccount, savedRecurring.get("accountUuid").getAsString());
        assertEquals(stableAccount, savedTransaction.get("accountUuid").getAsString());
        assertEquals(stableRecurring, savedTransaction.get("recurrentPaymentUuid").getAsString());
        assertEquals(stableRecurring, savedTransaction.getAsJsonObject("metadatas").get(stableAccount).getAsString());
        assertEquals("account-uuid", savedAccount.get("counterPartyUuid").getAsString());
        assertEquals("subscription-uuid", savedTransaction.get("counterPartyUuid").getAsString());
        assertEquals("account-uuid", account.getUuid());
        assertEquals("subscription-uuid", recurring.getUuid());
        assertEquals("account-uuid", transaction.getAccountUuid());
        File reference = temp.resolve("stable.json").toFile();
        CollectorResultJson.save(reference, collector);
        assertEquals(first, Files.readString(reference.toPath()));

        account.setUuid("different-account-uuid");
        account.setMetadata("nested-reference", "different-subscription-uuid");
        recurring.setUuid("different-subscription-uuid");
        recurring.setAccountUuid(account.getUuid());
        transaction.setAccountUuid(account.getUuid());
        transaction.setRecurrentPaymentUuid(recurring.getUuid());
        // Replace the test transaction to change the metadata key without mutating unmodifiable maps.
        TransactionDto offline = transaction();
        offline.setAccountUuid(account.getUuid());
        offline.setRecurrentPaymentUuid(recurring.getUuid());
        offline.setCounterPartyUuid("subscription-uuid");
        offline.setMetadata(account.getUuid(), recurring.getUuid());
        CollectorPlugin replay = collector(List.of(account), List.of(offline), List.of(recurring));
        assertEquals(first, CollectorResultJson.toJson(replay));
        CollectorResultJson.assertMatches(reference, replay);
        assertNotEquals(first, CollectorResultJson.toJson(replay, false));
        File raw = temp.resolve("raw.json").toFile();
        CollectorResultJson.save(raw, replay, false);
        CollectorResultJson.assertMatches(raw, replay, false);
        assertEquals(CollectorResultJson.toJson(replay, false), Files.readString(raw.toPath()));
    }

    @Test
    void stabilizationRequiresIdsAndRejectsConflictingUuidMappings() {
        AccountDto account = account();
        account.setId(null);
        assertThrows(IllegalArgumentException.class, () -> CollectorResultJson.toJson(List.of(account), List.of(), List.of()));
        assertDoesNotThrow(() -> CollectorResultJson.toJson(List.of(account), List.of(), List.of(), false));
        account.setId("account-id");
        AccountDto other = account();
        other.setId("other-id");
        assertThrows(IllegalArgumentException.class, () -> CollectorResultJson.toJson(List.of(account, other), List.of(), List.of()));
    }

    private AccountDto account() {
        AccountDto account = new AccountDto();
        account.setId("account-id");
        account.setUuid("account-uuid");
        account.setLabel("Compte été");
        account.setLastUpdate(new Date(123));
        return account;
    }

    private TransactionDto transaction() {
        TransactionDto transaction = new TransactionDto();
        transaction.setId("transaction-id");
        transaction.setAccountUuid("account-uuid");
        transaction.setRecurrentPaymentUuid("subscription-uuid");
        return transaction;
    }

    private RecurringPaymentDto recurring() {
        RecurringPaymentDto recurring = new RecurringPaymentDto("subscription-id", "subscription-uuid", "account-uuid");
        recurring.setExpectedAmount(9.99);
        return recurring;
    }

    private CollectorPlugin collector(List<AccountDto> accounts, List<TransactionDto> transactions,
                                      List<RecurringPaymentDto> recurringPayments) {
        return (CollectorPlugin) Proxy.newProxyInstance(CollectorPlugin.class.getClassLoader(),
                new Class<?>[]{CollectorPlugin.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getAccounts": return accounts;
                        case "getTransactions": return transactions;
                        case "getRecurringPayments": return recurringPayments;
                        default: throw new AssertionError("Unexpected collector call: " + method.getName());
                    }
                });
    }
}
