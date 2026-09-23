# Validation status

Last updated: 2026-09-23

This report distinguishes executed evidence from implemented-but-unvalidated scope.

## Executed evidence

| Area | Evidence | Result |
|---|---|---|
| Java unit/contract coverage | `./mvnw.cmd test` | 46 tests passed; 0 failures; 0 errors |
| Python control plane | `python -m unittest discover -s python/tests -v` with warnings as errors | 5 tests passed |
| JDBC lifecycle | `JdbcLifecycleProbe` against DuckDB JDBC 1.5.5.1 | Owner/TEMP/run-schema/UDF/cancellation/timeout/cleanup report completed |
| Worker protocol | Shaded worker `ping`, `status`, `count`, unsafe SQL, and `shutdown` requests | Correlation, read-only enforcement, diagnostics, and shutdown passed |
| Performance | `BenchmarkMain` at sizes 100, 500, 1000, and 2000 | Baselines recorded under `benchmarks/` |
| Package integrity | `packaging/package.ps1 -Output dist-current -CreateJre` plus `verify-package.ps1 -RequireJre` | Fresh Windows package build, dependency purity, locks, source archive, SHA-256 verification, bundled-Java metadata, and package verification passed |
| Source quality | `git diff --check`, Python syntax compilation | Passed |
| Runtime purity boundary | `packaging/check-pure-runtime.ps1 -Root .` | Passed; normal engine/runtime artifacts contain no forbidden Spark, Scala, GraphFrames, Py4J, JPype, or `zingg.*` dependencies |
| Python metadata consistency | `python -m json.tool python/dependency-lock.json` plus Python suite | `pyproject.toml` and dependency lock both declare Python 3.10+; 5 tests passed |
| Python wheel metadata | `python -m build --wheel --no-isolation` and wheel METADATA inspection | `zingg_duckdb-0.1.0-py3-none-any.whl` built successfully and declares `Requires-Python: >=3.10`; worker/JRE remain intentionally outside this control-plane wheel |
| DuckDB canary resolution | Published Maven Central lanes 1.4.5.0 and 1.4.4.0 selected; 1.5.6 and 2.0.0 were rejected as unavailable coordinates | Both versions completed full reactor package builds and passed the JDBC lifecycle probe; pinned 1.5.5.1 build restored |
| Bundled-Java launch | Packaged `runtime/java/bin/java.exe -jar worker/runtime-worker-0.1.0-SNAPSHOT.jar` | `ping`, `status`, and `shutdown` passed without using the system Java executable |
| Worker negative paths | Packaged worker malformed-operation and >8 MiB input followed by valid requests | Structured errors emitted; worker recovered and completed `ping`/`shutdown` |
| Fresh bundled-worker smoke | Packaged Java runtime with tab protocol requests `bundle-1 ping` and `bundle-2 shutdown` | `pong` and `stopping` returned with exit code 0; no system Java executable used |

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
- The build plan now distinguishes locally proven model envelope/checksum/version rejection, deterministic pair projection/file-ingress metadata, and structured worker payloads from their still-pending real-Zingg differential gates.
- Python client coverage now verifies that `max_collect_bytes` is forwarded to the default worker command; 5/5 Python tests pass.
- Added the Zingg-shaped string-splitting overload while retaining predicate split semantics; targeted metadata and structural capability tests pass, with differential parity still open.
- Arrow egress experiment: direct Arrow Java 19 `ArrowFileWriter` round-trip was attempted and rejected by the runtime with `UnsupportedOperationException: sun.misc.Unsafe or java.nio.DirectByteBuffer.<init>(long, int) not available`; the experiment was removed, so offline-safe Arrow egress remains an explicit blocker rather than an unverified claim.
- The strict compatibility profile now persists `jaroWinklerDelegatesToJaro=true` and `profileSemantics=strict-released-quirks` in both Java and packaging manifests; a regression test passes.
- Python distribution boundary remains explicit: the source wheel contains the control-plane package only, while the verified standalone bundle contains the worker JAR and private Java runtime. A plain `pip install` cannot satisfy the no-system-Java contract until a platform-wheel/bundle distribution design is selected.
- RSS observability uses `/proc/self/status` on Linux and standard `tasklist`/`ps` fallbacks on Windows/macOS; the current-platform regression test requires a positive measurement. Cross-platform CI confirmation remains a release gate.
- RSS subprocess fallbacks are bounded by a two-second timeout and forcibly terminated on timeout, preventing diagnostics from blocking the worker.
- GitHub Actions now uses `actions/setup-java@v5` and explicitly checks `test -x ./mvnw` on Unix runners, guarding the previously observed wrapper-permission failure. Hosted-matrix execution remains pending external CI evidence.
- Predicate partitioning now has the explicit `partitionByPredicate` API name, while `split(Expression)` remains a compatibility alias; the Zingg-shaped string-splitting overload is distinct. Direct matching/remaining-count coverage passes, and the complete 46-test Java suite passes.
- A fresh benchmark smoke run completed at size 1000 with 3 repetitions: startup median 30.899 ms, SQL median 1.575 ms, and graph median 91.487 ms on the current Windows/JDK 21/DuckDB JDBC 1.5.5.1 environment. This is observational evidence only; release thresholds remain unestablished.
- Backend-native blocking histograms now use a distinct `duckdb-native-0.1` profile, `0.1.0` version, and `BACKEND_BLOCKING_HISTOGRAM` model type; strict `zingg-0.7.0` blocking-tree artifacts remain separately validated. The current full 46-test Java suite passes.
- Model loading rejects mixed strict/backend profile, version, and model-type identities; this remains covered by the current 46-test Java suite.

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
