# Design: Session variables on error → TimescaleDB

**Date:** 2026-06-02
**Status:** Approved — ready for implementation plan
**Source writeup:** `docs/session-variables-on-error-schema-writeup.md` (schema rationale + risks R1–R10)
**Schema status:** `session_variables jsonb` column has **landed** in the canonical Perfana-repo schema.

## Goal

When a JMeter sample fails, snapshot the failing virtual user's session variables
(JMeter thread variables) and store them as a queryable `jsonb` map in
`public.requests_error.session_variables`, so failures can be debugged with the
actual session state that produced them. Capture is **opt-in** and gated by a
secret-ish deny-list.

## Resolved design decisions

These are the decisions made during brainstorming, on top of the writeup.

### Carrier mechanism (resolves R5) — side-channel keyed on the top-level result

Variables are live only on the **sampler thread**, inside `createSampleResult`.
The error record is built on the **worker thread**, in `handleSampleResults`.
We carry the snapshot between them via a **side-channel**, not a `SampleResult`
subclass:

- A bounded `ConcurrentHashMap<SampleResult, Map<String,String>>`. `SampleResult`
  uses identity `equals`/`hashCode`, so this is effectively an identity map and is
  thread-safe across concurrent sampler threads (plain `IdentityHashMap` is not).
- `createSampleResult` (sampler thread) snapshots **only on `!result.isSuccessful()`**,
  builds an immutable `Map<String,String>`, and `put`s it keyed on the top-level
  result. Success path does zero allocation (R3).
