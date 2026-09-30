# Remaining Build and Validation Plan

**Repository:** Zingg DuckDB  
**Baseline reviewed:** 2026-09-30  
**Purpose:** Consolidate work that remains open in `build-plan.md`, audit reconciliation, coverage matrices, and `docs/validation-status.md`. This is a pending-work plan, not a claim that every item is a defect or belongs to the same release.

## Scope and status rules

- This plan covers work in this repository. Upstream Zingg, Spark Connect, GraphFrames, and SecondString source changes are out of scope unless separately authorized.
- A checkbox may be closed only when its stated artifact/evidence exists, is reproducible, and satisfies its acceptance criteria. A local focused test does not close hosted-platform, compatibility, legal, or release gates.
- Classify an item as **implementation**, **test/evidence**, **external decision/input**, or **release policy**. Do not report externally blocked work as implemented.
- Preserve the distinction between native DuckDB behavior, Zingg v0.7 compatibility, the experimental/native backend, and packaged-worker guarantees.

## Priority and dependency overview

1. **P0 — Define contracts / obtain external inputs:** integration boundary; approved blocking/model artifacts and goldens; support/release decisions. Several later compatibility tasks cannot be honestly completed without these.
2. **P1 — Close correctness and safety gaps:** output resource caps, graph-pair expansion budget, similarity edge semantics, imported-model end-to-end parity, Arrow and worker cancellation/recovery contracts.
3. **P2 — Complete compatibility evidence:** ZFrame per-overload differentials, full workflow/phase goldens, clean cross-platform CI and installer qualification.
4. **P3 — Performance and release readiness:** representative benchmark budgets, reproducible platform reports, signed provenance, SBOM/license/CVE review, JDK lifecycle ownership.
5. **Final gate:** serial/isolated full regression, package verification on the declared support matrix, traceable evidence index, and explicit limitations/support statement.

External decisions in P0 should be requested first and tracked with an owner, request date, requested artifact/decision, fallback, and exit condition. Engineering work that does not depend on them can proceed in parallel.

## P0 — Contracts and external prerequisites

### P0.1 Integration and support contracts

- [ ] Record the intended distribution boundary: standalone repository or `home/duckdb` module in the main Zingg repository. Document repository/module layout, build ownership, versioning, dependency direction, and how consumers discover/install the backend.
- [ ] Decide the contract for legacy Spark importers and any Spark prerequisite: supported formats, whether Spark is required at build/runtime/import time, supported versions, and failure behavior when absent.
- [ ] Define the supported operating systems, CPU architectures, JDK/runtime policy, Python versions, and whether GraalVM Native Image is a supported release target or an experiment.
- [ ] Define supported-vs-unsupported phase behavior for FIND_TRAINING_DATA, labeling, TRAIN, MATCH, and LINK. Unsupported paths must return explicit actionable errors; no identity/no-op executor may appear successful.
- [ ] Define public output schemas, deterministic ordering guarantees (or explicitly unordered behavior), nullability expectations, artifact compatibility, and which compatibility level is promised: native behavior versus exact Zingg v0.7 parity.

**Acceptance:** decisions are recorded in a reviewed architecture/support document and reflected in CLI/API docs, package metadata, and CI target matrix.

### P0.2 Obtain approved upstream artifacts and semantic goldens

- [ ] Resolve EXT-02 provenance/authority for the historical `zingg.block` artifact. Request a maintainer-confirmed producer/version and intended target contract; existing byte identity in older test trees is not sufficient evidence.
- [ ] Obtain a compatible approved blocking artifact or an approved historical source plus a field-by-field migration specification. Do not relax the deserialization filter or patch serialized descriptors speculatively.
- [ ] Obtain approved Zingg v0.7 blocking/classifier model fixtures and expected neutral representations, including tree nodes, field context, hash identity/edges, classifier stages/features/weights, thresholds, and expected scored pairs/entities.
- [ ] Record the Spark/Zingg version, producer command, environment, input files/hashes, model hashes, output schema, row ordering/multiset semantics, and expected output hashes for every golden.
- [ ] Request explicit approval for intentional compatibility deviations, especially null/blank similarity scores and DuckDB-vs-Spark nullability or numeric representation differences.

