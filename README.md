# Perfana JMeter TimescaleDB Backend Listener

A JMeter backend listener plugin that writes test results directly to [TimescaleDB](https://www.timescale.com/) for real-time performance analysis.

## Features

- Batch writing with configurable batch size and flush interval
- HikariCP connection pooling
- Backpressure handling with automatic payload reduction
- URL normalization for pattern-based analysis
- Two operating modes: standard load testing and synthetic monitoring
- Virtual user tracking (standard mode)

## Installation

### Maven/Gradle (recommended)

Add the dependency to your build tool:

**Maven:**
```xml
<dependency>
    <groupId>io.perfana</groupId>
    <artifactId>perfana-jmeter-timescaledb</artifactId>
    <version>1.0.2</version>
</dependency>
```

**Gradle:**
```groovy
implementation 'io.perfana:perfana-jmeter-timescaledb:1.0.2'
```

### Manual Installation

Download the fat JAR (`perfana-jmeter-timescaledb-1.0.2-all.jar`) from the [GitHub Releases](https://github.com/perfana/perfana-jmeter-timescaledb/releases) page and copy it to JMeter's `lib/ext` directory.

## Database Setup

Create the TimescaleDB schema using the migration files in the `migrations/` directory:

```bash
psql -h localhost -d jmeter -f migrations/V001__initial_schema.sql
psql -h localhost -d jmeter -f migrations/V002__add_url_normalization.sql
```

## Configuration

Add the Backend Listener to your JMeter test plan and select:

```
io.perfana.jmeter.timescaledb.JMeterTimescaleDBBackendListenerClient
```

### Connection Settings

| Parameter | Default | Description |
|-----------|---------|-------------|
| `timescaleDBHost` | `localhost` | Database host |
| `timescaleDBPort` | `5432` | Database port |
| `timescaleDBDatabase` | `jmeter` | Database name |
| `timescaleDBSchema` | `public` | Schema name |
| `timescaleDBUser` | _(empty)_ | Database user |
| `timescaleDBPassword` | _(empty)_ | Database password |
| `timescaleDBSslMode` | `prefer` | SSL mode (`disable`, `allow`, `prefer`, `require`, `verify-ca`, `verify-full`) |

### Connection Pool Settings

| Parameter | Default | Description |
|-----------|---------|-------------|
| `timescaleDBMaxPoolSize` | `10` | Maximum connections in pool |
| `timescaleDBConnectionTimeout` | `30000` | Connection timeout (ms) |

### Batch Settings

| Parameter | Default | Description |
|-----------|---------|-------------|
| `timescaleDBBatchSize` | `1000` | Records per batch |
| `timescaleDBFlushInterval` | `1` | Flush interval (seconds) |

### Test Identification

| Parameter | Default | Description |
|-----------|---------|-------------|
| `runId` | `Run` | Test run identifier |
| `systemUnderTest` | `SUT` | System under test |
| `testEnvironment` | `test` | Test environment |
| `scenarioName` | `Scenario` | Scenario name |
| `location` | `local` | Deployment location |
| `nodeName` | `controller` | Node name |

### Mode & Data Capture

| Parameter | Default | Description |
|-----------|---------|-------------|
| `syntheticMonitoring` | `false` | Enable synthetic monitoring mode |
| `saveResponseBody` | `true` | Save response bodies for failed requests |
| `normalizeUrls` | `true` | Enable URL normalization |
| `flattenNestedTransactions` | `true` | Flatten nested parallel controllers into the outermost transaction (see below) |

All parameters support JMeter property substitution: `${__P(propertyName,defaultValue)}`

### Nested transactions and parallel controllers

When a test plan uses the Blazemeter **Parallel Controller** (`com.blazemeter.jmeter.controller.ParallelSampler`) with `PARENT_SAMPLE=true` inside a **Transaction Controller**, the result tree contains two nested transaction-level results: the outer Transaction Controller and the inner Parallel Controller.

`flattenNestedTransactions=true` (default) resolves this cleanly:

- **`transactions` table** — only the outermost Transaction Controller is written. The Parallel Controller aggregate is suppressed because it has a transaction ancestor.
- **`requests_raw.transaction_name`** — always set to the outermost Transaction Controller name, regardless of how many parallel controller layers are between the sampler and the TC.

`flattenNestedTransactions=false` preserves the raw JMeter result hierarchy:

- **`transactions` table** — one row per transaction-level result, including each Parallel Controller group. This gives per-batch parallel timing but produces multiple rows per TC execution.
- **`requests_raw.transaction_name`** — set to the nearest transaction ancestor, which is the Parallel Controller name rather than the outer TC name.

The default (`true`) gives the most predictable grouping for dashboards and SLA reporting. Set to `false` only if you need per-parallel-batch timing data in the `transactions` table.

## Building from Source

```bash
# Build thin JAR (for Maven Central consumers)
./gradlew build

# Build fat JAR (for manual JMeter installation)
./gradlew fatJar

# Publish to Maven Local
./gradlew publishToMavenLocal
```

Requires Java 17+.

## TimescaleDB Tables

The plugin writes to five tables:

| Table | Description |
|-------|-------------|
| `requests_raw` | Individual HTTP sampler results |
| `transactions` | Transaction controller aggregates |
| `requests_error` | Detailed error information with headers and response body |
| `virtual_users` | Thread count metrics over time |
| `url_patterns` | Normalized URL patterns for deduplication |

## License

[Apache License 2.0](LICENSE)
