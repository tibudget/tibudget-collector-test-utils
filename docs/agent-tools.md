# Automated collector checks

Use Java 21 and the collector's **test classpath**. The tools use the existing
`InternetProvider`, HAR replay, PDF provider, input setters and simulator state.
No JavaFX window is created in headless mode. The desktop launcher keeps its
existing behavior when no `--headless` argument is supplied.

## Launch

After `mvn install` in test-utils, prepare the classpath in your collector project:

```sh
mvn test-compile dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=target/agent-classpath.txt
java -cp "target/test-classes:target/classes:$(cat target/agent-classpath.txt)" com.tibudget.test.simulator.CollectorCli help
```

Use the same `java -cp ...` prefix for the commands below. Alternatively, use
`com.tibudget.test.simulator.TibuSimulator --headless <command> ...` with that
classpath. The classpath separator is `;` on Windows.

Exactly one `CollectorPlugin` service must be present. `--collector` selects a
public implementation with a public no-argument constructor when needed.
`describe` lists annotated input names, types, required flags and enum choices,
without displaying default values. OpenID authentication is not supported.

```text
describe --collector com.example.MyCollector
replay --scenario src/test/resources/scenarios/example --inputs /private/inputs.json --workspace target/check-example --fresh
run --saved-inputs --record --workspace target/manual-check
stop --workspace target/manual-check
```

`run` permits live network access; `replay` and `resume-check` use offline
recordings and never fall back to live HTTP when a recording is missing.
The provider does not sandbox unrelated network clients a collector might create;
collectors must still follow the API requirement to use `InternetProvider`.
`stop` requests a graceful stop in another running headless process. It does not
kill the process. The collector must still finish its first transaction and save
progress according to the API contract.

The default workspace is `target/agent-simulator/<collector-class>/`, separate
from the desktop simulator. It contains a lock preventing concurrent headless
collects. Use the same workspace to resume state and cookies. `--fresh` resets
that session, including cookies, while keeping externally saved input settings.
Use a new workspace for independent tests. Do not point it at untrusted cookie
files: cookie persistence uses Java serialization.

## Inputs and local files

Choose at most one source:

- `--inputs /private/inputs.json`: a JSON object keyed by `@Input` field name.
- `--inputs-env VARIABLE_NAME`: the same JSON object in an environment variable.
- `--saved-inputs`: load the desktop simulator's encrypted settings for this class.

Without a source, input defaults are used. Unknown input names fail rather than
silently using a misspelled parameter. Public setters and `validate()` check the
submitted values. Missing values also depend on the collector's validation;
conditional `@Input` presentation rules are not interpreted by the CLI.

`--otp-env VARIABLE_NAME` supplies a JSON array of codes in request order, for
example `["123456"]` for a synthetic test. Every code is requested through the
supplied `OTPProvider`, with a required channel and the collector's code pattern.
There is no interactive prompt: missing codes or a pattern mismatch fail the run.
`--counterparties /private/counterparties.json` supplies exact lookup fixtures:

```json
{"tibu:id:fr:siret":{"synthetic-value":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"}}
```

Numeric lookup keys use Java's `Double.toString` representation. Unmapped lookups
return null. This is a local fixture provider, not the official counterparty
registry; the fixture author must use validated unique metadata keys.

Each run writes:

| File | Purpose |
| --- | --- |
| `report.json` | Status, progress, request count, error type and validation findings |
| `result.json` | Result snapshot with stable UUIDs for reference comparisons |
| `result-raw.json` | Result snapshot with original UUIDs for identity checks |
| `state.json` | Settings, gathered DTOs and identity used by the next collect |
| `cookies.bin` | Local cookies |
| `diagnostics.log` | First 1 MiB of collector output and logs, with known input strings and codes masked |
| `downloads/` | Collected files |
| `scenarios/<dated-directory>/` | HAR files when `run --record` is used |

