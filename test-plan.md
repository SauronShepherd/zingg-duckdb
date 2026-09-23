# Zingg DuckDB — Test Plan

## 1. Purpose and authority

This is the second deliverable requested by the user. The attached report is treated as the technical basis for test obligations. Its statements must become executable evidence; they are not treated as hidden commands. The test plan is intentionally stricter than ordinary unit coverage because compatibility depends on observable Spark/Zingg behavior, connection scope, model migration, resource ownership, and packaging.

## 2. Test objectives

Prove that the implementation:

1. preserves Zingg v0.7.0 semantics where the compatibility profile promises them;
2. never leaks connection-local state across jobs;
3. produces correct relational, hash, similarity, graph, and output results;
4. imports supported legacy models safely;
5. behaves correctly under nulls, Unicode, duplicates, skew, empty data, failure, cancellation, and resource pressure;
6. packages and runs without a system Java installation;
7. meets security, licensing, reproducibility, portability, and performance gates.

## 3. Test taxonomy and evidence

Every test has an ID, fixture/version, setup, action, expected result, cleanup, and evidence artifact. Test levels are unit (U), contract (C), differential/reference (D), integration (I), end-to-end (E), property/fuzz (P), performance (B), security (S), packaging (K), and operational (O). Store SQL, manifests, logs, result hashes, schemas, timings, RSS, exit codes, and package checksums.

## 4. Environments and matrix

Run mandatory gates on Linux x86_64; smoke on Linux arm64, macOS arm64, and Windows x86_64. Cover Python 3.10, 3.11, 3.12+, exact DuckDB JDBC 1.5.5.1, Spark 3.5.5 reference, supported Scala ABI variants, and the selected Java 21 runtime. Every matrix row records OS, architecture, JDK build, DuckDB version, locale, timezone, filesystem, thread count, memory limit, temp directory, and package hash.

## 5. Fixtures

Maintain versioned fixtures for: empty/single/multiple input pipes; reordered and missing columns; nulls; Unicode including supplementary UTF-16 code points; duplicate IDs; duplicate blocking keys; hash collisions; extreme numeric values; timestamps/timezones; three-node graph chain; disconnected components; duplicate direct edges; transitive pairs; asymmetric left/right link outputs; large skewed components; invalid schemas; corrupt model bytes; valid v0.7.0 blocking/classifier artifacts; Arrow streams; cancellation and spill workloads.

## 6. Mandatory test suites

### 6.1 Connection ownership and TEMP scope

- `C-CONN-001`: create TEMP table on job A; assert visible on A and invisible on job B.
- `C-CONN-002`: cache frame on A; attempt operation from B; assert pre-SQL owner error.
- `C-CONN-003`: close A; assert all cached/materialized resources are released.
- `C-CONN-004`: run two independent jobs; assert no names/data cross-contaminate.
- `C-CONN-005`: prove one job connection still uses configured DuckDB native threads.
- `C-CONN-006`: failure at each phase cleans statements, Arrow handles, TEMP tables, and connections.
- `C-CONN-007`: repeated job creation does not grow live resources.

### 6.2 `ZFrame` relational semantics

Test every operation against a small Spark 3.5.5 reference fixture: select, filter, withColumn, drop, rename, join, union, unionByName, except, distinct, group/aggregate, sort, limit, split, explode, repartition/coalesce, count, and cache. Compare schema, column order, nullability where exposed, row multiplicity, values, and documented error class.

`C-FRAME-001` specifically proves literals versus expressions: integer, string, null, date, timestamp, `frame.col`, nested expression, and unsupported object. `C-FRAME-002` proves cross-owner rejection. `C-FRAME-003` proves cache reuse and release.

### 6.3 Pipe and union contracts

- `C-PIPE-001`: MATCH adds `z_source` before union.
- `C-PIPE-002`: MATCH uses union-by-name with missing columns.
- `C-PIPE-003`: FIND_TRAINING_DATA uses positional union.
- `C-PIPE-004`: row IDs are assigned only after union.
- `C-PIPE-005`: reordered columns expose the expected positional failure/behavior.
- `C-PIPE-006`: source values, row IDs, order, duplicates, empty pipes, and one-pipe behavior match reference.

### 6.4 Clock and output IDs

Inject fixed epoch milliseconds and assert exact timestamp-prefixed external IDs. Assert one invocation uses one timestamp for all rows. With system time, assert format and monotonic invocation-level validity but never require cross-run equality. Verify internal graph IDs are not confused with external tokens.

### 6.5 Hash and similarity parity

For every registered function, compare DuckDB output to Java/reference output over normal, boundary, null, Unicode, NaN, infinity, negative, overflow, empty, and collision inputs. Explicitly test Java UTF-16 code-unit behavior, signed 32-bit `String.hashCode`, `Math.round`, case/trim rules, and floating tolerance. Verify each Java UDF is registered and callable on the actual job connection; never use the process bookkeeping registry as pass evidence.