**Acceptance:** immutable, provenance-bearing fixtures and expected outputs are checked in (or fetched through an approved reproducible process); tests consume those goldens without deserializing untrusted Java object streams outside the existing isolated/filter boundary.

## P1 — Correctness, safety, and behavior closure

### P1.1 Bound disk/output resource use (audit ZD-008)

- [ ] Specify independent limits for final published output bytes, temporary output bytes, DuckDB spill bytes, row counts, and per-request duration. Define whether quotas are hard limits or best-effort observations.
- [ ] Select an enforceable mechanism per deployment: bounded streaming writer, filesystem/container quota, or another mechanism that stops writes before exhausting the host. A post-write size check and atomic rename are not a disk-consumption limit.
- [ ] Apply limits to every supported output route, including CSV/Parquet/Arrow/model/pipeline artifacts and worker-mediated exports; define temp-file placement and cleanup.
- [ ] Ensure quota failure cancels the DuckDB query, removes staging files, closes registrations/connections, preserves any previous destination, and returns a structured error with request context.
- [ ] Add deterministic fault injection for quota exhaustion during write, spill exhaustion, cancellation during finalization, disk-full/short-write, and atomic publication failure.
- [ ] Measure peak temporary+spill consumption independently from final output size; verify behavior under concurrent jobs and process termination.

**Acceptance:** each output path demonstrates bounded consumption (not just bounded final artifact size), no partial destination publication, bounded cleanup, and repeatable Windows/Linux test evidence. Document limits and residual platform guarantees.

### P1.2 Bound explicit graph expansion (audit ZD-003)

- [ ] Specify `transitiveEdges` contract: expected maximum component size/output pairs, whether a caller may stream results, and behavior at the limit.
- [ ] Add a configurable pair/output budget with overflow-safe arithmetic and fail-before-materialization behavior; alternatively expose a streaming iterator/API and retain the current materializing method only with a documented hard cap.
- [ ] Ensure cancellation checks occur during traversal and that errors do not leave partially published output.
- [ ] Test path, star, dense, disconnected, duplicate/reversed-edge, self-edge, zero-score, and giant-component graphs. Include pair-count boundaries `limit-1`, `limit`, and `limit+1` without allocating the over-limit result.
- [ ] Benchmark memory, latency, and output bytes separately for `entityScores` (sparse O(V+E)) and explicit pair expansion.

**Acceptance:** `entityScores` remains sparse; all public pair-expansion paths have a documented and enforced resource contract and scale/fail tests.

### P1.3 Close similarity compatibility (audit ZD-007)

- [ ] Generate a differential corpus against the exact pinned SecondString/Zingg dependency for punctuation, whitespace, letter/digit boundaries, repeated tokens, token collisions, Unicode normalization/case folding, Turkish/default locale, empty, whitespace-only, and null inputs.
- [ ] Run under at least the default locale and Turkish locale; make locale dependence explicit or replace it with the approved locale-independent behavior only if parity contract permits.
- [ ] Compare scores and downstream threshold decisions, not only token sets. Capture exact dependency versions and corpus hashes.
- [ ] Obtain an explicit decision on blank/null behavior: preserve local score `1`, match upstream `NaN`, or define another approved compatibility rule. Add tests for the selected decision and explain downstream MATCH behavior.
- [ ] Expand property/boundary tests to repeated/hash-collision token cases and ensure numeric results are finite or intentionally represented.

**Acceptance:** approved semantic contract, exact dependency differential report, and regression fixtures. Do not mark parity complete while the null/blank deviation remains unapproved.

### P1.4 Imported model scoring and phase parity (audit ZD-010/012)

