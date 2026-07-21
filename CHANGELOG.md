# Changelog

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
