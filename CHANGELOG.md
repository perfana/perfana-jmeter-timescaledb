# Changelog

## [Unreleased]

### Added
- `responseBodyMaxLength` (default 16384) truncates the error response body before it is buffered. Bodies are collected for failed samples only, so they cost nothing until the system under test starts failing — and then every failure carries a full, untruncated body. The head is kept, since that is where the stack trace or error page is, and the marker says how much was dropped.

### Fixed
- Backpressure now also trips on buffered response-body bytes (64 MB), not only on a record count. 50 000 buffered records mean something very different with bodies attached than without, so the count alone did not bound the footprint.
- A buffer whose flushes keep failing is capped at 100 000 records; past that the oldest are dropped with an error line naming the count. Failed records were re-added unconditionally, so a database that stayed down grew the buffer until the JVM ran out of heap.

### Notes
- Plan path capture (`saveSourceElementPath`) was profiled against a T_Start-shaped plan on WireMock: +1.6% CPU with capture on, within run-to-run noise, and the same for the pre-cache build. The per-path JSON cache holds a flat live-object count across a run. A connection-endpoint leak observed during that work lives in the engine's async HTTP client and is independent of this plugin.

## [1.3.1] - 2026-09-02

### Changed
- The test plan path JSON is built once per distinct path instead of once per sample. Capturing it previously cost `1 + 3N` reflective reads and a fresh JSON string on every request (`N` = path depth); it is now a single read plus a hash lookup returning a shared string. BreakTest's path entries are records, so equal paths from different threads share one cache entry and the cache is bounded by the number of samplers in the plan. The engine side was already cached at compile time and is unchanged.

## [1.3.0] - 2026-09-02

### Changed
- Test plan path capture (`requests_raw.source_element_path`) is now **opt-in**: set `saveSourceElementPath=true` to enable it. Previously it was written whenever the column existed. With the toggle off the column is omitted from the insert and the listener does not ask the engine for the path.

### Fixed
- Query parameters whose `=` is percent-encoded (`encquery%3d<token>`) are now masked during URL normalization. They previously looked valueless and were stored verbatim, along with the extra pairs hidden behind `%20`/`%26`.

## [1.2.0] - 2026-08-19

### Added
- `requests_raw.source_element_path` records where in the test plan a request came from: the elements from the Thread Group down to the sampler, outermost first, as `{name, class, occurrence}`. `occurrence` numbers identically named siblings of the same class, so two `checkout` transactions in different branches of a plan stop producing indistinguishable rows — nothing else on a request row can tell them apart.
- The listener now implements BreakTest's `SampleResultMetadataConsumer` contract: it asks for the plan path only when the `source_element_path` column exists, and for the thread variables only when session-variable capture is active, so an engine prepares nothing for a run that does not store it. Both accessors are read reflectively, so the plugin still compiles against stock Apache JMeter and writes NULL on engines without the metadata.

### Changed
- Session variables now come from the engine when it attaches them (BreakTest stamps the same snapshot on every sub-result), instead of the listener snapshotting on the sampler thread and carrying the map across by result identity. The failing leaf carries its own session state, so no parent chain has to be walked and nothing is lost when that chain is broken under load. The sampler-thread path stays as the fallback for engines without the metadata.

### Notes
- Requires Java 17 or newer on the JMeter JVM (unchanged since 1.0.0, now documented in the README): the plugin's classes are compiled for Java 17, so an older JVM fails at class load. Verified against stock Apache JMeter 5.6.3 — every column the engine can fill is written and `source_element_path` stays NULL, with the reason logged once at startup.
- Replaces the `parent_controllers` proposal from the previous unreleased revision, which stored the *runtime* controller chain (loop pass, foreach element, per-pass id for Parallel Controllers). That engine-side design was rejected by the BreakTest maintainer in favour of the metadata mechanism above, so per-pass parallel timing is not available: the plan path is static and describes the plan, not the run.
- The `source_element_path` column DDL is owned by the Perfana repo; `migrations/V004__add_source_element_path.sql` here is a dev/test mirror (see `docs/source-element-path-schema-writeup.md`). If the column is absent the listener logs once and omits it from the insert rather than failing, so an un-migrated database is unaffected.

## [1.1.0] - 2026-08-16

### Changed
- **Breaking:** session variable capture is now opt-in per variable. `sessionVariablesExclude` (a deny-list of secret-ish names) is replaced by `sessionVariablesInclude`, an allow-list of the names to store — a variable nobody asked for is never persisted, so a session holding an unexpected token or personal detail cannot leak into the database by an omission. The allow-list supports `*` wildcards (`cartId,order_*,*Id`), matches case-insensitively, and must match the whole name.
- `sessionVariablesInclude` defaults to empty, which captures nothing. A run with `saveSessionVariables=true` and no allow-list logs a warning at startup and skips snapshotting entirely.
- Migration: replace `-JsessionVariablesExclude=…` with `-JsessionVariablesInclude=…` listing the variables worth debugging with. The old property is ignored. `*` restores capture-everything behaviour, minus JMeter internals — and minus the secret-name protection the deny-list used to give, so prefer explicit names.
- JMeter's built-in/internal variables (`__*`, `JMeterThread.*`, `START.*`, `TESTSTART.MS`) are still always excluded, now even when a pattern matches them.

