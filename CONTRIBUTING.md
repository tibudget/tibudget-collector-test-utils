# Contributing and releases

Read [AGENTS.md](AGENTS.md). Use Java 21 and run `mvn verify` before proposing a
change. Tests use synthetic responses and require no remote HTTP service.
The build creates the library, sources and Javadoc JARs under `target/`.

## Release with JitPack

JitPack builds and publishes an existing Git tag. This project does not deploy
to a separate Maven repository and does not need `distributionManagement`.
The Maven Release Plugin is configured to run `install`, rather than its default
`deploy` goal, during `release:perform`.

After committing the reviewed changes:

```sh
mvn release:prepare
mvn release:perform
```

`release:prepare` commits the release version, creates and pushes the tag, then
commits the next development version. `release:perform` checks out the release
tag and builds, tests and installs its artifacts locally. It does not itself
publish them on JitPack.

Request the tag's build on
[JitPack](https://jitpack.io/#tibudget/tibudget-collector-test-utils), then verify
its build log and consumer dependency resolution. Use the exact tag as the
consumer version, for example:

```xml
<dependency>
    <groupId>com.github.tibudget</groupId>
    <artifactId>tibudget-collector-test-utils</artifactId>
    <version>tibudget-collector-test-utils-0.8</version>
    <scope>test</scope>
</dependency>
```

The consumer must also declare the JitPack repository and its own compatible
Collector API dependency as described in the [README](README.md).

## Recover a failed release:perform

If preparation succeeded but `release:perform` failed because no deployment
repository was defined, keep the existing tag and `release.properties`. Retry:

```sh
mvn release:perform -Dgoals=install
```

The command-line override also works for tags created before the POM contained
the release plugin configuration. Do not rerun `release:prepare`, change an
existing public tag or roll back a successful preparation just to recover this
error. The JitPack build can already use the prepared tag independently of the
failed Maven deploy step.

Do not commit `release.properties`, `pom.xml.releaseBackup` or generated files.
Keep real cookies, credentials, settings and personal data out of test fixtures.
