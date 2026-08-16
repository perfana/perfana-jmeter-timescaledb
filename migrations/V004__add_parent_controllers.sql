-- Migration: Add parent_controllers to requests_raw
-- Description: Records the controllers a request ran under, outermost first, as a JSON array of
--              {name, class, iteration}. The iteration is the pass that controller was executing,
--              so a consumer can tell which loop pass, foreach element or concurrent branch
--              produced the request — none of which is recoverable from the request row otherwise,
--              since a transparent controller leaves the transaction name and thread name of a
--              sequential sibling untouched. The Parallel Controller entry also carries
--              "execution": an id shared by every request of one concurrent pass, so the pass's
--              real elapsed time (last finish minus first start) can be measured per pass instead
--              of approximated from per-request averages.
--
--              NULL when the engine does not tag samples (stock Apache JMeter, or BreakTest with
--              sampleresult.parent_controllers off).
--
-- NOTE: This is a DEV/TEST MIRROR of the canonical schema, which is owned by the Perfana repo
-- (packages/shared/src/database/migrations). Apply the canonical migration to target environments
-- before deploying this plugin; this file keeps local and integration test databases in sync.
-- Do not diverge from the canonical DDL. See docs/parent-controllers-schema-writeup.md.

ALTER TABLE requests_raw ADD COLUMN IF NOT EXISTS parent_controllers jsonb;

COMMENT ON COLUMN requests_raw.parent_controllers IS
    'Controllers this request ran under, outermost first: [{"name","class","iteration"}], the Parallel Controller entry also carrying "execution" (one concurrent pass). NULL when the engine does not tag samples or the column predates the running plugin.';
