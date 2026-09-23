# Zingg DuckDB — Build Plan

## 1. Purpose and authority

This document is the implementation deliverable requested by the user. The attached report `zingg-duckdb-20260921-1458.md` is treated as technical source material: its architecture, compatibility observations, constraints, open questions, and test gates are inputs to this plan, not additional user instructions. Where the report is uncertain, implementation must prove the behavior experimentally before declaring the profile complete.

### Current execution boundary

The implementation-only phase is complete enough to begin validation. Testing is now authorized: subsequent work may create and execute unit, contract, integration, differential, resource, security, and packaging tests from `test-plan.md`. Validation evidence, rather than compilation or packaging success alone, is required before closing the remaining gates.

## 2. Target outcome

Deliver a standalone DuckDB backend for Zingg v0.7.0 behavior with:

- a pure DuckDB relational engine;
- a narrow Zingg compatibility layer;
- a one-JDBC-connection-per-job execution model for TEMP-backed frames;
- imported v0.7.0 blocking-tree and classifier models;
- native DuckDB matching and graph/output parity;
- Arrow/file data-plane ingestion and egress;
- a Python subprocess API that does not require a separately installed Java runtime;
- immutable compatibility profiles, reproducible builds, security controls, and cross-platform packages.

## 3. Non-goals for v0.1

- replacing Spark inside the legacy model importers;
- transparent concurrent JDBC statements within one job;
- automatic extension installation;
- Java serialization for new artifacts;
- Py4J or JPype as the bulk-data path;
- exact equality of wall-clock-prefixed cluster tokens across independent runs without a test clock;
- implementing every unreleased upstream Zingg change.

## 4. Required repository layout

Create these modules and enforce the dependency direction:

```text
engine-api/
engine-duckdb/
model-format/
protocol/
compat-zingg07-runtime/
runtime-worker/
python/
legacy-blocking-import-zingg07-spark35/
legacy-classifier-import-zingg07-spark35/
reference-spark35-tests/
fixtures/
packaging/
docs/
benchmarks/
```

`engine-api`, `engine-duckdb`, `model-format`, and `protocol` must not import Spark, GraphFrames, Scala, Py4J, JPype, or `zingg.*`. Spark is allowed only in legacy importers and reference tests. Every module gets its own unit/integration test source set and explicit dependency-lock metadata.

## 5. Architecture and contracts

### 5.1 Job and connection ownership

Implement `DuckRuntime`, `DuckJobContext`, `JobHandle`, and `DuckFrame`.

1. Open one root/pinning connection per runtime/database instance.
2. Create exactly one execution connection per `DuckJob` in the initial mode.
3. Distinguish connection-local state from worker-shared state. TEMP tables, TEMP views, registered Arrow streams, statements, and cancellation handles are connection-local. Reusable frames that may cross connections must be materialized as ordinary tables/views in a run-private `_zingg_run_<runId>` schema.
4. Store an opaque owner token in every frame and materialization.
5. Reject joins, unions, excepts, and other binary operations across different owners before SQL generation.
6. Serialize jobs per worker initially; expose a scheduler limit before enabling multiple jobs.
7. Close statements, Arrow resources, TEMP objects, job connections, and root connections in deterministic `try/finally` order.
8. Track relation scope explicitly (`QUERY_ONLY`, `CONNECTION_LOCAL`, `WORKER_SHARED`, `PERSISTED`) and refuse to move connection-local handles across connections.
9. Reserve ordinary per-job schemas for reusable state and serialize catalog mutations through the scheduler.

### 5.2 Relational core

Implement `DuckRelation`, `DuckSchema`, `DuckExpr`, `DuckRow`, `MaterializedRef`, and the `ZFrame<D,R,C>` adapter. The minimum operation set is:

`select`, `selectExpr` (allowlisted), `filter`, `withColumn`, `drop`, `rename`, `join`, `union`, `unionByName`, `except`, `distinct`, `groupBy`, aggregations, `sort`, `limit`, `split`, `explode`, `repartition`, `coalesce`, `cache`, `count`, `collect`, schema inspection, and output writing.