- `handleSampleResults` iterates the **top-level** results — the same objects
  `createSampleResult` returned. It looks up the snapshot by that top-level object
  **once per iteration**, hands it into `addAllSubResults`, and associates it with
  each leaf error built from that tree. No `getParent()` walking (simpler and
  unambiguous vs. the writeup's parent-walk).
- The entry is **removed on lookup**, so the map self-cleans. Only failures ever
  populate it. A size cap with eldest-eviction prevents leaks from dropped phantom
  samples that never reach the worker (R5).

Rejected alternative — `SampleResult` subclass: would require deep-copying every
field of an already-built result (the sub-result tree exists by the time
`createSampleResult` runs); any copy-fidelity slip silently corrupts error records.

### Snapshot must be a deep/immutable copy on the sampler thread (R4)

The snapshot is copied into an immutable `Map<String,String>` inside
`createSampleResult`, before enqueue. The live `JMeterVariables` map is never read
on the worker thread.

### Local schema mirror — add `migrations/V003`, mirror-only

The canonical DDL lives in and has landed in the Perfana repo. This repo's
`migrations/` is a **dev/test mirror**; local and integration DBs are built from it.
We add `migrations/V003__add_session_variables.sql` with exactly:

```sql
ALTER TABLE requests_error ADD COLUMN IF NOT EXISTS session_variables jsonb;
```

This is a sync of an already-landed canonical change (the writeup's "Coordinating
the local schema mirror" step), not authorship of new schema. It is also required
to exercise the happy path locally — without it the R1 probe disables capture.

## Configuration surface (new listener args)

Same `${__P(...)}` pattern as existing keys. Added to `TimescaleDBConfig` +
`getDefaultParameters()`.

| Key | Default | Meaning |
|-----|---------|---------|
| `saveSessionVariables` | `false` | Master on/off. Off by default (explicit opt-in; PII exposure per R2). |
| `sessionVariablesExclude` | secret-ish deny-list (below) | Comma-separated variable names to skip (case-insensitive). |
| `sessionVariablesMaxValueLength` | `2048` | Per-value cap; values longer than this are **skipped entirely** (not truncated — avoids persisting a partial secret) (R7). |
| `sessionVariablesMaxTotalBytes` | `16384` | Total serialized cap per error row; stop adding keys once exceeded (R7). |

Default deny-list (case-insensitive): `password,passwd,pwd,token,secret,
authorization,auth,apikey,api_key,sessionid,jsessionid,cookie,credential,bearer`.
User-supplied `sessionVariablesExclude` replaces this default (documented).

## Data flow

```
createSampleResult (sampler thread)
  └─ if saveSessionVariables && !success && !underPressure:
       snapshot = filter(JMeterContextService.getContext().getVariables(),
                         denyList, maxValueLength, maxTotalBytes)   // immutable copy
       sideChannel.put(topLevelResult, snapshot)                   // bounded, eldest-evict
     return result (unchanged)

handleSampleResults (worker thread)
  └─ for each topLevelResult:
       snapshot = sideChannel.remove(topLevelResult)               // null when absent
       addAllSubResults(..., snapshot)                             // associate with each leaf error
  └─ failed leaf → RequestErrorRecord.sessionVariables = snapshot
  └─ TimescaleDBWriter serializes map → JSON, binds as jsonb (R8)
```

## Components touched

- **`TimescaleDBConfig`** — 4 new keys/defaults/getters; parse a deny-list `Set` and
  caps. Expose `isSaveSessionVariables()`, deny-set, caps.
- **`JMeterTimescaleDBBackendListenerClient`**
  - `createSampleResult(context, result)` override — failure-only snapshot + filter +
    side-channel put. Null-guard `getContext()`/`getVariables()` for non-thread
    samples (R9).
  - side-channel field (bounded identity map) + a small static helper for filtering.
  - `handleSampleResults` / `addAllSubResults` — thread the snapshot to leaf error
    records.
  - `setupTest` — R1 probe: query `information_schema.columns` for
    `requests_error.session_variables`; if absent, log a warning and **disable
    capture for the run** (writer omits the column) rather than failing inserts.
  - Gate capture on `writer.isUnderPressure()` (R6), same as response bodies.
- **`RequestErrorRecord`** — new `Map<String,String> sessionVariables` field + builder
  + getter.
- **`TimescaleDBWriter`**
  - INSERT SQL gains `session_variables` column **only when the probe found it**
    (two SQL variants, or conditional column list built at construction from a
    `boolean sessionVariablesColumnPresent` flag set by the listener/probe).
  - `writeRequestErrorBatch` binds the map as `jsonb` via `PGobject(type="jsonb")`
    (or `?::jsonb`); writes **`NULL`, not `{}`**, when the map is null/empty (R8).
  - JSON serialization helper (map → compact JSON string).
- **`migrations/V003__add_session_variables.sql`** — mirror DDL.

## Error handling / safety (mapped to risks)

- **R1 (schema mismatch stalls test):** `setupTest` column probe → disable capture +
  omit column when absent. Makes wrong deploy ordering safe, not fatal.
- **R2 (PII):** opt-in default off; deny-list; value cap; documented exposure.
- **R3 (hot path):** early-return on success; zero allocation on success path.
- **R4 (deep copy):** immutable map built on sampler thread.
- **R5 (carrier):** side-channel keyed on top-level result; remove-on-lookup;
  eldest-eviction cap.
- **R6 (error storms):** skip capture while `isUnderPressure()`.
- **R7 (fat values):** per-value cap (oversized values skipped, not truncated) +
  total-bytes cap (stop adding keys once exceeded).
- **R8 (jsonb binding):** `PGobject`/`::jsonb`; `NULL` for empty; explicit test.
- **R9 (`getVariables()` edge cases):** null-guard; capture inside `createSampleResult`.
- **R10 (timing):** values reflect end-of-sample state; documented.

## Testing

- Unit: deny-list filtering (case-insensitive), value/total caps, JSON serialization,
  empty→NULL, snapshot immutability.
- Unit: side-channel put/remove, eldest-eviction, success path makes no entry.
- Integration (against a DB built from `V001`+`V002`+`V003`): failed sample writes a
  queryable jsonb row; `session_variables->>'key'` lookups work; probe path with the
  column dropped disables capture without failing inserts.

## Out of scope

- GIN index on `session_variables` (added later in Perfana repo if key-level
  filtering becomes hot).
- Value-level (vs. name-level) secret redaction.
