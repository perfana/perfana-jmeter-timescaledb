# `url_patterns` write-path performance — notes for the Perfana repo

This document collects observations and recommendations that originated from a
`url_patterns` contention investigation but fall **outside the scope of
`perfana-jmeter-timescaledb`**. They are intended as input for follow-up work
in the **Perfana API / worker** repo, which owns the production TimescaleDB
schema (including RLS policies) and runs additional write paths into
`url_patterns`.

## Observed symptoms

During ingestion bursts (25 JMeter scripts running in parallel):

- 10+ concurrent `INSERT INTO public.url_patterns (...)` statements pile up on
  `Lock: transactionid`.
- Bulk inserts collide on the primary key
  `(url_hash, system_under_test, test_environment)`.
- Table size: 2.9 GB, ~4M rows. Indexes (1.47 GB) exceed table data (1.42 GB).
- Row-Level Security is enabled with function-based policies
  (`can_access_resource`, `is_global_admin`) evaluated on every INSERT and
  SELECT.
- WAL volume spiked 14% → 65% during the contention.
- Connection-pool saturation (29 active connections, most blocked).

## What this repo has already shipped (for reference)

The JMeter backend listener was one source of the inserts. Version after
`1.0.0` contains:

- **Per-JVM LRU dedup cache** (size 10 000) keyed by
  `url_hash|sut|env` — eliminates duplicate writes *within* one JMeter instance.
- **Explicit `INSERT ... ON CONFLICT DO NOTHING`** on
  `(url_hash, system_under_test, test_environment)`.
- **Short per-commit groups** — `url_patterns` flushes now commit every 50
  rows instead of one transaction per flush, dramatically shortening PK row
  lock hold times when multiple instances conflict.
- **Randomized initial flush delay** (0 .. `flushInterval`) per writer so
  parallel instances don't align their first flushes at startup.

These measures reduce contention between *JMeter instances*, but they cannot
coordinate across instances or across other writer services. Items below are
for the Perfana side.

## Recommendations for the Perfana repo

### 1. Drop `idx_url_patterns_normalized_url` if unused

The index on `normalized_url` adds write amplification (every INSERT touches
one more B-tree) and WAL volume, but lookups on `url_patterns` are by the PK
`(url_hash, sut, env)`.

Verify before dropping:

```sql
SELECT schemaname, relname, indexrelname, idx_scan, idx_tup_read, idx_tup_fetch
FROM pg_stat_user_indexes
WHERE indexrelname = 'idx_url_patterns_normalized_url';
```

If `idx_scan` is 0 (or very low relative to inserts), drop it:

```sql
DROP INDEX CONCURRENTLY IF EXISTS idx_url_patterns_normalized_url;
```

`DROP INDEX CONCURRENTLY` cannot run inside a transaction block; run it
out-of-band or via a migration runner that executes statements
non-transactionally.

Expected impact: reduced WAL, smaller index footprint, faster INSERT latency.
Does **not** eliminate `Lock: transactionid` contention on its own.

### 2. Reconsider RLS on `url_patterns`

RLS is intended for multi-tenant isolation. `url_patterns` is a write-hot
dedup/reference table. The function-based policies (`can_access_resource`,
`is_global_admin`) run on every INSERT and SELECT, amplifying the time each
conflicting transaction holds row locks before either committing or no-oping
via `ON CONFLICT`.

Options, roughly in order of preference:

1. **Enforce access scoping in the application layer** (API/worker) and drop
   the RLS policies on `url_patterns` entirely. Tenant isolation on dependent
   tables (`requests_raw`, `requests_error`) is unchanged — those already
   filter on `system_under_test` / `test_environment`.
2. **Simplify the policies** to column-only comparisons (no function calls)
   so Postgres can inline them and avoid per-row function invocations.
3. **Keep RLS but partition `url_patterns`** so concurrent writers target
   different partitions and contend less.

### 3. Application-side dedup across workers

Each JMeter instance already dedups locally. Perfana API/worker writers should
do the same:

- A process-local LRU (or bloom filter) of `(url_hash, sut, env)` seen
  recently. Bloom is attractive because false positives only cost an
  occasional redundant upsert; false negatives are impossible.
- Size the cache to cover the working set per (sut, env). The JMeter plugin
  uses 10 000 entries as a starting point.

### 4. Explicit `INSERT ... ON CONFLICT DO NOTHING`

Any writer that currently relies on catching PK violation exceptions should
switch to an explicit `ON CONFLICT DO NOTHING`. Catching `SQLException` for
every conflict inflates log volume and adds Java-side overhead on the hot
path.

### 5. Smaller commit groups

For the same reason the JMeter listener now commits `url_patterns` in groups
of 50: a transaction that inserts 500 rows holds row locks for all of them
until commit. Ten small commits of 50 rows serialize far better under
contention than one commit of 500.

Tune toward **latency over throughput** for this specific table.

### 6. Dedicated owner worker for `url_patterns`

If the API/worker ingestion pipeline is the dominant source of inserts,
funnel all `url_patterns` writes through a single worker (or a work queue
with a single consumer). The database then sees zero concurrent conflicting
writes for this table, and other ingestion workers no longer block on
`Lock: transactionid`.

Trade-off: one worker becomes a throughput ceiling for `url_patterns`. In
practice that's fine because the table is low-cardinality relative to
`requests_raw` — a single worker can easily keep up with net-new patterns.

## Suggested sequencing

1. **Index drop** — one-off, low risk, quick WAL/bloat relief.
2. **Explicit `ON CONFLICT DO NOTHING` + app-side dedup** — eliminates most
   of the write volume.
3. **Smaller commit groups** — shortens lock windows for what remains.
4. **RLS review** — structural, needs product/security sign-off.
5. **Dedicated owner worker** — architectural, do last, and only if 1–4
   don't fully resolve the contention.