Implement typed AST nodes rather than concatenating user SQL. Quote identifiers, bind literals, validate operators, and reject arbitrary function/class names.

### 5.3 Expression/literal coercion

Mirror Spark `functions.lit` behavior:

- primitive/string/date/timestamp values become literals;
- `DuckExpr` values remain expressions;
- `withColumn("x", frame.col("id"))` copies a column reference;
- nulls retain an explicit logical type where required;
- unsupported values fail with a typed diagnostic.

### 5.4 Cache semantics

Implement eager CTAS materialization into a connection-local TEMP table only for strictly connection-affine scratch. `cache()`/checkpoint-like operations that can outlive one statement or be consumed by another execution connection must promote the relation into the run-private worker-shared schema, with schema capture, reference counting, idempotent release, and cleanup on failure. Do not execute a connection-local handle on another connection.

### 5.5 Zingg context compatibility

Implement the `Context<S,D,R,C,T>` composition root with DuckDB equivalents for configuration, pipes, frame factory, output writers, model readers, clock, and logger. Keep Zingg common-client/core dependencies isolated in `compat-zingg07-runtime`.

### 5.6 Pipe and input semantics

Implement `DuckPipeReader` with explicit modes:

- MATCH: add `z_source` per input before `unionByName(..., allowMissingColumns=true)`;
- FIND_TRAINING_DATA: positional union matching the released overload;
- assign row IDs only after all pipes have been combined;
- preserve source order and failure behavior;
- return cached frame according to phase contract.

### 5.7 Clock and identifiers

Implement `CompatibilityClock`. Production uses epoch milliseconds. Tests inject a fixed clock. Preserve the distinction between internal graph IDs and external timestamp-prefixed cluster tokens.

### 5.8 Set and row-operation details

Implement Spark-compatible behavior for `filterInCond` as a join where multiplicity matters, `split`/`explode` projection behavior, positional versus by-name unions, duplicate handling, nulls, type coercion, and asymmetrical link-output deduplication.

### 5.9 Compatibility capsule and upstream provenance

Generate a reproducible `compat-zingg-0_7` capsule from the exact Zingg v0.7.0 tag rather than depending on an unavailable or mixed Spark assembly. The capsule must:

- capture the upstream commit/tag, source manifest, SHA-256 hashes, license and notice files;
- keep Scala 2.12 leakage isolated from the pure engine;
- fail the build if Spark, GraphFrames, Py4J, JPype, or `zingg.*` enters a pure-engine runtime graph;
- record every local patch and regenerate the capsule deterministically;
- provide a compatibility adapter per released Zingg line instead of binding to unreleased branches or pull requests.

### 5.10 Labels and remote application boundary

Define an internal language-neutral `LabelDecisionProvider`/`ApplyLabels` protocol for pending-label retrieval and label application. The v0.7 adapter must emulate the semantics without depending on unreleased upstream label APIs. Include request IDs, schema/version fields, idempotency keys, rejection diagnostics, and a bounded batch contract. Bind directly to a future upstream label interface only after it lands in a tagged release.

## 6. Implementation phases and exit criteria

### Phase 0 — proof spikes

Build the exact DuckDB JDBC 1.5.5.1 dependency. Prove TEMP visibility, duplicate-connection behavior, run-schema visibility, UDF catalog scope, Arrow registration lifecycle, cancellation, query timeout/progress, and Java 21 runtime loading. Record whether every required registration is connection-local or instance-visible. Exit only with executable probes checked into `reference-spark35-tests`/`engine-duckdb` and CI results.

### Phase 1 — neutral contracts and relational core

Create module skeletons, dependency rules, AST, schema/value model, connection ownership, frame operations, cache, SQL renderer, diagnostics, and resource lifecycle. Exit when all unit tests and cross-owner rejection tests pass.

### Phase 2 — pipe I/O and Arrow

Implement file readers/writers, Arrow registration-to-CTAS ingress, Arrow egress, source tagging, phase-specific union, post-union IDs, schema validation, and issue-#713 regression coverage.

### Phase 3 — hashes and similarities

