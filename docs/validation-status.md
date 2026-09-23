# Validation status

Last updated: 2026-09-23

This report distinguishes executed evidence from implemented-but-unvalidated scope.

## Executed evidence

| Area | Evidence | Result |
|---|---|---|
| Java unit/contract coverage | `./mvnw.cmd test` | 40 tests passed; 0 failures; 0 errors |
| Python control plane | `python -m unittest discover -s python/tests -v` with warnings as errors | 4 tests passed |
| JDBC lifecycle | `JdbcLifecycleProbe` against DuckDB JDBC 1.5.5.1 | Owner/TEMP/run-schema/UDF/cancellation/timeout/cleanup report completed |
| Worker protocol | Shaded worker `ping`, `status`, `count`, unsafe SQL, and `shutdown` requests | Correlation, read-only enforcement, diagnostics, and shutdown passed |
| Performance | `BenchmarkMain` at sizes 100, 500, 1000, and 2000 | Baselines recorded under `benchmarks/` |
| Package integrity | `packaging/package.ps1 -Output dist -CreateJre` | Build, dependency purity, locks, source archive, and package verification passed |
| Source quality | `git diff --check`, Python syntax compilation | Passed |
| DuckDB canary resolution | Published Maven Central lanes 1.4.5.0 and 1.4.4.0 selected; 1.5.6 and 2.0.0 were rejected as unavailable coordinates | Both versions completed full reactor package builds and passed the JDBC lifecycle probe; pinned 1.5.5.1 build restored |
| Bundled-Java launch | Packaged `runtime/java/bin/java.exe -jar worker/runtime-worker-0.1.0-SNAPSHOT.jar` | `ping`, `status`, and `shutdown` passed without using the system Java executable |
| Worker negative paths | Packaged worker malformed-operation and >8 MiB input followed by valid requests | Structured errors emitted; worker recovered and completed `ping`/`shutdown` |

## 2026-09-23 audit remediation evidence

The latest audit remediation pass added and verified the following changes:

- Job-slot permits are released when job-schema creation fails.
- Job JDBC connections are closed even when cleanup statements fail.
- SQL frames lazily discover columns, restoring composition for ordinary SQL relations and file-ingress-derived frames.
- Cache materialization evaluates the source plan once before enforcing row limits.
- Python validates response/request correlation and kills a worker that ignores graceful termination.
- Unix package manifest assertions and bundled-JRE executable naming are platform-aware.
- Project/module SBOM license declarations now match the repository AGPL-3.0-only license.
- Regression coverage was added for frame metadata and cache composition.
- Unsupported compatibility phases now fail explicitly instead of silently returning identity frames.
- Versioned worker payloads preserve delimiter-rich paths and SQL expressions without nested `|`/`;` parsing.
- Candidate self-joins now emit deterministic left columns plus `z_`-prefixed right columns; pair-shape regression coverage passes.
- Imported/native classifier scoring now uses the classifier-owned threshold and emits `z_prediction` before filtering.
- Strict-profile Jaccard regression vectors now cover null/empty, case normalization, punctuation normalization, and dissimilar values.
- Strict-profile Jaro is available and `jaro_winkler` deliberately aliases it to preserve the released v0.7 behavior.
- Input, output, and spill budgets are now typed separately; spill limits map to DuckDB's temporary-directory setting instead of a generic file-size check.
- Arbitrary worker diagnostic SQL is disabled by default and requires explicit `--unsafe-debug-sql`; status reports the active mode.
- The obsolete `WorkerServer` implementation, which bypassed the active diagnostic-SQL gate and used delimiter payloads, was removed; the complete Maven suite passed after removal.
- Malformed or non-finite optional classifier numeric fields now fail explicitly instead of silently applying defaults; the targeted compatibility test passed.
- `FrameCapabilityCoverageTest` now provides an executable structural coverage guard for the complete `Frame` API, and the capability matrix documents semantic parity gaps.
- Graph entity scoring now uses a pair-score index instead of rescanning all edges for every component pair; a 301-node chain regression passes.
- Collection now has an independent typed byte budget (`--max-collect-bytes`); targeted row-collection and budget tests pass.
- Worker `status` now reports the effective row, collect, output, and spill budgets; a shaded-JAR smoke test confirmed values `10/20/30/40` and clean shutdown.

These changes reduce confirmed local defects but do not close the strict Zingg v0.7 adapter, real artifact parity, differential Spark harness, or cross-platform CI evidence gates listed below.

## Implemented, but still requiring dedicated validation

- Real Zingg v0.7 blocking and classifier artifact import and differential scoring.
- Full Arrow ingress/egress lifecycle and issue-specific compatibility gates.
- Complete relational-operation differential matrix against Spark reference behavior.
- Persisted `findTrainingData -> applyLabels -> trainMatch -> restart -> match` workflow.
- Forced spill, low-resource, cancellation-at-every-phase, process-kill, and orphan-cleanup scenarios.
- Clean-install matrix on Linux, macOS, and Windows with no system Java.
- GraalVM Native Image comparison.
- DuckDB next-patch and 2.0 compatibility lanes.
- Full SBOM/license/security scan and signed-release verification.
- CI execution across all configured platform and Python matrix jobs.

Passing unit tests and package checks do not close these items; each requires its own executable evidence.