- [ ] Use the approved imported Spark model fixture to test the full path: import → validate → persist → reload in a fresh runtime/process → score MATCH candidate pairs → threshold/prediction → LINK output.
- [ ] Compare feature order, intermediate vector values, logistic margin, probability, threshold source, prediction, pair score, and final entity output against Spark/Zingg goldens.
- [ ] Include a non-default imported threshold (e.g. 0.73) and a deliberately conflicting MatchConfig threshold; prove the approved model-owned/override precedence at every API boundary.
- [ ] Exercise supported stage envelopes and malformed/unsupported stage diagnostics, artifact checksum/version errors, and no-partial-publication behavior.
- [ ] Add representative multi-feature and edge fixtures (null/missing values if supported, extreme finite values, class imbalance, repeated pairs, no matches, and threshold-boundary scores).

**Acceptance:** full worker-visible end-to-end imported-model parity on approved fixtures, with deterministic report and explicit unsupported-model envelope.

### P1.5 Offline and filesystem security closure (audit ZD-002)

- [ ] Inventory every packaged file-ingress/egress and extension-backed connector path. Test offline mode for each; assert no implicit install/load and no prohibited outbound connection attempt.
- [ ] Test global DuckDB instance reuse, JDBC URL/config overrides, extension configuration lock, local allowed-file operation, symlink/path traversal, and configured worker path roots.
- [ ] Verify Windows and Linux behavior independently; document that DuckDB's broad local allowed root is not itself a worker path sandbox.
- [ ] Add a clean-container/network-denied test that exercises attempted network access and checks a structured rejection without downloading or installing an extension.

**Acceptance:** policy matrix covers every entrypoint and operating environment; only explicit online allowlisted extensions work, and offline network/file policy is demonstrable rather than inferred from flags.

### P1.6 Worker protocol lifecycle and cancellation

- [ ] Exercise client request timeout through the actual packaged worker on Windows, WSL/Linux, and Docker, including worker termination escalation, process reaping, and next-request/restart behavior.
- [ ] Document the 300-second default, `None` behavior, timeout error shape, worker lifecycle after timeout, and caller retry/idempotency expectations.
- [ ] Cover cancellation at durable-label writes, model publication, MATCH output publication, LINK output publication, Arrow ingestion/egress, and shutdown; explicitly identify operations that are atomic/retryable versus not cancellable.
- [ ] Add process hard-stop/crash tests at every durable mutation boundary and verify recovery does not replay or lose acknowledged labels or publish corrupt state.
- [ ] Add cross-platform tests for stderr inheritance, startup/readiness, EOF, malformed frames, correlation IDs, timeout races, and process-tree termination.

**Acceptance:** protocol/lifecycle matrix passes on supported targets and retry/recovery semantics are documented.

### P1.7 Arrow qualification completion

- [ ] Close remaining Arrow vectors and schema cases across IPC ingress/egress: nested/unsupported types, decimals, dates/times/timestamps/timezones, binary/string edge cases, dictionary encoding, empty batches/files, multiple batches, and malformed/truncated files.
- [ ] Verify row/byte limits are enforced before excess memory/disk use; validate rollback after failures at each batch/commit stage and cleanup after child-process termination.
- [ ] Compare JDBC and Appender paths for type, nullability, duplicate, and error semantics; keep the faster Appender only where parity is proven, with an exercised fallback if retained.
- [ ] Benchmark vectorized C Data/CTAS and existing Arrow/JDBC/Appender paths with identical data, warmups, repeats, checksums, RSS, spill, and output size.

**Acceptance:** documented supported Arrow type matrix, no silent coercions, bounded failure handling, and reproducible performance evidence for the selected path.

## P2 — Compatibility and full workflow evidence

### P2.1 ZFrame v0.7 overload semantics