Implement the complete required function registry. Preserve Java UTF-16, signed 32-bit `String.hashCode`, `Math.round`, null, Unicode, numeric, and floating-point semantics. Use Java UDFs only where SQL cannot prove parity; register and verify each function on the job connection.

### Phase 4 — blocking tree

Implement the released training behavior, including known double-sampling and signed 32-bit blocking-key behavior. Add serialization-free native model format and deterministic test fixtures.

### Phase 5A — blocking migration

Build a separate Spark 3.5.5 legacy importer. Enforce class allowlist, object-size/depth/reference limits, timeout, isolated process, symlink/path checks, non-zero-exit handling, and provenance. Read the released `Tree<Canopy<Row>>` blocking artifact only inside that process, extract field context/hash identity/tree edges, and convert to a versioned neutral model format. Add an upstream fixture manifest and reject unknown serialized model types.

### Phase 5B — classifier migration/scoring

Import supported v0.7.0 classifier artifacts through the isolated Spark process, validate feature metadata, implement DuckDB scoring, and record unsupported model types explicitly.

### Phase 6 — graph and output parity

Implement connected components, direct edges, implied transitive pairs, zero-score propagation, min/max aggregation, external cluster IDs, and LinkOutputBuilder asymmetry. Keep the straightforward O(k²) algorithm until equivalence is proven.

### Phase 7 — imported-model MATCH

Wire configuration, input reading, blocking, candidate generation, classifier scoring, graph/link output, persistence, metrics, cancellation, and CLI/API diagnostics. This is the first production milestone.

### Phase 8 — native TRAIN

Implement sampling, blocking-tree training, feature generation, classifier training, model validation, artifact writing, and reproducibility metadata. Treat fresh model byte equality as non-goal unless all randomness is controlled.

### Phase 9 — Python and runtime packaging

Implement JSON request/response protocol, subprocess worker, Arrow/file handoff, structured errors, cancellation, log separation, project-bundled Java 21 `jlink` runtime, pinned `jdk4py` Java-21 line, platform launchers, and no-system-Java checks. In parallel, prototype a GraalVM Native Image worker using DuckDB JDBC 1.5.5.1 tracing/reachability metadata; compare JVM/native behavior, startup, RSS, artifact size, reflection coverage, and model loading before making native packaging a default.

### Phase 10 — hardening and release

Complete security, license/SBOM, dependency and CVE review, performance/resource budgets, platform packages, upgrade/migration docs, compatibility-profile manifest, release signatures, rollback package, and final gate review. Include clean-install/offline-install gates, source/provenance attestations, signed `SHA256SUMS`, and explicit JDK/Native Image/vendor security-update policy.

## 7. Build and dependency controls

- Pin Zingg v0.7.0, DuckDB JDBC 1.5.5.1, Spark 3.5.5, Scala ABI, Arrow, JDK vendor/build, Python versions, and native artifacts.
- Rebuild common client/core artifacts reproducibly instead of using the mixed Spark assembly as the normal dependency boundary.
- Verify no Spark classes enter pure-engine runtime jars.
- Generate SBOMs and license notices for every distributable.
- Use reproducible timestamps/order where the artifact format permits.
- Run API probes against the exact pinned DuckDB artifact, not current online documentation.
- Generate and verify `upstream-manifest.json` for the compatibility capsule; fail on source drift or unreviewed patches.
- Maintain a dependency ban report for pure-engine jars and inspect shaded jars/classes, not only Maven declarations.
- Set both DuckDB `memory_limit` and `max_temp_directory_size`; measure process RSS separately because buffer-manager memory is not total process memory.
- Keep one DuckDB database instance per worker; cap concurrent jobs and avoid multiplying native thread pools through independent instances.
- Maintain JVM-bundled and Native Image dependency locks separately, including reachability metadata and platform-specific native inputs.

## 8. Security and operational requirements

Canonicalize configured paths; reject traversal and remote schemes by default; bind literals; allowlist expressions; disable auto-install; pin extension hashes; redact secrets; cap memory, spill, rows, depth, references, and wall time; isolate legacy Java deserialization; sign packages; track JDK security updates.

Additional operational checks:

