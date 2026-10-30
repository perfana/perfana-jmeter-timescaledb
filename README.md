# Perfana JMeter TimescaleDB Backend Listener

A JMeter backend listener plugin that writes test results directly to [TimescaleDB](https://www.timescale.com/) for real-time performance analysis.

## Features

- Batch writing with configurable batch size and flush interval
- HikariCP connection pooling
- Backpressure handling with automatic payload reduction
- URL normalization for pattern-based analysis
- Two operating modes: standard load testing and synthetic monitoring
- Virtual user tracking (standard mode), per thread group when asked for
- Optional per-thread-group grouping, so parallel thread groups sharing a transaction name stay separate metrics

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
Setting up Perfana JMeter TimescaleDB Backend Listener 1.8.2...
This engine links each sample to its Transaction Controller; samplers are attributed by reference.
```

Check both lines before trusting a run. `lib/ext` is scanned alphabetically, so an older copy of this jar left in place silently wins over a newer one: `perfana-jmeter-timescaledb-1.3.1-all.jar` shadows `-1.8.2-all.jar`. Delete the old jar rather than adding the new one beside it.

## Installation

### Maven/Gradle (recommended)

Add the dependency to your build tool:

**Maven:**
```xml
<dependency>
    <groupId>io.perfana</groupId>
    <artifactId>perfana-jmeter-timescaledb</artifactId>
    <version>1.8.2</version>
</dependency>
```

**Gradle:**
```groovy
implementation 'io.perfana:perfana-jmeter-timescaledb:1.8.2'
```

### Manual Installation

Download the fat JAR (`perfana-jmeter-timescaledb-1.8.2-all.jar`) from the [GitHub Releases](https://github.com/perfana/perfana-jmeter-timescaledb/releases) page and copy it to JMeter's `lib/ext` directory.

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
| `timescaleDBConnectionInitTimeout` | `60000` | Milliseconds startup keeps retrying the first connection before failing the test run. A pooler under a starting test wave can stall logins; retrying rides that out. `1` restores fail-fast. See [Startup connection retries](#startup-connection-retries). |

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
| `scenarioNamePerThreadGroup` | `false` | Append each sample's Thread Group to the stored `scenario_name`, so two thread groups sharing a transaction name stay separate metrics (see [below](#multiple-thread-groups)) |

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

### Multiple thread groups

Rows are grouped by `transaction_name` and `sampler_name`; the thread group a sample came from is not part of the key. So two thread groups running in parallel that use the **same** transaction name have their rows merged into one metric, and `scenario_name` cannot separate them either, because it is one configured value for the whole JMeter JVM.

That merge is usually unwanted. Measured on a plan with `TG_Zoeken` (2 threads) and `TG_Solliciteren` (3 threads), each with its own `Login` transaction:

| | `scenario_name` | `transaction_name` | rows | avg ms |
|---|---|---|---|---|
| default | `T_WNL_Combi` | `Login` | 10 | 1621 |
| `scenarioNamePerThreadGroup=true` | `T_WNL_Combi_TG_Zoeken` | `Login` | 4 | 1351 |
| `scenarioNamePerThreadGroup=true` | `T_WNL_Combi_TG_Solliciteren` | `Login` | 6 | 1564 |

The blended 1621 ms hides two populations that differ by 200 ms, and the blend moves whenever the thread group mix changes, for instance if one ramps differently, even though neither transaction got slower.

With `scenarioNamePerThreadGroup=true` the stored `scenario_name` becomes `<scenarioName>_<thread group name>` on `requests_raw`, `transactions` and `requests_error`. Notes:

- **`test_run_id` is never suffixed.** In synthetic monitoring mode the scenario name *is* the run id, so suffixing it would split one run into one run per thread group.
- **Turning it on re-labels `scenario_name`,** so existing Perfana series for that scenario do not continue into the new name. That is why it is off by default. Decide once per test, not per run.
- A thread group named exactly after its scenario is not repeated: `Zoeken` + `Zoeken` stays `Zoeken`.
- The thread group is read from the sample's thread name (`TG_Zoeken 1-2`), since nothing else on a `SampleResult` names it and the listener's worker thread has no JMeter context. A thread name without that `<group>-<thread>` suffix leaves the scenario name unsuffixed rather than guessing.

**`virtual_users` gains a series per thread group,** alongside the existing JVM-wide row under the unsuffixed name, so anything already reading it keeps the series it has:

| `scenario_name` | `active_threads` | `started_threads` / `finished_threads` |
|---|---|---|
| `T_WNL_Combi` | 5 (JVM-wide) | populated |
| `T_WNL_Combi_TG_Zoeken` | 2 | `NULL` |
| `T_WNL_Combi_TG_Solliciteren` | 3 | `NULL` |

The per-group `active_threads` comes from `SampleResult.getGroupThreads()`, the thread group's own count. JMeter exposes started and finished per engine only, never per thread group, so those stay `NULL` rather than carrying a derived number that would be wrong during ramp-up. A thread group also only appears once its first sample has arrived, so the first second or two of a run has the total row but no per-group rows.

**A distributed run does not group by load generator.** On a remote engine JMeter names its threads `<host>-<thread group> <group>-<thread>`, so the thread group arrives as `10.0.0.1:1099-Zoeken`. That address is stripped, so one thread group is one scenario no matter how many generators ran it. It is recognised two ways: by matching the configured `remote_hosts` (with the port optional, since JMeter drops it from that property but keeps it in the thread name), and failing that by the `<address>:<port>-` shape itself, which a standalone run never produces. The second rule covers a `remote_hosts` that is unset by then, and a `-R` given a host name while the thread name carries the resolved address. A thread group genuinely named `order-entry` keeps its name, and an address that is not a configured remote host is left alone. Which generator a sample came from is already in `node_name` and `location`.

Thread counts are still tracked per generator internally and summed into that one scenario, so a thread group spread over four engines reports its total concurrency rather than one engine's.

**Two thread groups with the same name cannot be separated**, JMeter's default `Thread Group` most of all: the suffix is the name, so both land under `<scenarioName>_Thread Group` and their rows stay merged. The listener logs a warning naming the duplicate, because nothing downstream can tell such rows from any other scenario's:

```
2 thread groups are named "Thread Group", so their samples share one scenario_name and stay
merged into one metric despite scenarioNamePerThreadGroup=true. Their virtual user counts are
summed, which is the concurrency behind those merged rows. Give the thread groups distinct
names to separate them.
```

Their virtual user counts are summed rather than one group overwriting the other, so a merged scenario reports the concurrency that actually produced its rows (2 + 3 threads = peak 5). Renaming the thread groups is the fix; the names are what Perfana displays, so they are worth setting anyway.

If you would rather not change `scenario_name` at all, the alternative is to make the transaction names unique in the plan (`Zoeken_Login`, `Solliciteren_Login`). That needs no setting and no listener support.

### Startup connection retries

If the database is not reachable when the test starts, `setupTest` fails and JMeter aborts the run with `IllegalStateException: Failed calling setupTest`. To ride out a database or a pooler that is still coming up, startup retries the first connection for `timescaleDBConnectionInitTimeout` milliseconds, pausing one second between attempts.

**There is no attempt count to set.** The number of attempts falls out of two values, and it varies by an order of magnitude with the failure mode:

| Failure mode | One attempt takes | Attempts in the default 60000 ms |
|---|---|---|
| Port refuses the connection | milliseconds | about 60 |
| Host accepts, then stalls the handshake | 5s (the driver's `sslResponseTimeout`) | about 10 |
| Host never answers at all | the full `timescaleDBConnectionTimeout`, 30s by default | **2** |

That last row is the one that surprises people: `timescaleDBConnectionInitTimeout=60000` sounds like a minute of retrying, and against a silent host it is two tries. Startup says which range applies in its own log line, and each failed attempt gets a line of its own:

```
Startup will retry the first connection for up to 60000 ms, 30 s per attempt: as few as 2
  attempt(s) if every attempt stalls for the full 30 s, up to about 60 if they fail immediately.
TimescaleDB connection attempt 1 failed, 30041 ms of the 60000 ms startup window used,
  retrying in 1000 ms: PSQLException: Connection attempt timed out.
Giving up on TimescaleDB after 2 connection attempt(s) in 62085 ms (60000 ms window, 30 s per
  attempt). Raise timescaleDBConnectionInitTimeout to retry for longer. Last failure: ...
```

Two levers, both of which widen the count:

- **Raise the window.** `timescaleDBConnectionInitTimeout=300000` gives about 10 attempts over five minutes against a silent host. This is the one to reach for first.
- **Lower the per-attempt cap.** `timescaleDBConnectionTimeout=10000` turns the default window into about 6 attempts, but it also shortens how long a flush waits for a pool connection during the run, so it is not free.

Note the asymmetric naming when setting these from the command line: the **parameter** is `timescaleDBConnectionInitTimeout`, while the **JMeter property** behind its default is `timescaleConnectionInitTimeout`, with no `DB`. Every connection parameter is spelled that way (`timescaleHost` feeds `timescaleDBHost`, and so on):

```bash
jmeter -n -t plan.jmx -JtimescaleConnectionInitTimeout=300000
```

That only works if the Backend Listener in the JMX carries the argument row with the `${__P(...)}` expression. A plan saved before the parameter existed keeps its own argument list, in which case the built-in default applies and no property can change it. Either add the row, or set the value on the Backend Listener directly:

```xml
<elementProp name="timescaleDBConnectionInitTimeout" elementType="Argument">
  <stringProp name="Argument.name">timescaleDBConnectionInitTimeout</stringProp>
  <stringProp name="Argument.value">${__P(timescaleConnectionInitTimeout,60000)}</stringProp>
  <stringProp name="Argument.metadata">=</stringProp>
</elementProp>
```

### What URL normalization masks

With `normalizeUrls=true` (default) each request URL is reduced to a pattern, stored in `url_patterns` and referenced from `requests_raw.url_hash`. Only the path and query are examined; the scheme and host are left alone, so the nodes of a farm stay distinguishable.

| In the path | Becomes | Example |
|---|---|---|
| A UUID | `{uuid}` | `/items/550e8400-e29b-41d4-a716-446655440000` |
| A segment of only digits | `{id}` | `/vacature-relation/58741300/save` |
| 21+ characters, letters and digits only | `{token}` | an encoded blob |
| 8+ characters mixing letters and digits | `{id}` | `/assignments/1-q02oj1h` |
| A `;name=value` path parameter | `;name={val}` | `/home;jsessionid=2o84…` |
| Every query parameter value | `name={val}` | `?a=1&b=2`, sorted by name |

The eight-character floor on the mixed rule is what keeps real path words that carry a digit: `v1`, `v2`, `oauth2`, `html5`, `base64`, `sha256`. A segment that is neither of those shapes is kept verbatim, so a value this list does not describe (a date such as `2026-10-14`, say) still produces one pattern per value.

`requests_error.url` and `url_patterns.original_example` deliberately keep the original, unmasked URL, so a failure can still be reproduced.

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
| `virtual_users` | Thread count metrics over time, JVM-wide and (optionally) per thread group |
| `url_patterns` | Normalized URL patterns for deduplication |

## License

[Apache License 2.0](LICENSE)
