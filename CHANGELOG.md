# Changelog

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