## [1.0.6] - 2026-07-21

### Fixed
- Child samplers under a Transaction Controller are no longer misrecorded as standalone transactions. The 1.0.5 standalone-sampler feature decided "is this a standalone sampler?" purely from JMeter's `SampleResult.getParent()` chain, which is not reliably intact in a BackendListener under load — so a leaf whose parent link to its Transaction Controller was dropped got fabricated into its own bogus single-step transaction (duplicating a real request). The listener now latches whether the run uses Transaction Controllers at all; once any TC sample is seen, unattributed leaves are treated as linkage failures and never turned into standalone transactions. Plans that genuinely use no Transaction Controllers are unaffected.

## [1.0.5] - 2026-07-08

### Added
- Test plans without Transaction Controllers now populate the `transactions` table: each leaf sampler with no Transaction Controller ancestor is recorded as its own single-step transaction, using the sampler name as the transaction name (matching `requests_raw.transaction_name`). This lets Perfana's Performance Analysis view show data for plans that group nothing under Transaction Controllers. Samplers nested under a Transaction Controller are unaffected — their enclosing TC still produces the transaction row, with no double-counting.

## [1.0.4] - 2026-06-03

### Changed
- Session variable capture now always excludes JMeter's own built-in/internal variables, which carry no debugging value: the reserved `__`-prefixed namespace (e.g. `__jm__<ThreadGroup>__idx`, `__jmeter.U_T__`, `__jmv_SAME_USER`), `JMeterThread.*` thread state (e.g. `JMeterThread.pack`, `JMeterThread.last_sample_ok`), and the `START.MS`/`START.YMD`/`START.HMS`/`TESTSTART.MS` timestamps. This filter is independent of `sessionVariablesExclude`, so overriding the user deny-list never re-exposes the noise.

## [1.0.3] - 2026-06-02

### Added
- Session variable capture on errors. When a sample fails and `saveSessionVariables=true`, the listener snapshots the failing virtual user's JMeter session variables and stores them in a new `requests_error.session_variables` (`jsonb`) column, queryable per key (e.g. `session_variables->>'cartId'`). Off by default. New parameters: `saveSessionVariables`, `sessionVariablesExclude` (secret-name deny-list, case-insensitive, replaces the default when set), `sessionVariablesMaxValueLength`, `sessionVariablesMaxTotalBytes`. Capture is skipped under writer backpressure, and if the `session_variables` column is absent the listener logs a warning and disables capture for the run instead of failing inserts.
- First test infrastructure for the project: JUnit 5 unit tests plus Testcontainers integration tests against a real TimescaleDB.

### Notes
- The `session_variables` column DDL is owned by the Perfana repo; `migrations/V003__add_session_variables.sql` here is a dev/test mirror. Apply the canonical migration to target environments before deploying this plugin.

## [1.0.2] - 2026-05-21

### Added
- `flattenNestedTransactions` config parameter (default `true`). When enabled, nested transaction-level results (e.g. Blazemeter Parallel Controller with `PARENT_SAMPLE=true` inside a Transaction Controller) are flattened: only the outermost transaction is written to the `transactions` table, and `requests_raw.transaction_name` always reflects the outermost Transaction Controller name. Set to `false` to restore the previous behaviour where every transaction-level result in the hierarchy was written separately.

## [1.0.1] - 2026-04-19

### Changed
- `url_patterns` flushes now commit in groups of 50 rows instead of one transaction per flush, shortening PK row lock hold times when multiple JMeter instances run in parallel.
- Periodic flush scheduler uses a randomized initial delay (0..`flushInterval`) so parallel JMeter instances don't align their first flushes at startup.

### Docs
- Added `docs/url-patterns-performance-notes.md` with follow-up recommendations for the Perfana API/worker repo (index drop, RLS review, app-side dedup, dedicated owner worker).

## [1.0.0] - 2026-04-12

### Added
- JMeter backend listener for TimescaleDB with batch writing and HikariCP connection pooling
- Five TimescaleDB tables: `requests_raw`, `transactions`, `requests_error`, `virtual_users`, `url_patterns`
- URL normalization with pattern-based deduplication (UUIDs, numeric IDs, long tokens, query parameters)
- Two operating modes: standard load testing and synthetic monitoring
- Backpressure handling with automatic payload reduction under high write load
- Configurable batch size, flush interval, and connection pool settings
- SSL/TLS support for PostgreSQL connections
- Maven Central publishing with GPG-signed artifacts
- GitHub Actions CI/CD workflows for build and release