- [ ] Re-run the exact current inventory and generated adapter matrix; make the reported counts agree across `build-plan.md`, matrix JSON, coverage Markdown, and test results.
- [ ] Add independently attributable Spark 3.5.5 differential tests for all remaining **46/93** overloads currently marked `OVERLOAD_SELECTED_SEMANTICS_UNPROVEN` (recalculate from checked-in matrix before execution). Each test must invoke the exact overload, prove Javac resolution, assert its own result/output, and include relevant null/type/duplicate/empty/boundary cases.
- [ ] Expand existing 47 overload differentials for untested edge cases and rerun the complete class plus overload resolver after the latest `gt(C,C)` addition; the recorded full suite predates that addition.
- [ ] Add focused differentials for duplicate names, zero-column compositions, alias/self joins, schema nullability, row ordering, sampling determinism/statistics, split/explode corner cases, and coercion boundaries as applicable.
- [ ] Obtain approved Zingg v0.7 goldens; Spark 3.5.5 behavior alone is not proof of Zingg v0.7 contract parity.
- [ ] Run matrix generator, inventory hash verifier, matrix unit tests, exact-contract/reflection tests, and all differentials serially or in isolated Maven target directories to prevent shared-target truncation.

**Acceptance:** 93/93 signatures routed and independently semantically evidenced to the agreed bar; all generated evidence links resolve; no “selected semantics” entries remain unless explicitly accepted as a documented deviation.

### P2.2 End-to-end workflow gates

- [ ] Complete the approved workflow from FIND_TRAINING_DATA through labeling, TRAIN, process/runtime restart, MATCH, and LINK using persisted decisions and the persisted model.
- [ ] Validate candidate-pair schemas, deterministic blocking behavior, score/prediction thresholds, entity IDs, cluster IDs, min/max scores, duplicates/reversed edges, and output row multisets against approved Zingg fixtures.
- [ ] Verify behavior when a phase is unsupported, a model is absent/stale, labels are incomplete/duplicated, an input schema differs, or the operation is retried after timeout/cancellation.
- [ ] Resolve whether native histogram-only training is an explicitly supported backend mode or a deliberate unsupported mode; never describe it as classifier training parity.
- [ ] Add default-worker durability and sustained queue/load tests; retain the existing multi-process contention/lost-ack probes as targeted evidence, not a complete load qualification.

**Acceptance:** workflow outputs match approved fixtures; phase state transitions and restart/retry behavior are explicit and tests prove them.

### P2.3 Remaining engine/API semantics

- [ ] Expand schema-changing frame tests to all applicable overloads, duplicate column names, zero-column joins, alias edge cases, and schema invalidation after SQL/UNNEST/aggregation/join transformations.
- [ ] Test `maxRows` at exactly the limit and one above through direct engine and worker APIs; verify no truncation and bounded cancellation on rejection.
- [ ] Prove duplicate JDBC close/connection release behavior with instrumentation or diagnostics where feasible; current read-only database regression proves permit reuse but not direct duplicate-close invocation.
- [ ] Validate row-count/collect limits through all worker ingestion and result paths and report exact resource-limit errors.

## P3 — Performance qualification and regression budgets

### P3.1 Establish representative budgets

- [ ] Define workload profiles and acceptance budgets for startup, throughput, p50/p95 latency, peak RSS, DuckDB spill/temp bytes, output bytes, and cancellation latency. Separate smoke, typical, and capacity profiles.
- [ ] Include classifier training at 10k/100k/1M rows and multiple feature widths; record exact numerical tolerances, DuckDB plan, iteration count, spill, RSS, and cancellation latency.
- [ ] Add graph path/star/dense/giant-component workloads, direct pair-expansion budget tests, and entity-score scaling; record V/E/output sizes.
- [ ] Extend native-image/JVM comparison beyond 64/128/256 candidate rows to representative protocol, Arrow, imported model, failure, and larger MATCH workloads.
- [ ] Compare vectorized C Data/CTAS and current Arrow paths. Include CPU counters if available and distinguish clean timing runs from instrumented RSS/spill runs.
- [ ] Run repeats on independent clean runners and establish statistically justified regression thresholds with an agreed noise policy; configure CI to fail only on those agreed thresholds.
- [ ] Store machine/runtime/OS/architecture/JDK/DuckDB/configuration/commit and input hashes with every report; reject incomparable runs automatically.

**Acceptance:** versioned benchmark protocol, fixture-specific limits, clean-runner baseline, enforced CI gate, and a dashboard/history or machine-readable report.

## P4 — Cross-platform CI, packages, security, and release controls

### P4.1 Clean install and supported platform matrix

