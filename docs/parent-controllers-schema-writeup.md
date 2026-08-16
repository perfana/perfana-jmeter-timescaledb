# Schema change request (Perfana repo): `parent_controllers` on `requests_raw`

**Status:** Proposal — schema change must be applied in the **Perfana repo**, not here.
**Owner of DDL:** `perfana` repo (`packages/shared/src/database/migrations`).
**Requested by:** `perfana-jmeter-timescaledb` (JMeter TimescaleDB backend listener).

## Goal

Record which controllers a request ran under, and which pass each of those controllers was
on. Today a request row says *what* was called (`sampler_name`) and *inside which transaction*
(`transaction_name`), but nothing about the structure between them: which loop pass, which
foreach element, which concurrent branch produced it. Transparent controllers make that
unrecoverable after the fact — a request issued concurrently by a Parallel Controller reaches
the listener with the same transaction name and the same thread name as a sequential sibling.

BreakTest now stamps every `SampleResult` with that runtime ancestry
(`SampleResult#getParentControllerExecutions()`, behind `sampleresult.parent_controllers`).
This column stores it.

## What the listener needs from the schema

A single new nullable column on the base hypertable:

```sql
ALTER TABLE public.requests_raw
    ADD COLUMN parent_controllers jsonb;
```

Value: the enclosing controllers **outermost first**, each `{name, class, iteration}`. The
Parallel Controller entry additionally carries `execution` — an id shared by every request of
one concurrent pass.

```json
[{"name":"Thread Group","class":"org.apache.jmeter.threads.ThreadGroup","iteration":-1},
 {"name":"loop","class":"org.apache.jmeter.control.LoopController","iteration":2},
 {"name":"checkout","class":"org.apache.jmeter.control.TransactionController","iteration":1},
 {"name":"par","class":"org.apache.jmeter.control.ParallelController","iteration":1,
  "execution":"Thread Group 1-3-par-1"}]
```

- **Type `jsonb`** — one column for a variable-depth path, queryable per entry, and directly
  renderable as a breadcrumb in the UI (`Thread Group › loop ② › checkout ① › par ①`).
- **Nullable** — `NULL` when the engine does not tag samples (stock Apache JMeter, or BreakTest
  with the property off). Never `[]`.
- **`iteration` is per controller type, not a universal counter.** A Loop Controller reports 1 on
  its first pass, a While/Transaction/Parallel Controller 0, and a controller that does not count
  reports `-1` (the Thread Group always does). Group and compare values; do not assume a base.
- **`execution` is the grouping key for a concurrent pass**, and is separate from `iteration`
  because a parallel branch executes on *cloned* controllers whose own counts cannot identify
  the pass. Every request of one pass shares it, so the pass's real elapsed time (last finish
  minus first start) can be measured per pass and reported as a distribution instead of
  approximated from per-request averages.
- No index required initially. If the UI filters by controller,
  `CREATE INDEX idx_requests_raw_parent_controllers ON public.requests_raw USING gin (parent_controllers);`
  or an expression index on the parallel `execution` covers it.

## Where to make the change in the Perfana repo

Same two edits as the `session_variables` change (see
`docs/session-variables-on-error-schema-writeup.md`):

1. **Fresh installs** — add `parent_controllers jsonb` to the `CREATE TABLE public.requests_raw
   (...)` block in `packages/shared/src/database/migrations/schema-sql.ts`.
2. **Existing databases** — a new timestamped TypeORM migration whose `up()` runs the
   `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` above and whose `down()` drops it.

## Impact analysis

- **Continuous-aggregate views** over `requests_raw` are rollups over grouped dimensions and do
  not select row-level detail, so they are **unaffected**.
- **TimescaleDB hypertable** — adding a nullable column is metadata-only: no rewrite, no chunk
  migration.
- **Grants** — existing table grants cover the new column.
- **Row size** — this is the high-volume table, so the payload matters. It is written only when
  the engine tags samples (a BreakTest opt-in property, off by default), the chain is a handful
  of short entries, and repeated identical values compress well in TimescaleDB's columnstore.
  If it turns out too heavy, the follow-up is a dimension table keyed by a path hash (as
  `url_patterns` already does for URLs), with only the per-row iterations left inline.

## Coordinating the local schema mirror

`migrations/V004__add_parent_controllers.sql` in this repo is a dev/test mirror for local and
integration-test databases. It must not diverge from the canonical DDL.

## Listener side

Already implemented (`ParentControllerTag`, `TimescaleDBWriter`):

- The whole tag is read **reflectively**, so the plugin still compiles against stock Apache
  JMeter and keeps working on engines that do not expose it — those runs simply write `NULL`.
- The writer probes `information_schema.columns` for the column at `setupTest` and, when it is
  absent, omits it from the INSERT for the whole run rather than failing every flush (the same
  guard as R1 in the session-variables writeup: a failing flush re-adds the batch, fills the
  buffer and eventually blocks JMeter's sampler threads).
