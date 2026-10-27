# Development rules

These rules apply to humans and coding agents working in this repository.

## Scope and structure

- Use Java 21 and Maven. Do not add Gradle support.
- Keep the standard Maven layout: production code in `src/main/java`, tests in
  `src/test/java`, and resources in the matching `resources` directories.
- Keep the project simple. Add dependencies, frameworks, or abstractions only
  when a concrete requirement needs them.
- When implementation begins, use the Tibu Collector API documentation and
  verified API contracts. Do not guess API behavior.
- Preserve existing work and the existing license file.

## Code and documentation

- Write code for human maintainers first. The code must be easy to understand for developers who may not be fluent in
  English and may not know the whole project.
- Preserve existing comments and Javadoc unless they are outdated, inaccurate, or no longer reflect the actual behavior of the code.
- Use term 'collect', not 'collection'
- Use simple English in names, comments, Javadoc, validation messages and documentation. Avoid uncommon words,
  abbreviations and unnecessarily technical wording when a simpler formulation exists.
- Prefer explicit and straightforward code over clever abstractions, compact tricks or complex constructs.
- Use clear and descriptive names. A developer should understand the purpose of a variable, method or class without
  needing to inspect unrelated parts of the project.
- Keep methods easy to read as a sequence of logical steps. When a method performs several distinct operations, make
  these steps visually clear.
- Use short comments to introduce important steps inside a method when this makes the execution flow easier to
  understand. Comments should explain the purpose of the step, not repeat what the following code already says.
- Keep related statements together and use blank lines to separate logical steps. Do not add blank lines between
  statements that belong to the same operation.
- Avoid unnecessary line breaks. The IDE line width is 200 characters, so short and reasonably sized signatures, method
  calls, conditions and expressions should usually stay on one line.
- Break a long statement when separating it makes its structure or the different steps easier to understand. Do not
  mechanically wrap arguments, chained calls or expressions just because they could be split.
- Follow the formatting style of the surrounding code when it does not conflict with these readability rules.
- Javadoc and documentation for classes and methods must use a multi-line format, even when the documentation is short.
- In Javadoc, use `{@link ...}` when referring to a class, method, field, or constant. Link to public getters when DTO fields are private.
- Keep Javadoc short and use simple English for readers who are not native English speakers. Explain what to implement in this collector.
- Link to the API and DTO documentation for field rules instead of repeating them here. Keep only essential guidance, such as stable IDs and preserving existing UUIDs.
- Comments, Javadoc and validation messages must be written in English.
- Explain non-obvious behavior, business rules, assumptions and important implementation decisions. Do not add comments
  that simply restate the code.
- Keep changes scoped to the request. Preserve unrelated local changes and existing behavior unless changing them is
  explicitly required.
- Do not introduce new dependencies, abstractions or broad refactors without a concrete need for the requested change.
- Do not split code into additional methods or classes only to make methods shorter. Extract code when the resulting
  method has a clear responsibility, is reusable, or makes the main execution flow significantly easier to understand.
- Prefer a slightly longer but linear and explicit implementation over a shorter implementation that requires more
  knowledge of the project to understand.
- Use constructor injection for new dependencies.
- Keep controllers focused on HTTP binding, authorization and responses. Put business logic in services and database
  queries in repositories.
- Keep changes scoped to the request and preserve unrelated local changes. Do not introduce dependencies or broad
  refactors without a concrete need.
- Keep credentials and personal data out of source code, resources, logs, and
  committed test data.
- Keep the README and `docs/` accurate as the project grows.

## Verification

- Run `mvn verify` after build or code changes.
- Add focused tests for new behavior when implementation begins. Do not add
  placeholder tests just to make the empty starter appear tested.
- Report what changed, what was verified, and any checks that could not run.
