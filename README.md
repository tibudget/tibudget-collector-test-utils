# Tibu Collector Test Utils

Java tools for developing and testing [Tibu collectors](https://github.com/tibudget/tibudget-api).
Record a real HTTP scenario once, replay it without a network connection, and compare
accounts, transactions and recurring payments with a JSON reference.

This library runs in your development and test environment. Add it with Maven
`test` scope so it is not included in the collector delivered to the application.
It does not reproduce the application's sandbox or domain restrictions.

## Requirements and installation

Use **Java 21** and **Maven 3.9 or later**. The build uses the public
`com.github.tibudget:tibudget-api:0.65` API with `provided` scope. Your collector
must declare its own compatible API dependency; do not add two copies of the API
under different Maven group IDs.

For JitPack, add this repository and dependency to your collector's `pom.xml`:

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.github.tibudget</groupId>
        <artifactId>tibudget-collector-test-utils</artifactId>
        <version>GIT_TAG_OR_COMMIT</version>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>5.13.4</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

Replace `GIT_TAG_OR_COMMIT` with a tag or commit containing these changes.
`0.8-SNAPSHOT` is the current source version, not a promise that a JitPack release
already exists. To try a local build first, run `mvn install` here and use
`com.tibudget:tibudget-collector-test-utils:0.8-SNAPSHOT` in your collector.

## Record and replay

Configure one provider per scenario, before the first request. Requests in a
scenario must run in sequence. A custom `OkHttpClient` is optional.

```java
import com.tibudget.test.OkHttpInternetProvider;

var recording = new OkHttpInternetProvider()
        .htmlDir("target/http-html")
        .harDir("target/http-har")
        .downloadDir("target/downloads")
        .cookiesFile("target/local/cookies.bin")
        .rateLimiter(2); // Requests per second.

// Pass recording to your collector's init(...), then run collect().
// The getter returns the actual dated session directory.
var scenarioDirectory = recording.getHarDir();

var replay = new OkHttpInternetProvider().replayDir(scenarioDirectory);
// Initialize a fresh collector with replay, then run collect().
replay.assertReplayComplete();
```

The second collect uses recorded responses, without network requests or rate
limiting. `assertReplayComplete()` fails if recorded requests remain unused.
See [the scenario guide](docs/scenarios.md) for the complete record/test workflow,
initial settings, result checks and replay limits.

| Configuration | Behavior |
| --- | --- |
| `htmlDir(File or String)` | Save readable response bodies for debugging. |
| `harDir(File or String)` | Save one HAR 1.2 file per provider call, including downloads. |
| `downloadDir(File or String)` | Set the download output directory; default: `target/downloads`. |
| `cookiesFile(File or String)` | Install a persistent `AdvancedCookieJar`; create its parent directory. |
| `replayDir(File or String)` | Read an existing session directory and skip all network calls. |
| `rateLimiter(double or RateLimiter)` | Set requests per second, or share a limiter across providers; default: 2. |
| `okHttpClient(OkHttpClient)` | Replace the client; set it before `cookiesFile`. A constructor also accepts a client. |

Recording is disabled unless an HTML or HAR base directory is set.
Each base gets a `yyyy-MM-dd_HH-mm-ss_SSS` session subdirectory. If that name
exists, `_1`, `_2`, etc. is added. The same provider reuses its session when
HTML and HAR have the same base. With different bases, suffixes are chosen
independently. There is no UUID in directory names.

`getHtmlDir()` and `getHarDir()` return the actual session directories.
`getDownloadDir()`, `getCookiesFile()` and `getReplayDir()` return the configured
paths. Unset optional paths are `null`. Passing `null` to `htmlDir` or `harDir`
disables that format. Setting `replayDir` takes priority over recording.

The default client follows redirects and keeps cookies in memory. A supplied
client keeps its own options and cookie jar. `cookiesFile` replaces that jar on
a copy of the client's configuration. Replacing the client later clears the
provider's cookies-file setting.

## Compare collect results

```java
import com.tibudget.test.CollectorResultJson;
import java.io.File;

File reference = new File(recording.getHarDir(), "collect-result.json");

// After the real collect, when creating or updating the scenario:
CollectorResultJson.save(reference, collector);

// After the offline collect, in the automated test:
CollectorResultJson.assertMatches(reference, offlineCollector);
replay.assertReplayComplete();

// Get JSON without writing a file, or keep the original UUIDs:
String json = CollectorResultJson.toJson(collector);
String rawJson = CollectorResultJson.toJson(collector, false);
```

The reference contains `accounts`, `transactions` and `recurringPayments`, with
nested DTOs, sorted object keys, UTC dates and explicit null values. List order
is preserved. Attached files are included as base64 content, so temporary paths
can change between runs. Reading a missing attachment fails serialization.

By default, account UUIDs use `UUID.nameUUIDFromBytes` with UTF-8 `account:<id>`;
recurring-payment UUIDs use `recurringPayment:<id>`. A shared ID such as `4`
therefore produces different UUIDs for these two types. Exact references to the
old UUIDs are replaced throughout the JSON, including metadata keys and values.
Stable `id` fields and `counterpartyUuid` fields are preserved. DTOs are never
modified. Missing/blank IDs or conflicting mappings for one old UUID fail with
`IllegalArgumentException`. IDs must identify entities unambiguously within
each result type, including recurring payments across accounts, when using this
ID-only normalization.

`save` and `assertMatches` use the same normalization; both also accept a final
`boolean` to disable it. `assertMatches` ignores JSON whitespace and object key
order, but reports different data as a JUnit assertion failure. It never creates
or updates the reference. Settings, credentials and collector configuration are
not included. Dates, counterparty UUIDs and all other business data still need
to be repeatable; see [the scenario guide](docs/scenarios.md#make-the-test-repeatable).

## Other tools

| Class | Purpose |
| --- | --- |
| `AdvancedCookieJar` | In-memory cookies with optional local file persistence. `clear()` also saves the empty jar. |
| `StringMapSerializer` | Save/load collector settings as UTF-8 JSON. Missing, unreadable or malformed files load as an empty map. |
| `OTPProviderConsole` | Read an OTP interactively during manual development. Use a fixed provider in automated tests. |
| `PDFToolsProviderPDFBox` | Extract text from PDFs with PDFBox; does not provide OCR for scanned images. |

HAR, HTML, cookie files, result JSON and settings may contain personal data or
login tokens. Use synthetic or redacted test data before committing a scenario.
Cookie files use Java serialization: only load files you created and trust.

## Build and contribute

```sh
mvn verify
```

The tests use synthetic data and do not require a bank account, credentials or a
running HTTP service. Dependency downloads still need network access on a fresh
machine. The build produces the library JAR, sources JAR and Javadoc JAR under
`target/`. See [contributing and release instructions](CONTRIBUTING.md).

Licensed under the [MIT License](LICENSE).
