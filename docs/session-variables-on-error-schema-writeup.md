# Schema change request (Perfana repo): `session_variables` on `requests_error`

**Status:** Proposal — schema change must be applied in the **Perfana repo**, not here.
**Owner of DDL:** `perfana` repo (`packages/shared/src/database/migrations`).
**Requested by:** `perfana-jmeter-timescaledb` (JMeter TimescaleDB backend listener).

## Goal

When a JMeter sample fails, capture the failing virtual user's session variables
(JMeter thread variables) as key-value pairs and store them alongside the existing
error detail in `public.requests_error`. This makes failures debuggable with the
actual session state (user id, cart id, correlation tokens, etc.) that produced them.

## What the listener needs from the schema

A single new nullable column on the base hypertable:

```sql
ALTER TABLE public.requests_error
    ADD COLUMN session_variables jsonb;
```

- **Type `jsonb`** — chosen so individual keys are queryable later, e.g.
  `WHERE session_variables->>'cartId' = '...'`.
- **Nullable** — populated only for failed samples, and only when variable capture
  is enabled in the listener. Existing rows and all success rows stay `NULL`.
- No default, no index required initially. A `GIN` index can be added later if
  key-level filtering becomes a hot query pattern:
  `CREATE INDEX idx_requests_error_session_variables ON public.requests_error USING gin (session_variables);`

## Where to make the change in the Perfana repo

Migrations are TypeORM, driven by a single consolidated migration that executes
`SCHEMA_SQL`:

- `packages/shared/src/database/migrations/schema-sql.ts` — canonical schema, used
  for **fresh installs**. `requests_error` is defined around line 772.
- `packages/shared/src/database/migrations/1700000000000-ConsolidatedSchema.ts` —
  runs `SCHEMA_SQL` statement-by-statement.

Two edits are required:

1. **Fresh installs** — add `session_variables jsonb` to the `CREATE TABLE
   public.requests_error (...)` block in `schema-sql.ts`.
2. **Existing databases** — add a **new** timestamped TypeORM migration (after
   `1700000000000`) whose `up()` runs the `ALTER TABLE ... ADD COLUMN` above and
   whose `down()` runs `ALTER TABLE public.requests_error DROP COLUMN session_variables;`.
   A guard (`ADD COLUMN IF NOT EXISTS`) keeps it idempotent.

## Impact analysis

- **Continuous-aggregate views** `requests_error_5s`, `requests_error_1m`,
  `requests_error_5m` are count rollups (grouped dimensions + `n`). They do **not**
  select row-level detail, so they are **unaffected** and need no change.
- **TimescaleDB hypertable** — `requests_error` is a hypertable; adding a nullable
  column is an online metadata-only operation (no rewrite, no chunk migration).
- **Grants** — the existing `GRANT ... ON TABLE public.requests_error TO perfana_app /
  perfana_system` cover the new column; no grant change needed.
- **Reads** — anything doing `SELECT *` gets an extra `NULL` column; explicit-column
  consumers are unaffected.

## Coordinating the local schema mirror

This repo carries a dev mirror of the schema for local testing:

- `migrations/V001__initial_schema.sql` defines `requests_error` (no
  `session_variables`).
- The canonical schema lives in the Perfana repo; this mirror must be updated **after**
  the Perfana change lands, to keep local dev/integration tests in sync.

## Listener side (separate, follows this change)

Once the column exists, the plugin will:

1. Override `createSampleResult(context, result)` (runs on the **sampler thread**,
   where `JMeterContextService.getContext().getVariables()` returns the live session)
   and, for failed samples, snapshot the variables minus a configurable deny-list.
2. Carry the snapshot to `handleSampleResults` (which runs on the listener's worker
   thread, where the variables are otherwise unavailable).
3. Serialize to JSON and INSERT into `requests_error.session_variables`.

New listener config arguments (same `${__P(...)}` pattern as existing keys):

- `saveSessionVariables` (default `false`) — master on/off. Defaults **off** so variable
  capture (and the PII exposure in R2) is an explicit opt-in.
- `sessionVariablesExclude` — comma-separated **deny-list** of variable names to skip.
  Default excludes common secrets (case-insensitive): `password,passwd,pwd,token,
  secret,authorization,auth,apikey,api_key,sessionid,jsessionid,cookie,credential,
  bearer`. User-supplied names extend/replace this.