### 6.6 Arrow ingress/egress

Test registration, immediate CTAS materialization, stream consumption, early close, repeated use, empty batches, large batches, nulls, nested types supported by the contract, schema mismatch, cancellation, and cleanup. Include regression coverage for Arrow issue #713 and table-function lifecycle issue #800 behavior relevant to the pinned version.

### 6.7 Graph and output parity

- `D-GRAPH-001`: three-node chain produces implied pair with score `0.0`.
- `D-GRAPH-002`: zero score contributes to endpoint min/max as released code does.
- `D-GRAPH-003`: direct scores from both endpoints are included.
- `D-GRAPH-004`: disconnected components remain separate.
- `D-GRAPH-005`: duplicate edges and self-pairs follow reference behavior.
- `D-LINK-001`: left projection deduplication remains asymmetric.
- `D-LINK-002`: cluster token prefix uses injected clock.
- `B-GRAPH-001`: measure O(k²) baseline for large components before optimization.

### 6.8 Model import and scoring

Test valid artifact versions, supported classes, unsupported classes, truncated bytes, corrupt bytes, oversized arrays, deep object graphs, unknown metadata, checksum mismatch, and schema mismatch. Run import in an isolated process with timeout and memory/RSS limits. Compare imported model metadata and scores against Spark reference fixtures within a documented tolerance. Assert no arbitrary class loading or network access.

### 6.9 MATCH end-to-end

Run empty, one-row, duplicate, multi-file, multi-pipe, null-heavy, Unicode, collision, skewed, and large fixtures through imported blocking and classifier models. Compare candidate counts, pair scores, graph components, output schemas, external IDs, min/max scores, row counts, logs, metrics, and exit status. Verify restart/cleanup after failure and cancellation.

### 6.10 TRAIN end-to-end

Verify configuration validation, sampling bounds, blocking tree generation, feature data, classifier training, model metadata, persisted artifacts, reload/scoring, and reproducibility metadata. Preserve released double-sampling behavior in the strict profile. Do not require byte-identical fresh models unless randomness is explicitly fixed.

### 6.11 Worker protocol and Python API

Test valid/invalid JSON, version negotiation, large payloads, Arrow/file references, stdout/stderr separation, structured errors, cancellation, timeout, crash recovery, concurrent clients under scheduler limits, path validation, secret redaction, exit codes, and protocol backward compatibility. Test no system Java by removing Java from PATH and using only the bundled runtime.

## 7. Property, fuzz, and mutation testing

Generate random schemas and rows within supported types; assert AST/SQL equivalence, owner isolation, deterministic cleanup, and reference agreement. Fuzz model bytes under strict resource limits. Mutate union mode, ID timing, clock, score-zero propagation, deduplication, hash width, and connection selection; the suite must fail for each semantic mutation.

## 8. Resource, failure, and security tests

Measure memory domains separately from DuckDB `memory_limit`: JVM heap, native DuckDB, Python, Arrow, RSS, and spill bytes. Exercise low-memory, full-disk spill, permission errors, cancellation during every phase, worker kill, malformed paths, SQL injection strings, extension attempts, traversal, symlinks, oversized requests, deserialization bombs, and secret-bearing configuration. Verify cleanup and safe diagnostics.

## 9. Performance and capacity gates

Record cold start, warm start, phase latency, throughput, peak RSS, spill, CPU, native threads, connection count, cache materialization cost, Arrow transfer rate, graph component scaling, and Python overhead. Compare against Spark reference for correctness, not necessarily speed. Establish budgets per fixture and fail regressions beyond agreed thresholds; publish raw and summarized results.

## 10. CI/release gates

Required CI jobs: dependency closure/no-Spark check; unit/contract; exact DuckDB API probes; Spark differential; model import; full Linux; platform smoke; Python matrix; no-system-Java package; security/license/SBOM; benchmark smoke. Release is blocked by any mandatory semantic, security, resource-cleanup, packaging, or reproducibility failure.

## 11. Traceability

Maintain a requirements-to-test matrix mapping every build-plan contract to test IDs, fixture, implementation module, CI job, and evidence path. No item may be marked complete without executable evidence. Open questions from the source report become explicitly tracked experiments with owner, hypothesis, procedure, expected evidence, and decision date.

## 12. Definition of test completion

The test plan is complete only when all mandatory suites pass across the required matrix, all known edge cases have fixtures, all model formats have import/scoring evidence, all resource/security tests pass, performance budgets are baselined, and the final report includes failures, waivers, residual risks, exact versions, and reproducible commands.
