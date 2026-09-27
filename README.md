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
| **JMeter** | **BreakTest 2026.09.25 or newer is recommended** and is the only engine on which every plan shape is recorded correctly. Apache JMeter 5.6.3 and older BreakTest builds still work, with the constraints in [Engine support](#engine-support) — most importantly, every sampler must sit under a Transaction Controller with *Generate parent sample* enabled. |
| **Database** | TimescaleDB (PostgreSQL). See [Database Setup](#database-setup). |

## Engine support

A request row is only useful if it carries the name of the transaction it belongs to, and how the listener can find that name depends on the engine. BreakTest 2026.09.25 links every sampler result to the transaction it ran in, so attribution is exact. Every older engine, stock Apache JMeter included, leaves the listener walking the sub-result parent chain, which only exists when the Transaction Controller generates a parent sample.

| | BreakTest 2026.09.25+ | Older BreakTest, Apache JMeter 5.6.3 |
|---|---|---|
| Samplers under a Transaction Controller | Recorded, named after the controller | Recorded **only** with *Generate parent sample* enabled |
| Samplers outside every Transaction Controller, in a plan that uses them | Recorded as their own single-step transaction | **Dropped** from `requests_raw`, `requests_error` and `transactions` |
| Nested Transaction Controllers | Flattened per `flattenNestedTransactions` | Flattened only with *Generate parent sample* enabled |
| Plans with no Transaction Controller at all | Every sampler is its own transaction | Every sampler is its own transaction |
| `requests_raw.source_element_path` | Written when enabled | BreakTest with listener sample metadata only; `NULL` on stock JMeter |
| Session variables on error | Attached by the engine to the failing sample | Same on any BreakTest with listener sample metadata (2026.08+); snapshotted on the sampler thread on stock JMeter |

Measured on a plan with one nested Transaction Controller and one sampler outside it, 2 threads × 2 loops, so 12 requests and 8 transactions are expected:

| Engine and plan | `requests_raw` | `transactions` |
|---|---|---|
| BreakTest 2026.09.25 | 12 of 12, all attributed | 8, correct |
| BreakTest 2026.08.24, *Generate parent sample* on | 8 of 12 (the standalone sampler is dropped) | 4 |
| BreakTest 2026.08.24, *Generate parent sample* off | 3 of 12, each named after its own sampler | 11, including fabricated and unflattened rows |

The second row is the cost of the rule introduced in 1.5.0: without transaction references, a sampler that arrives with no Transaction Controller ancestor is indistinguishable from one whose parent chain broke under load, and storing it under its own name puts a one-sample metric into Perfana beside the real one. Dropping it is the safer of two bad options. On BreakTest 2026.09.25 the engine answers the question outright, so neither guess nor drop is needed.

The third row is not a supported configuration. If you cannot upgrade the engine, keep *Generate parent sample* enabled and keep every sampler inside a Transaction Controller.

The listener logs which of the two attributions is active at startup, along with its own version:

```
Setting up Perfana JMeter TimescaleDB Backend Listener 1.6.0...
This engine links each sample to its Transaction Controller; samplers are attributed by reference.
```

Check both lines before trusting a run. `lib/ext` is scanned alphabetically, so an older copy of this jar left in place silently wins over a newer one: `perfana-jmeter-timescaledb-1.3.1-all.jar` shadows `-1.6.0-all.jar`. Delete the old jar rather than adding the new one beside it.

## Installation

### Maven/Gradle (recommended)

Add the dependency to your build tool:

**Maven:**
```xml
<dependency>
    <groupId>io.perfana</groupId>
    <artifactId>perfana-jmeter-timescaledb</artifactId>
    <version>1.6.0</version>
</dependency>
```

**Gradle:**
```groovy
implementation 'io.perfana:perfana-jmeter-timescaledb:1.6.0'
```

### Manual Installation

Download the fat JAR (`perfana-jmeter-timescaledb-1.6.0-all.jar`) from the [GitHub Releases](https://github.com/perfana/perfana-jmeter-timescaledb/releases) page and copy it to JMeter's `lib/ext` directory.

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
| `timescaleDBConnectionInitTimeout` | `60000` | Milliseconds startup keeps retrying the first connection before failing the test run. A pooler under a starting test wave can stall logins; retrying rides that out. `1` restores fail-fast. |

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
| `responseBodyMaxLength` | `16384` | Characters of an error response body kept per failed sample; the rest is dropped with a marker. Bodies are only collected for failures, so the cost arrives all at once when the system under test starts failing. |
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

### How a sampler is attributed to its Transaction Controller

Both settings above describe *which* transaction a sampler is stored under. How the listener finds it depends on the engine, and it logs which of the two ran at startup:

- **BreakTest 2026.09.25 and newer** — the engine links every sampler result to the transaction it ran in, so attribution is exact. That release removed the Transaction Controller's *Generate parent sample* option: a sampler reaches the listener as soon as it completes, and the transaction sample arrives afterwards carrying only running totals, no nested children. A sampler the engine reports as running outside every transaction is genuinely standalone and is recorded as its own single-step transaction.
- **Stock Apache JMeter and older BreakTest builds** — attribution walks the sub-result parent chain up to the enclosing Transaction Controller, which requires *Generate parent sample*. JMeter does not keep that chain intact in a BackendListener under load, so a sampler that arrives detached from its Transaction Controller is dropped rather than stored under its own name, which would show up in Perfana as a separate one-sample metric beside the real one. A genuinely standalone sampler is indistinguishable from a detached one and is dropped with it.

See [Engine support](#engine-support) for what each engine records, with measured row counts.

A sampler label of the form `transaction::sampler` overrides both, in either engine.

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
