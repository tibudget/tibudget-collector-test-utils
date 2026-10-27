# Collector scenarios

A scenario contains the HTTP responses needed by a collect and a reference of
the resulting DTOs. Use the [Collector API documentation](https://github.com/tibudget/tibudget-api)
for lifecycle and field rules. Keep stable IDs and preserve existing UUIDs in
collector code; JSON normalization is only a test convenience.

## Record a scenario

1. Create a collector instance with known initial settings, previous accounts
   and previous recurring payments. Keep these inputs separate from outputs.
2. Create an `OkHttpInternetProvider` with `harDir` enabled. Add `htmlDir` if you
   want readable pages while developing. Configure cookies only when needed.
3. Pass the provider and your other test providers to `CollectorPlugin.init`.
4. Run `collect()`. Handle validation and authentication as your collector requires.
5. Save the result in the HAR session directory. Review and redact the scenario
   before copying it into committed test resources.

The following fragment assumes `collector`, `counterpartyProvider`,
`initialSettings`, `previousAccounts` and `previousRecurringPayments` are your
collector-specific fixtures:

```java
import com.tibudget.test.CollectorResultJson;
import com.tibudget.test.OkHttpInternetProvider;
import com.tibudget.test.OTPProviderConsole;
import com.tibudget.test.PDFToolsProviderPDFBox;
import java.io.File;

var internet = new OkHttpInternetProvider()
        .harDir("target/scenarios")
        .htmlDir("target/html")
        .cookiesFile("target/local/cookies.bin");

collector.init(internet, counterpartyProvider, new OTPProviderConsole(),
        new PDFToolsProviderPDFBox(), initialSettings, previousAccounts, previousRecurringPayments);
collector.collect();

File session = internet.getHarDir();
CollectorResultJson.save(new File(session, "collect-result.json"), collector);
```

A session might look like this:

```text
target/scenarios/2026-10-27_14-30-00_123/
  0001_get_example.test_login.har
  0002_post_example.test_login.har
  0003_get_example.test_accounts.har
  0004_dl_example.test_invoice.har
  collect-result.json
```

Copy the reviewed directory to a stable path such as
`src/test/resources/scenarios/first-collect`. Do not keep the timestamp in test
code. Preserve the initial fixture data used for that run; `getSettings()` after
the collect may contain progress that would make a replay skip recorded requests.

This library is compiled and tested against API 0.65, where `collect()` returns
`void`. Later API versions may add a resume/completion protocol. When testing
such a collector, follow that API's documented lifecycle and gather the intended
results before saving the reference.

## Replay in a JUnit test

Use a fresh collector and fresh copies of the same initial fixtures. The
following is the body of a collector-specific test method; the named fixtures
and the collector instance are supplied by your test:

```java
var scenario = new java.io.File("src/test/resources/scenarios/first-collect");
var internet = new com.tibudget.test.OkHttpInternetProvider()
        .replayDir(scenario)
        .downloadDir(temporaryDirectory.toFile()); // A JUnit @TempDir Path.

com.tibudget.api.OTPProvider otp = (channel, keyword, pattern, prompt) -> "123456";
collector.init(internet, counterpartyProvider, otp,
        new com.tibudget.test.PDFToolsProviderPDFBox(), initialSettings,
        previousAccounts, previousRecurringPayments);
collector.collect();

com.tibudget.test.CollectorResultJson.assertMatches(
        new java.io.File(scenario, "collect-result.json"), collector);
internet.assertReplayComplete();
```

Use the synthetic OTP your collector expects. Test counterparty providers should
return fixed UUIDs locally. Replay only replaces calls made through this
`InternetProvider`; it does not block network access from other code. All test
providers must therefore be offline as well.

## Make the test repeatable

- Use the same initial settings, accounts, recurring payments and OTP values.
  Make fresh copies when the collector changes those inputs.
- Keep business dates fixed. If the collector uses the current date or time,
  provide a clock in collector code or prepare an equivalent test fixture.
  The JSON helper does not remove `lastUpdate` or other timestamps.
- Preserve result list order, including nested payments and items. JSON object
  keys are sorted, but list order is compared.
- UUID normalization leaves counterparty UUIDs and entity IDs untouched. Use
  stable counterparty fixtures. To test the original UUIDs, use
  `save(file, collector, false)` and `assertMatches(file, collector, false)`.
- Keep result-type IDs unambiguous. The normalization key is the type and ID;
  it does not include the parent account. If recurring-payment IDs are reused
  across accounts, use stable fixture UUIDs and disable normalization.
- Downloads are rebuilt as temporary files. Result JSON compares their bytes,
  not their paths. Callers own these files and should use JUnit `@TempDir` or
  remove them after the test.

## HTTP matching and format limits

Each provider call creates one HAR 1.2 file containing one entry. The filename
starts with the call index (`0001`, `0002`, ...), followed by `get`, `post` or
`dl` and a normalized URL. Long URLs use a shortened filename plus a stable
hash. Replay also checks the exact URL and HTTP method inside the HAR, so
filename normalization cannot hide an URL change. Query parameter order is
significant. A download has its own `dl` filename marker and HTTP method `GET`.

An unexpected call, missing file, invalid HAR or unsupported content encoding
throws `IOException`. Replay never falls back to the network. Call
`assertReplayComplete()` after the collect to detect a scenario that now makes
fewer HTTP calls. Extra JSON files such as `collect-result.json` are ignored.

Request headers and POST bodies are recorded for inspection, but replay does
not compare them. Thus this is a regression test of the recorded call sequence
and parsed results, not a complete verification of outgoing payloads. Check
request construction separately when it matters.

The HAR files capture the response returned to the provider after OkHttp handles
redirects and decompression. They are not a browser's complete wire capture:
redirect hops are not separate entries, timings are placeholders, cookie arrays
are empty and response headers have one value per name, matching the Tibu API.
The final provider location is preserved in the HAR extension `_location`.
Do not expect arbitrary browser-exported HAR files to be replayable without
converting them to this one-file-per-call layout.

HAR response content is base64. Text bodies use UTF-8 after OkHttp decodes them;
download bytes are preserved. The status, message, headers and provider location
are restored. The forced download content type controls the recreated extension.
The per-provider rate limiter applies to live GET, POST and download calls;
redirects inside one call are managed by OkHttp. Replay never waits on it.

HTML is for debugging: it contains the text response, or a download's local
path. Legacy HTML-only scenarios can be replayed with an assumed HTTP 200,
`OK`, `http/1.1`, the requested location and no response headers. They cannot
check exact URLs inside a recording, and their downloaded files must still
exist at the recorded paths. Prefer HAR for portable scenarios.

Recording failures throw `IOException` instead of silently leaving an incomplete
scenario. If HTTP succeeds but writing fails, fix the output path before
recording again. Do not treat a partly written session as a valid reference.
