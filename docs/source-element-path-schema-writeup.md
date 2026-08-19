# Schema change request (Perfana repo): `source_element_path` on `requests_raw`

**Status:** Proposal — schema change must be applied in the **Perfana repo**, not here.
**Owner of DDL:** `perfana` repo (`packages/shared/src/database/migrations`).
**Requested by:** `perfana-jmeter-timescaledb` (JMeter TimescaleDB backend listener).

## Goal

Record where in the test plan a request came from. Today a request row carries a sampler name and
a transaction name, and nothing else about its origin — so two identically named elements in
different branches of a plan produce indistinguishable rows, and there is no way to show a request
in the context of the plan that issued it.

BreakTest can now attach that path to every result
(`SampleResult#getSourceTestElementPath()`, opt-in per listener). This column stores it.

## What the listener needs from the schema

A single new nullable column on the base hypertable:

```sql
ALTER TABLE public.requests_raw
    ADD COLUMN source_element_path jsonb;
```

Value: the elements from the Thread Group down to the sampler, **outermost first**, each
`{name, class, occurrence}`.

```json
[{"name":"Shoppers","class":"org.apache.jmeter.threads.ThreadGroup","occurrence":0},
 {"name":"checkout","class":"org.apache.jmeter.control.TransactionController","occurrence":1},
 {"name":"cart","class":"org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy","occurrence":0}]
```

- **Type `jsonb`** — one column for a variable-depth path, queryable per entry, and directly
  renderable as a breadcrumb in the UI.
- **Nullable** — `NULL` when the engine does not attach the path (stock Apache JMeter, or a
  BreakTest build without listener sample metadata). Never `[]`.
- **`occurrence`** counts identically named siblings of the same class at that level, starting at
  0. It is what makes two `checkout` transactions in different branches distinguishable; nothing
  else on the row can do that.
- **`class`** is the reliable type discriminator (Transaction Controller, Parallel Controller,
  Thread Group, sampler). Never infer type from the name.
- The path is **static**: it describes the plan, not the run. It does not say which loop pass or
  which concurrent execution produced the request.
- No index required initially. If the UI filters by plan position,
  `CREATE INDEX idx_requests_raw_source_element_path ON public.requests_raw USING gin (source_element_path);`

## Where to make the change in the Perfana repo

Same two edits as the `session_variables` change (see
`docs/session-variables-on-error-schema-writeup.md`):

1. **Fresh installs** — add `source_element_path jsonb` to the `CREATE TABLE public.requests_raw
   (...)` block in `packages/shared/src/database/migrations/schema-sql.ts`.
2. **Existing databases** — a new timestamped TypeORM migration whose `up()` runs the
   `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` above and whose `down()` drops it.

## Impact analysis

- **Continuous-aggregate views** over `requests_raw` are rollups over grouped dimensions and do
  not select row-level detail, so they are **unaffected**.
- **TimescaleDB hypertable** — adding a nullable column is metadata-only: no rewrite, no chunk
  migration.
- **Grants** — existing table grants cover the new column.
- **Row size** — this is the high-volume table. The path is a handful of short entries and repeats
  identically for every request of the same sampler, so it compresses well in the columnstore. If
  it proves heavy, the follow-up is a dimension table keyed by a path hash (the pattern
  `url_patterns` already uses for URLs), leaving only the hash inline.

## Predecessor

An earlier proposal (`parent_controllers`) stored the *runtime* controller chain — which loop pass,
which foreach element, plus a per-pass id for Parallel Controllers. That engine-side design was
rejected by the BreakTest maintainer in favour of the metadata mechanism this column is built on.
The parallel-pass grouping it enabled (wall time per concurrent pass) is therefore **not**
available; the static path cannot express it.

## Coordinating the local schema mirror

`migrations/V004__add_source_element_path.sql` in this repo is a dev/test mirror for local and
integration-test databases. It must not diverge from the canonical DDL.

## Listener side

Already implemented (`SampleMetadata`, `TimescaleDBWriter`):

- The listener declares what it needs via BreakTest's `SampleResultMetadataConsumer` contract
  (`needsSourceTestElementPath()`, `needsJMeterVariables()`), so the engine prepares nothing for a
  run that does not store it. `needsSourceTestElementPath()` is answered from the column probe, so
  an un-migrated database also means no engine-side work.
- Both accessors are read **reflectively**, so the plugin still compiles against stock Apache
  JMeter and keeps working on engines that do not attach metadata — those runs write `NULL`.
- The writer probes `information_schema.columns` at `setupTest` and omits the column from the
  INSERT for the whole run when it is absent, rather than failing every flush (R1 in the
  session-variables writeup).