- [ ] Run the full clean-install/no-system-Java flow on hosted Linux, Windows, and macOS for every declared architecture (including ARM only if supported).
- [ ] Include clean native Linux filesystem and packaged Docker smoke; verify offline launch, worker ping/shutdown, Python client, JRE metadata/licensing, source archive, bundle manifest, rollback, and checksums.
- [ ] Run the full Python-client supported-version matrix and Java/Maven verification on clean runners. Use isolated workspaces/targets for OS jobs.
- [ ] Execute actual hosted CI and inspect uploaded logs/artifacts/hashes; local Windows/WSL success does not close this task.
- [ ] Decide whether GraalVM Native Image is a release target. If yes, build on each declared target and qualify broad protocol/Arrow/model/failure/security behavior; if no, label current image benchmark explicitly experimental and exclude it from support promises.

### P4.2 Provenance, SBOM, licensing, vulnerability response

- [ ] Generate and verify signed provenance attestations for source and binary artifacts; bind commit, toolchain, dependency lock/tree, package hashes, and build workflow identity.
- [ ] Complete component/license review for every shipped component, resolving all `NOASSERTION` declarations (currently 195 per recorded package) and the embedded metadata-only `jctools-core` coordinate; verify JRE and legacy artifacts separately.
- [ ] Review shaded-JAR overlapping META-INF/module descriptors and confirm notices/copyright obligations are preserved correctly.
- [ ] Obtain independent legal/security review and record reviewer, disposition, and source evidence for every license.
- [ ] Run the mandatory hosted OSV workflow; triage all findings, record accepted mitigations/exceptions, prove scan-failure behavior, and archive reports. Decide whether OWASP Dependency-Check is supplemental and provision NVD credentials if retained.
- [ ] Assign an owner and cadence for JDK/DuckDB/dependency security updates, support window, CVE response SLA, rebuild/re-sign, and rollback policy.
- [ ] Re-verify rollback and source archive from the exact release candidate and test restoration in a clean environment.

**Acceptance:** signed verifiable provenance, complete SBOM/license disposition, reviewed vulnerability report, defined update ownership, and rehearsed rollback for the exact release candidate.

## Final release/closure gate

- [ ] Reconcile every checklist item in this plan with an owner, issue/PR, evidence link, status, and rationale for any accepted non-goal.
- [ ] Regenerate `docs/zframe-v07-adapter-matrix.json` and coverage summary; verify signature count/dispositions match test reports. Remove stale claims that predate the latest overload tests.
- [ ] Run a serial or isolated full Windows verification and a full Linux verification after the final source change; run the complete Python suite, packaging suite, reference differential suites, and Docker package tests.
- [ ] Build final packages from clean hosted runners, verify signatures/checksums/source archive/SBOM/notices/rollback, and test offline installation on every supported platform.
- [ ] Publish an evidence index mapping requirement → implementation → test/report → artifact hash → platform/runtime → date → owner, with known gaps and accepted limitations.
- [ ] Obtain explicit maintainer/product sign-off for support scope, compatibility deviations, unresolved upstream inputs, and release readiness.

## Current evidence snapshot (not a completion percentage)

- Latest audit remediation: relevant focused Java tests passed on Windows/WSL; full Maven `verify` passed on Windows and WSL before the final focused-only adjustments. Python suite previously passed 22/22 on Windows.
- Newest Linux/JRE package passed package/source/SBOM/license/rollback verification and the offline Docker smoke: Ubuntu launcher ping/shutdown plus packaged worker/Python integration **4/4**.
- Hosted CI, macOS/ARM qualification, signed attestations, complete license review, representative hard resource budgets, approved upstream goldens, and several end-to-end compatibility gates remain open.
- The ZFrame matrix at review time recorded 47 dedicated overload differentials and 46 selected-but-unproven signatures; the full class had not been rerun after the latest `gt(C,C)` test. Recalculate all counts from generated artifacts before using them as current status.
- Existing workspace contains unrelated in-progress modifications and untracked distribution bundles. This plan does not authorize cleanup, commit, or push; preserve those artifacts and edits.