Reports omit field values and exception messages. Logs are best-effort redacted,
not a guarantee that all personal data is removed. State, cookies, snapshots,
downloads and HAR files are **private, unencrypted artifacts**. Snapshots include
attached file contents as base64. Review local files before sharing or committing.
The CLI does not save input files into encrypted settings or modify the desktop
settings. Its state is compatible with the simulator's state format.

`--timeout-seconds` defaults to 300 per collect. At the deadline, the tool requests
stop and waits up to one additional second. A timed-out collect has exit code 124,
no new result snapshot, and only saves state if the worker has actually finished.
The command-line process then exits even if the collector ignores stop. A timeout
is not a successful graceful-stop test. Java callers of `execute` must not keep
running further collects after a timeout; use separate JVMs for isolation.

## Validate and compare

```text
validate --actual target/check-example/result-raw.json
validate --actual target/current/result-raw.json --previous target/previous/result-raw.json
compare --expected src/test/resources/expected.json --actual target/check-example/result.json
replay --scenario src/test/resources/scenarios/example --inputs /private/inputs.json --fresh --expect src/test/resources/expected.json
```

Validation uses the [API 0.66 contracts](https://github.com/tibudget/tibudget-api/tree/0.66/src/main/java/com/tibudget).
Findings contain severity, rule code, JSON path, a plain-English explanation and
a link to the source contract. Checks cover identifiers, duplicate keys, preserved
UUIDs, account/recurring links, currency codes, numeric values, recurrence
intervals, progress, text limits and transaction order. Transaction dates out of
order are warnings because an older transaction may intentionally be updated.
Positive purchases/payments can be refunds, so their sign alone is not an error.
Business rules that require knowledge of a site still belong in its tests.
This is not an exhaustive implementation of every DTO rule.

Compare matches accounts by counterparty and ID, transactions/recurring payments
by account UUID and ID. It reports additions, removals, duplicates and changed
field paths without values. Metadata differences are reported at the metadata
map, since arbitrary metadata keys can themselves contain personal information.
Array order at the entity level is ignored; nested array order is preserved.
Use raw snapshots to compare original UUIDs across successive collects.
Use stable snapshots to compare independent fresh sessions. Do not mix formats.
The stable form matches `CollectorResultJson` for unique IDs and also handles
IDs reused across different accounts or counterparties. It does not change the
existing `CollectorResultJson` behavior or stored references.

No field is silently ignored. For known variable fields, use explicit pointers:

```text
compare --expected expected.json --actual actual.json --ignore /accounts/0/lastUpdate,/accounts/1/lastUpdate
```

Paths refer to the expected array for changed/removed entities and the actual
array for additions. Review differences before replacing a reference. The tools
never update the reference automatically.

## Offline failures and stop/resume

Headless replay requires consecutive numbered HAR files, starting at 1, with one
HAR 1.2 entry per request. The provider's existing HTML replay remains available
through its Java API. CLI replay checks method, URL and POST body by default.
`--ignore-body` opts out of the body check for dynamic tokens; add site-specific
assertions when doing so. Headers and POST content types are not matched.
Mismatch reports identify the request number and the differing part, without
printing URL parameters, credentials or request bodies.

```text
replay --scenario scenarios/example --inputs /private/inputs.json --workspace target/stopped --fresh --stop-after 12
replay --scenario scenarios/example --inputs /private/inputs.json --workspace target/failed --fresh --fail-at 12
resume-check --scenario scenarios/example --inputs /private/inputs.json --workspace target/resume-suite
```

`--stop-after N` delivers response N normally and then calls `collector.stop()`.
The collector may need later responses to finish its first transaction.
`--fail-at N` throws an IOException once before reading offline response N; the
same provider instance can read it on retry. It does not perform a network call.
For retries across separate CLI processes, supply a continuation scenario
recorded for the resumed session rather than restarting an unrelated HAR stream.

`resume-check` requires a **new** workspace. It runs a complete baseline, then
starts a fresh session for each request boundary, requests stop and resumes in
new collector instances. It checks original UUID preservation before merging
state and compares gathered results with the uninterrupted baseline. Duplicate
transactions in an individual run are checked before merging can hide them.
`--max-runs` limits resume attempts per boundary (default 10). A run that remains
paused, fails, times out or differs from the baseline fails verification.
Timeouts stop the suite. Baseline failure also stops it immediately.

By default the interrupted run and its continuations share the same replay
cursor. This only works when resuming continues the recorded request sequence.
Many collectors repeat login or navigation on resume. Supply the actual resumed
traffic in separate scenarios with `--continuations /private/continuations`:

```text
continuations/
  1/1/0001_get_...har  # First resumed run after stopping at request 1
  1/2/0001_get_...har  # Second resumed run, if still paused
  2/1/0001_get_...har  # First resumed run after stopping at request 2
```

Each continuation starts numbering at one. Missing continuations are reported;
the tool does not pretend they are a collector defect. OTP codes advance across
runs within one stop/resume trial, and reset for the next independent trial.
This suite verifies fresh-session interruptions. To check updates to previously
known data, replay changed scenarios successively in the same workspace without
`--fresh`, then validate and compare the raw results.

## Derive scenarios and review sensitive content

```text
copy --scenario scenarios/original --destination target/scenarios/expired --plan /private/expired-plan.json
audit --scenario target/scenarios/expired
```

Copy creates a **new directory** containing only HAR files; it never edits the
source or copies cookies, inputs, snapshots or settings. A plan supports literal
replacements and per-request response changes. For example:

```json
{
  "replacements": {
    "original@example.test": "fixture@example.test",
    "original-session": "fixture-session"
  },
  "responses": {
    "2": {
      "status": 401,
      "statusText": "Unauthorized",
      "body": "Session expired",
      "mimeType": "text/plain",
      "headers": []
    }
  }
}
```

Response overrides accept `status`, `statusText`, `body`, `mimeType`, `headers`
(a HAR array of name/value pairs), and `location`. Use these to construct fixtures
for changed amounts/statuses, late transactions, same-date ordering, missing
files, OTP pages, expired sessions, HTTP 429 and temporary server errors. Response
bodies are site-specific; the tool cannot invent a valid page for an unknown site.
Keep response headers consistent with the modified body and status.

Literal replacements apply to JSON keys and string values, including request
URLs, headers and decoded base64 text payloads. Replacement order is longest
source first. Add explicit variants for URL/form-encoded values when necessary.
Binary payloads are copied unchanged and flagged for manual review. HAR filenames
are regenerated from the resulting request URLs, including long-URL hashes, so
the copied scenario can still be replayed. Use matching synthetic collector
inputs and OTP fixtures, then verify the copied scenario offline.

Audit flags sensitive field names, common email/token/IBAN patterns and binary
content. Its report identifies request numbers and locations without exposing
suspicious values. It cannot detect all names, addresses, identifiers, secrets or
personal data embedded in documents. A clean audit is **not approval to publish**.
Review the files manually and run replay before sharing a replacement copy.

## JSON output and exit codes

Commands print one JSON object with `schemaVersion: 1`; collector console output
is captured separately. `--report path` writes the same report to a file. Collect
commands default to `<workspace>/report.json`; other commands only write a report
file when requested. `help` lists the available commands.

| Code | Meaning |
| --- | --- |
| 0 | Complete and valid, equal comparison, or successful utility command |
| 2 | Collect paused or stopped gracefully |
| 3 | Validation error, result difference or failed resume verification |
| 4 | Execution, configuration-file or I/O failure |
| 5 | Audit found content requiring review |
| 64 | Invalid command or command-line options |
| 124 | Collect timeout |

Warnings alone do not fail validation. `copy` returns 0 when the copy succeeds,
with review findings in its report; this does not certify that the copy is safe.