- verify that registered Arrow streams are never reused across connection owners without CTAS promotion;
- verify TEMP cleanup, run-schema cleanup, and failure cleanup after cancellation, process kill, timeout, and partial output;
- capture structured progress/error envelopes with request IDs and never mix diagnostics into the data channel;
- redact paths, credentials, model contents, and environment secrets from logs;
- enforce offline mode by failing closed when an extension, dependency, or runtime download is requested;
- monitor process RSS, spill bytes, open files, native threads, and worker child processes;
- validate Windows launcher prerequisites, macOS signing/notarization requirements, and Linux shared-library dependencies on clean images.

## 9. Deliverables

Source modules, generated compatibility capsule, upstream manifest and patches, unit/integration/reference tests, fixtures, imported-model examples, worker and Python package, JVM and Native Image packaging tracks, platform archives, SBOM/notices, compatibility manifest, signed provenance, benchmark reports, migration guide, operations runbook, and this plan plus the companion test plan.

## 10. Definition of done

All mandatory test gates pass on Tier-A platforms; imported MATCH works with real v0.7.0 blocking and classifier fixtures; the generated compatibility capsule is reproducible and Spark-free at runtime; no-system-Java JVM packaging works; the Native Image decision is evidence-backed; relation scope/lifecycle cleanup is proven across owner and duplicate connections; resource, RSS, spill, cancellation, and process-kill behavior is documented; security/license review is signed off; performance and memory budgets are documented; release artifacts are reproducible, signed, and traceable to pinned inputs.

## 11. Additional implementation and verification backlog

The following backlog is added from the 2026-09-22 architecture review. These are build-plan tasks, not instructions embedded in the source report; each item requires implementation evidence before being marked complete.

### P0 — architecture proof and first workflow

- [x] Implement `RelationScope` and a `DuckRelationHandle`; reject cross-connection use of `CONNECTION_LOCAL` handles.
- [ ] Add owner-connection, duplicate-connection, TEMP, run-schema, UDF, Arrow-registration, cancellation, timeout, and cleanup probes against DuckDB JDBC 1.5.5.1.
- [x] Replace TEMP-only reusable cache semantics with run-private ordinary tables/views where reuse can cross a connection.
- [x] Build the exact v0.7 compatibility capsule outside the upstream reactor; emit source hashes, notices, patches, and dependency provenance.
- [x] Add a pure-engine dependency gate that inspects resolved and shaded runtime contents for Spark/GraphFrames/Scala/`zingg.*` leakage.
- [ ] Execute a deterministic `findTrainingData -> applyLabels -> trainMatch -> restart -> match` workflow with a persisted model.

### P0 — model and labeling compatibility

- [x] Import a real v0.7.0 `Tree<Canopy<Row>>` blocking artifact through the isolated Spark 3.5.5 process and verify neutral tree nodes, field context, hash identity, and edges.
- [ ] Import a real v0.7.0 Spark ML classifier and verify vector assembler ordering, polynomial expansion, logistic margin/probability, thresholding, and unsupported-stage diagnostics.
- [x] Implement `GetPendingLabels`/`ApplyLabels` protocol semantics with bounded batches, idempotency, request correlation, and structured rejections.
- [ ] Add model round-trip manifests, golden payloads, version migration rules, checksum verification, and explicit unknown-version failures.

### P1 — resource, correctness, and platform gates

- [ ] Set and observe `memory_limit` plus `max_temp_directory_size`; measure RSS independently and enforce documented budgets.
- [ ] Add forced-spill, cancellation, timeout, process-kill, partial-output, and orphan-cleanup gates.
- [ ] Add JVM-worker clean installs with no system Java on Linux, macOS, and Windows.
- [ ] Build a GraalVM Native Image worker with tracing/reachability metadata; compare outputs, model loading, startup, RSS, size, and diagnostics against JVM worker.
- [ ] Add Linux amd64/arm64, macOS x86_64/arm64, and Windows x86_64 release jobs, including offline installation and launcher prerequisites.
- [ ] Add signed provenance attestations, source archive verification, SBOM/license review, rollback package, and JDK security-update tracking.

