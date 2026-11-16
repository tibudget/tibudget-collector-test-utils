package com.tibudget.test;

import com.google.gson.*;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CollectorResultDiffTest {
    @Test
    void matchesEntitiesByIdAndReportsChangedFieldsWithoutValues() {
        JsonObject expected = CollectorDiagnosticsTest.result();
        JsonObject actual = expected.deepCopy();
        JsonObject another = actual.getAsJsonArray("transactions").get(0).getAsJsonObject().deepCopy();
        another.addProperty("id", "second-private-id");
        expected.getAsJsonArray("transactions").add(another);
        actual.getAsJsonArray("transactions").asList().add(0, another.deepCopy());
        assertTrue(CollectorResultDiff.compare(expected, actual, Set.of()).isEmpty());
        actual.getAsJsonArray("transactions").get(1).getAsJsonObject().addProperty("amount", -42);
        var differences = CollectorResultDiff.compare(expected, actual, Set.of());
        assertEquals(1, differences.size());
        assertEquals("/transactions/0/amount", differences.getFirst().path());
        assertTrue(CollectorResultDiff.compare(expected, actual, Set.of("/transactions/0/amount")).isEmpty());
        assertFalse(new Gson().toJson(differences).contains("private"));
    }

    @Test
    void stabilizesIdsScopedToAccountsAndKeepsExistingSnapshotNamesForUniqueIds() {
        JsonObject source = CollectorDiagnosticsTest.result();
        JsonObject normalized = CollectorResultDiff.stabilizeUuids(source);
        assertNotEquals(source.getAsJsonArray("accounts").get(0), normalized.getAsJsonArray("accounts").get(0));
        JsonObject secondAccount = source.getAsJsonArray("accounts").get(0).deepCopy().getAsJsonObject();
        secondAccount.addProperty("uuid", java.util.UUID.randomUUID().toString());
        secondAccount.addProperty("counterpartyUuid", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        source.getAsJsonArray("accounts").add(secondAccount);
        for (JsonElement account : source.getAsJsonArray("accounts")) {
            JsonObject payment = new JsonObject();
            payment.addProperty("id", "same-payment-id");
            payment.addProperty("uuid", java.util.UUID.randomUUID().toString());
            payment.add("accountUuid", account.getAsJsonObject().get("uuid"));
            source.getAsJsonArray("recurringPayments").add(payment);
        }
        JsonObject result = CollectorResultDiff.stabilizeUuids(source);
        assertNotEquals(result.getAsJsonArray("accounts").get(0).getAsJsonObject().get("uuid"), result.getAsJsonArray("accounts").get(1).getAsJsonObject().get("uuid"));
        assertNotEquals(result.getAsJsonArray("recurringPayments").get(0).getAsJsonObject().get("uuid"), result.getAsJsonArray("recurringPayments").get(1).getAsJsonObject().get("uuid"));
        assertEquals(result.getAsJsonArray("accounts").get(1).getAsJsonObject().get("uuid"), result.getAsJsonArray("recurringPayments").get(1).getAsJsonObject().get("accountUuid"));
        assertEquals(source.getAsJsonArray("accounts").get(0).getAsJsonObject().get("id"), result.getAsJsonArray("accounts").get(0).getAsJsonObject().get("id"));
    }

    @Test
    void reportsAddedRemovedDuplicateAndUuidChanges() {
        JsonObject expected = CollectorDiagnosticsTest.result();
        JsonObject actual = expected.deepCopy();
        actual.getAsJsonArray("accounts").get(0).getAsJsonObject().addProperty("uuid", "changed");
        actual.getAsJsonArray("transactions").get(0).getAsJsonObject().addProperty("id", "new-private-id");
        actual.getAsJsonArray("transactions").add(actual.getAsJsonArray("transactions").get(0).deepCopy());
        var differences = CollectorResultDiff.compare(expected, actual, Set.of());
        assertTrue(differences.stream().anyMatch(difference -> difference.path().equals("/accounts/0/uuid")));
        assertTrue(differences.stream().anyMatch(difference -> difference.kind().equals("ADDED")));
        assertTrue(differences.stream().anyMatch(difference -> difference.kind().equals("REMOVED")));
        assertTrue(differences.stream().anyMatch(difference -> difference.kind().equals("DUPLICATE_ID")));
    }
}
