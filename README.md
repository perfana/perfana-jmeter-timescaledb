# Perfana JMeter TimescaleDB Backend Listener

A JMeter backend listener plugin that writes test results directly to [TimescaleDB](https://www.timescale.com/) for real-time performance analysis.

## Features

- Batch writing with configurable batch size and flush interval
- HikariCP connection pooling
- Backpressure handling with automatic payload reduction
- URL normalization for pattern-based analysis
- Two operating modes: standard load testing and synthetic monitoring
- Virtual user tracking (standard mode)

## Requirements

| | |
|---|---|
| **Java** | **17 or newer** — the plugin's classes are compiled for Java 17, so the JVM running JMeter must be at least 17. An older JVM fails at class load with `UnsupportedClassVersionError`, even though JMeter itself still supports Java 8. |
| **JMeter** | Apache JMeter 5.6.3 or a BreakTest build. Verified against stock 5.6.3: the plugin records everything it can and leaves engine-specific columns (`source_element_path`) `NULL`, logging the reason once at startup. |
| **Database** | TimescaleDB (PostgreSQL). See [Database Setup](#database-setup). |

## Installation

### Maven/Gradle (recommended)

Add the dependency to your build tool:

**Maven:**
```xml
<dependency>
    <groupId>io.perfana</groupId>
    <artifactId>perfana-jmeter-timescaledb</artifactId>
    <version>1.2.0</version>
</dependency>
```

**Gradle:**
```groovy
implementation 'io.perfana:perfana-jmeter-timescaledb:1.2.0'
```

### Manual Installation

Download the fat JAR (`perfana-jmeter-timescaledb-1.2.0-all.jar`) from the [GitHub Releases](https://github.com/perfana/perfana-jmeter-timescaledb/releases) page and copy it to JMeter's `lib/ext` directory.

## Distribution

This plugin is published to three channels:

- **Maven Central** — `io.perfana:perfana-jmeter-timescaledb` (thin jar, for Maven/Gradle consumers)
- **GitHub Releases** — fat jar (`-all.jar`) for manual `lib/ext` installation
- **JMeter Plugins Manager** — descriptor in [`plugin-manager.json`](plugin-manager.json)

See [`docs/PUBLISHING.md`](docs/PUBLISHING.md) for the release process and how to register/update the Plugins Manager entry.

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

### Test plan path (`requests_raw.source_element_path`)

Every request can record where in the test plan it came from — the elements enclosing it, outermost first, from the Thread Group down to the sampler itself:

```json
[{"name":"Shoppers","class":"org.apache.jmeter.threads.ThreadGroup","occurrence":0},
 {"name":"checkout","class":"org.apache.jmeter.control.TransactionController","occurrence":1},
 {"name":"cart","class":"org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy","occurrence":0}]
```

`occurrence` numbers identically named siblings of the same class at that level, starting at 0 — so two `checkout` transactions in different branches of a plan stay distinguishable, which sampler and transaction names alone cannot do. `class` is the type discriminator; never infer the type from the name.

```sql
-- requests grouped by the branch of the plan that issued them
SELECT (SELECT string_agg(e->>'name', ' > ' ORDER BY ord)
          FROM jsonb_array_elements(r.source_element_path) WITH ORDINALITY AS t(e, ord)) AS plan_path,
       count(*) AS requests,
       round(avg(response_time)) AS avg_ms
FROM requests_raw r
WHERE test_run_id = $1
GROUP BY 1 ORDER BY 2 DESC;
```

```sql
-- everything under one element, whatever its depth (GIN-indexable)
SELECT * FROM requests_raw
WHERE source_element_path @> '[{"name": "checkout", "occurrence": 1}]';
```

The path is **static**: it describes the plan, not the run. It does not say which loop pass or which concurrent execution produced a request.

Capture is **off by default**. Switch it on with `saveSourceElementPath=true` (or `-JsaveSourceElementPath=true`).

| Parameter | Default | Meaning |
|---|---|---|
| `saveSourceElementPath` | `false` | Record the test plan path of every request in `requests_raw.source_element_path`. |

Requirements: a BreakTest engine that supports listener sample metadata, and the `source_element_path` column on `requests_raw`. The listener asks the engine for the path only when capture is enabled and that column exists, so a run that does not want the path costs the engine nothing. Without either, the column is left `NULL`; the listener logs the reason once at test start and keeps recording every other column.

### Session variable capture on errors

When a sample fails, the listener can snapshot the failing virtual user's JMeter session variables and store them in `requests_error.session_variables` (a queryable `jsonb` column), so failures can be debugged with the session state that produced them.

> ⚠️ **PII / secret exposure.** Session variables routinely hold emails, account ids, and correlation tokens. Anything captured persists in TimescaleDB and its backups. Capture is **off by default** and **opt-in per variable**: only names you list are ever stored. Review what your listed names hold before enabling.

| Parameter | Default | Description |
|-----------|---------|-------------|
| `saveSessionVariables` | `false` | Master on/off switch. |
| `sessionVariablesInclude` | _(empty)_ | Comma-separated allow-list of the variable names to store (see below). Empty captures nothing. |
| `sessionVariablesMaxValueLength` | `2048` | Values longer than this (characters) are skipped entirely (not truncated). |
| `sessionVariablesMaxTotalBytes` | `16384` | Once kept key+value bytes exceed this for a row, no further variables are added. |

#### Choosing what to capture

Nothing is stored until you name it. `sessionVariablesInclude` takes a comma-separated list of variable names; `*` in a name matches any run of characters. Matching is case-insensitive and covers the **whole** name, so `cart` allows `cart` but not `cartId`.

| Pattern | Matches | Does not match |
|---------|---------|----------------|
| `cartId` | `cartId`, `CARTID` | `cartIdExt`, `myCartId` |
| `order_*` | `order_id`, `order_total` | `myorder_id` |
| `*Id` | `userId`, `orderId` | `identity` |
| `ship*_id_*` | `shipping_id_ext` | `shipping_id` |
| `*` | every non-internal variable | — |

Enable it for a run:

```bash
jmeter -n -t plan.jmx \
  -JsaveSessionVariables=true \
  -JsessionVariablesInclude=cartId,order_*,customerNumber
```

The same two values can be set as Backend Listener arguments in the JMX instead. Quote the list if your shell would split on the commas, and note that the value must not be passed through `${__P(...)}` with a default containing commas — JMeter parses those as extra function arguments.

A failed sample then stores exactly those variables:

```json
{"cartId": "c-8841", "order_id": "9912", "order_total": "149.95", "customerNumber": "NL-4471"}
```

Notes:

- JMeter's own built-in/internal variables are **always excluded**, even if a pattern matches them (so `*` gives you your test's variables without the engine's bookkeeping): the reserved `__`-prefixed namespace (e.g. `__jm__<ThreadGroup>__idx`, `__jmeter.U_T__`), `JMeterThread.*` thread state (e.g. `JMeterThread.pack`, `JMeterThread.last_sample_ok`), and the `START.MS`/`START.YMD`/`START.HMS`/`TESTSTART.MS` timestamps. Only meaningful user/test variables are stored.
- `*` captures every non-internal variable, secrets included. There is no secret-name deny-list any more — the allow-list is the protection, so list names rather than reaching for `*` on a plan that handles credentials.
- Requires the `session_variables jsonb` column on `requests_error`. If the column is absent the listener logs a warning and disables capture for the run (it never fails inserts).
- Capture is also skipped while the writer is under backpressure (same as response bodies).
- Captured values reflect **end-of-sample** state (after post-processors/extractors).
- On a BreakTest engine with listener sample metadata the variables come from the engine, attached to the failing sub-result itself. On any other engine the listener snapshots them on the sampler thread and carries them to the worker. Either way, only failed samples are stored and only when capture is enabled.

Query example:

```sql
SELECT time, sampler_name, session_variables->>'cartId' AS cart_id
FROM requests_error
WHERE session_variables->>'cartId' = '...';
```

## Building from Source

```bash
# Build thin JAR (for Maven Central consumers)
./gradlew build

# Build fat JAR (for manual JMeter installation)
./gradlew fatJar

# Publish to Maven Local
./gradlew publishToMavenLocal
```

Building requires a Java 17+ JDK; see [Requirements](#requirements) for what running the plugin needs.

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