### P2 — compatibility and ecosystem backlog

- [ ] Maintain a MatchType/function inventory and a complete ZFrame operation matrix, including null/type coercion, ordering, sampling, explode/split, and duplicate semantics.
- [ ] Add graph-clustering parity fixtures for connected components, implied pairs, zero-score propagation, min/max aggregation, cluster IDs, and LinkOutput asymmetry.
- [ ] Add DuckDB next-patch canary and DuckDB 2.0 prequalification lanes without making snapshots release blockers.
- [ ] Add extension-backed connectors only behind explicit offline/allowlist feature flags.
- [ ] Add explain/profiling capture, benchmark history, and regression thresholds for runtime, memory, spill, and output size.
- [ ] Prepare narrowly scoped upstream compatibility RFCs/issues only after a backend failure demonstrates the required abstraction.

## Audit addendum — 2026-09-23 specification reconciliation

The attached repository audit is authoritative for the next implementation pass. Its requirements are grouped below so implementation tasks remain traceable to executable evidence.

### Completed during this pass

- [x] Release job permits when job-schema creation fails.
- [x] Always close job JDBC connections even when table/schema cleanup reports an error.
- [x] Discover authoritative SQL-frame columns lazily so `sql(...).select(...)`, `withColumn`, `drop`, and `rename` compose correctly.
- [x] Materialize cache plans once, then enforce row budgets against the materialized relation.
- [x] Validate Python response correlation IDs and escalate worker termination to kill after timeout.
- [x] Correct Unix CI manifest assertions and make JRE verification/metadata platform-aware.
- [x] Correct generated project/module SBOM license declarations to AGPL-3.0-only.
- [x] Add regression tests for lazy frame metadata and cache composition.

### P0 implementation gates

- [ ] Add `adapter-zingg07` compiled against the exact Zingg v0.7 generic contracts while keeping `engine-*` Spark-free.
- [ ] Produce an executable ZFrame method-coverage report and implement capability-gated methods, including true string-splitting semantics.
- [ ] Replace identity phase executors with explicit supported/unsupported behavior and complete FIND_TRAINING_DATA, labeling, TRAIN, MATCH, and LINK orchestration.
- [ ] Repair ordinary file-ingress metadata and implement deterministic Zingg pair projection with `z_`-prefixed right-side fields.
- [ ] Separate strict `zingg07-blocking-tree-v1` artifacts from backend-native blocking histograms; validate a real upstream tree.
- [ ] Implement strict v0.7 similarity vectors and a persisted quirk registry, including Jaccard and released Jaro/Jaro-Winkler behavior.
- [ ] Validate real v0.7 classifier artifacts, preserve vector ordering, and make imported thresholds authoritative.
- [x] Remove the unused legacy worker path with arbitrary SQL; the active worker keeps diagnostics behind an explicit unsafe-debug mode.
- [ ] Replace delimiter-based nested worker payloads with structured versioned messages.
- [ ] Make Python installation discover a worker and private Java runtime on at least one supported platform.

### P1 correctness, performance, and release gates

- [ ] Replace extension-dependent Arrow egress with an offline-safe JDBC/Arrow IPC writer and qualify vectorized Arrow ingress.
- [ ] Replace `max-spill-bytes` with typed input/output/collect/temp-directory budgets mapped to DuckDB settings.
- [x] Add byte-bounded collection and typed collect limits; optimize repeated graph closure edge scans and retain the 301-node chain regression benchmark.
- [ ] Add forced-spill, low-resource, cancellation, process-kill, orphan-cleanup, and concurrent-job tests.
- [ ] Generate a complete transitive SBOM and run license/security review before release.
- [ ] Run clean-install/no-system-Java validation on Linux, macOS, and Windows.

### External evidence gates

- [ ] Obtain real Zingg v0.7 blocking/classifier artifacts and Spark differential fixtures.
- [ ] Confirm the intended integration boundary: standalone repository versus main Zingg `home/duckdb` module.
- [ ] Execute the full CI matrix on hosted Unix runners after executable-mode repair.
- [ ] Decide the distribution contract for legacy Spark importers and any external Spark prerequisite.
