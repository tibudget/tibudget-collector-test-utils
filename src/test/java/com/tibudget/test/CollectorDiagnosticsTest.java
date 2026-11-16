package com.tibudget.test;

import com.google.gson.*;
import com.tibudget.dto.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CollectorDiagnosticsTest {
    @Test
    void validatesReferencesDuplicatesAndIdentityWithoutReportingValues() {
        JsonObject previous = result();
        JsonObject current = previous.deepCopy();
        JsonObject account = current.getAsJsonArray("accounts").get(0).getAsJsonObject();
        account.addProperty("uuid", UUID.randomUUID().toString());
        JsonArray transactions = current.getAsJsonArray("transactions");
        transactions.add(transactions.get(0).deepCopy());
        var findings = CollectorDiagnostics.validate(current, previous);
        assertTrue(findings.stream().anyMatch(finding -> finding.code().equals("uuid.changed")));
        assertTrue(findings.stream().anyMatch(finding -> finding.code().equals("id.duplicate")));
        assertFalse(new Gson().toJson(findings).contains("private-transaction-id"));
        assertTrue(findings.stream().allMatch(finding -> finding.documentation().contains("/0.66/")));
    }

    @Test
    void acceptsRefundsSameDateAndIdsScopedToDifferentAccounts() {
        JsonObject current = result();
        JsonObject transaction = current.getAsJsonArray("transactions").get(0).getAsJsonObject();
        transaction.addProperty("amount", 15);
        JsonObject secondAccount = current.getAsJsonArray("accounts").get(0).deepCopy().getAsJsonObject();
        secondAccount.addProperty("id", "second-account");
        secondAccount.addProperty("uuid", UUID.randomUUID().toString());
        current.getAsJsonArray("accounts").add(secondAccount);
        JsonObject second = transaction.deepCopy();
        second.addProperty("accountUuid", secondAccount.get("uuid").getAsString());
        current.getAsJsonArray("transactions").add(second);
        assertTrue(CollectorDiagnostics.validate(current, null).isEmpty());
    }

    @Test
    void detectsMissingLinksInvalidCurrencyAndOldestFirstWarnings() {
        JsonObject current = result();
        JsonObject first = current.getAsJsonArray("transactions").get(0).getAsJsonObject();
        first.addProperty("currencyCode", "INVALID");
        first.addProperty("recurrentPaymentUuid", UUID.randomUUID().toString());
        JsonObject earlier = first.deepCopy();
        earlier.addProperty("id", "earlier");
        earlier.addProperty("dateTransaction", "1969-01-01T00:00:00Z");
        current.getAsJsonArray("transactions").add(earlier);
        var findings = CollectorDiagnostics.validate(current, null);
        assertTrue(findings.stream().anyMatch(finding -> finding.code().equals("currency.invalid")));
        assertTrue(findings.stream().anyMatch(finding -> finding.code().equals("recurring.missing")));
        assertTrue(findings.stream().anyMatch(finding -> finding.code().equals("date.order") && finding.severity().equals("WARNING")));
        current.add("accounts", new JsonArray());
        assertTrue(CollectorDiagnostics.validate(current, null).stream().anyMatch(finding -> finding.code().equals("account.missing")));
    }

    @Test
    void reportsMalformedListsAndNullEntities() {
        JsonObject result = new JsonObject();
        result.add("accounts", new JsonArray());
        result.getAsJsonArray("accounts").add(JsonNull.INSTANCE);
        var findings = CollectorDiagnostics.validate(result, null);
        assertEquals(3, findings.size());
    }

    static JsonObject result() {
        AccountDto account = new AccountDto(AccountDto.AccountDtoType.SHOPPING, "Synthetic account", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "EUR", "Europe/Paris", 0);
        account.setId("private-account-id");
        TransactionDto transaction = new TransactionDto("private-transaction-id", account.getUuid(), TransactionDto.TransactionDtoType.PURCHASE, new Date(0), new Date(0), "Synthetic transaction", null, -15, "EUR");
        return JsonParser.parseString(CollectorResultJson.toJson(List.of(account), List.of(transaction), List.of(), false)).getAsJsonObject();
    }
}
