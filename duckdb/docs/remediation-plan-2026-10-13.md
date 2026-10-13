# DuckDB backend remediation plan — 13 October 2026

This plan is derived from the attached repository audit and the source in this
checkout. The audit is evidence and design input; the user request is the
authority for the work. Historical findings are not treated as completed work
unless the current source and tests confirm them.

## Scope selected for this iteration

1. **R136:** make SEMI and ANTI joins expose only the left logical schema,
   including when either side uses the private zero-column sentinel.
2. **R137:** reject every sampling fraction outside the documented inclusive
   range `[0, 1]` before planning or executing a query, with a stable error
   marker (`ZD_SAMPLE_FRACTION`).
3. **R132/R133/R135:** make job-local materialization non-destructive on a
   duplicate table, enforce `maxRows` before creating a table, and make close
   cleanup continue after an individual table-drop failure.
4. **R82:** calculate sparse LINK min/max scores per entity, so a missing edge
   contributes zero only to the entity that lacks that evidence.
5. Add regression tests for each correction and run focused plus module-level
   Maven tests.

## Build and acceptance sequence

1. Add failing regression coverage for zero-column SEMI/ANTI joins, invalid
   fractions, materialization ownership/budget behavior, and a three-node sparse
   LINK path.
2. Patch the frame compiler, sampling guard, job lifecycle, and LINK reducer.
3. Run the focused DuckDB engine and compatibility tests.
4. Run the complete DuckDB Maven reactor test suite and inspect the diff/status.

Acceptance requires: SEMI/ANTI output columns are exactly the left logical
columns for ordinary and zero-column inputs; invalid fractions fail at the API
boundary; duplicate tables retain their rows; row limits fail before DDL;
cleanup reaches schema/connection close; and a sparse path gives the center
node its direct evidence range rather than a component-wide artificial zero.

## Execution result

- Focused engine tests: **90 passed, 1 existing skip**.
- Focused compatibility tests: **29 passed, 3 existing skips**.
- Full `mvnw.cmd test` reactor: **all nine modules passed**; the existing suite
  reported 323 tests with 18 skips across the modules.
- Packaging tests: `python -m pytest duckdb/packaging/tests -q` — **93 passed,
  11 skipped, 41 subtests passed**.
- `duckdb/packaging/package-native.sh` is now tracked executable (`100755`).

## Deferred gates

- **R134:** Java-side input snapshot quota and disk-headroom policy needs an
  explicit budget contract and filesystem tests before changing behavior.
- **R138:** graph adjacency sorting/pair-key optimization remains a hypothesis;
  benchmark it with JFR and semantic fixtures before changing the algorithm.
- Full Spark/Zingg differential coverage, clean-host packaging, SBOM/OSV
  reruns, and platform matrix validation remain release gates beyond this
  focused iteration.