- `sessionVariablesMaxValueLength` (default e.g. `2048`) — per-value cap; longer
  values are truncated or skipped (see risk #7).
- `sessionVariablesMaxTotalBytes` (default e.g. `16384`) — total serialized cap per
  error row; once exceeded, stop adding keys.

## Listener-side risks and required mitigations

These are not optional polish — #1 and #2 change how the feature ships.

### High severity

**R1 — Schema mismatch stalls the load test.** `TimescaleDBWriter.flushRequestErrorBuffer`
catches `SQLException` and **re-adds the whole batch** (`reAddWithBackpressureWarning`).
If the plugin writes `session_variables` against a DB where the column is missing (plugin
deployed before this migration lands) or the jsonb bind is wrong, every error flush fails,
the batch retries forever, the buffer hits the high-water mark, `underPressure` flips, and
**JMeter sampler threads block**. A column-ordering slip halts the test rather than
degrading gracefully.
- *Mitigation:* at `setupTest`, probe `information_schema.columns` for
  `requests_error.session_variables`. If absent, log a warning and **disable capture for
  the run** (omit the column from the INSERT entirely) instead of failing inserts. Schema-
  first deploy remains the plan; this guard makes wrong ordering safe rather than fatal.

**R2 — PII / secret persistence.** A name-based deny-list misses secrets embedded in
values (a token inside a URL var) and sensitively-named-but-unlisted keys. Session vars
routinely hold emails, user/account ids, correlation tokens. These now persist in
TimescaleDB and its backups, readable via the existing `perfana_app` / `perfana_system`
grants — a data-retention/GDPR surface that did not exist before.
- *Mitigation:* `saveSessionVariables` defaults **off** — capture is explicit opt-in.
  When enabled, ship the secret-ish default deny-list, enforce the value-length cap (R7),
  and document the exposure prominently.

### Medium severity

**R3 — Hot-path overhead.** `createSampleResult` runs on the sampler thread for **every**
sample. Naive capture slows the very threads whose latency the test measures.
- *Mitigation:* early-return on `result.isSuccessful()`; zero allocation on the success
  path; snapshot only on failure.

**R4 — Snapshot must be a deep copy on the sampler thread.** `JMeterVariables` is the
thread's live mutable map; `handleSampleResults` runs on the worker thread. Reading the
live map later yields wrong values or `ConcurrentModificationException`.
- *Mitigation:* copy into an immutable `Map<String,String>` inside `createSampleResult`,
  before the result is enqueued.

**R5 — Carrier wiring (leaf vs root).** Error records are built from **leaf sub-results**
(`addAllSubResults` adds only leaves with no sub-results), but `createSampleResult` sees
only the top-level result. All leaves under one top result belong to the same VU thread,
so one snapshot covers them — but `handleSampleResults` must walk `leaf.getParent()` up to
the carrier to find it. Getting this wrong drops variables on transaction-wrapped/nested
errors (the common case).
- *Mitigation:* attach the snapshot at the root and resolve it by walking the parent chain
  from the failing leaf. (Implementation choice: a `SampleResult` subclass carrying the map,
  or an `IdentityHashMap` side-channel keyed by the root object and cleared on lookup with a
  safety eviction. The side-channel avoids cloning every `SampleResult` field; whichever is
  used must not leak entries for samples that never reach the worker — e.g. dropped phantom
  samples.)

**R6 — Load amplification during error storms.** Errors spike when the system is unhealthy
— exactly when the most jsonb payload and write volume gets added. The listener already
drops response data/headers under pressure (`reducedPayload` / `isUnderPressure()`).
- *Mitigation:* gate session-variable capture on the same `isUnderPressure()` flag; skip it
  while under backpressure, like response bodies.

**R7 — Fat values.** A var holding a full extracted response bloats the jsonb row and WAL.
- *Mitigation:* enforce `sessionVariablesMaxValueLength` and `sessionVariablesMaxTotalBytes`
  (above); skip or truncate oversized values.

### Low severity

- **R8 — jsonb JDBC binding.** Bind via `PGobject(type="jsonb")` or `?::jsonb`; a plain
  `setString` into a jsonb column throws or mis-stores — and a bind error triggers R1. Test
  explicitly. Write `NULL` (not `{}`) when there are no captured variables.
- **R9 — `getVariables()` edge cases.** Null-guard for non-thread samples; capturing inside
  `createSampleResult` is the safe spot even in distributed/remote runs.
- **R10 — Timing semantics.** Captured values reflect end-of-sample state (after
  post-processors/extractors), not at-request-time. Usually desired; document it.

This plugin work is intentionally **not** started until the schema change above is
agreed and applied in the Perfana repo.
