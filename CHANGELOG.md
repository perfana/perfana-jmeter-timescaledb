# Changelog

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
