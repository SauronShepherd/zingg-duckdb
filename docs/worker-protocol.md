# Worker protocol

The Python client enforces an 8 MiB maximum on both outbound request lines and inbound response lines. Responses must be newline-terminated and carry the exact request correlation ID; oversized or unterminated responses close the worker client before failing. Versioned Python payload decoding validates the field count, URL-safe Base64 alphabet, and UTF-8 before exposing fields.

The Java worker enforces the same line limit during input accumulation, not after an unbounded `readLine()`. It drains an overlong request through its line ending, emits a correlated-format error response with an empty ID (the ID cannot be trusted before decoding), then continues serving subsequent requests. The exact-limit, over-limit recovery, and post-error `ping` behavior are covered by `ConfiguredWorkerServerTest`.

The worker is line-oriented. Each request is three escaped tab-separated fields:

```text
request-id<TAB>operation<TAB>payload
```

Supported operations:

- `ping` → `pong`;
- `count` → executes a read-only count SQL expression on a job connection;
- `run` → legacy `PayloadCodec` v1 fields `phase, output, predicate, classifierModel, input+`; returns output row count. A classifier path without matcher fields is rejected, so use `run_v2` for model-backed MATCH;
- `run_v2` → `PayloadCodec` v1 fields `phase, output, predicate, classifierModel, idColumn, blockingColumn, scoreExpression, threshold, input+`. It selects the explicit MATCH candidate/classifier scorer; a classifier model requires both ID and blocking columns. The Python `DuckWorker.run(...)` chooses `run_v2` when matcher arguments or a classifier artifact are supplied.
- `train` blocking-tree → `input1;input2|artifact|blockingExpression|blockingColumn|maxRows` and returns the artifact path;
- `train` classifier → `input1;input2|artifact|label|feature1,feature2|maxRows|iterations|learningRate|l2|profile` and returns the artifact path. Features must be finite numeric columns and the label must be boolean or numeric `0/1`.
- `apply_labels` → `PayloadCodec` v1 fields `schemaVersion, idempotencyKey, (leftId, rightId, MATCH|NON_MATCH|UNKNOWN, source)*`. The request ID comes from the line envelope. The response is `PayloadCodec` v1 fields `applied, replay, rejectionCount, (leftId, rightId, code, message)*`. Conflicting decisions are rejected; exact retries replay and changed-payload retries fail.
- `enqueue_pending_label` → `PayloadCodec` v1 fields `schemaVersion, producerKey, leftId, rightId, leftPresent, leftPayload, rightPresent, rightPayload`. Presence flags are literal `true` or `false`, allowing empty strings to differ from nulls. The response is one `replay` boolean. It requires `--labels-root`; producer keys are persisted and retries cannot duplicate a pair. A reused key with changed content fails. Each payload field is limited to 10,000 UTF-16 code units so Java's snapshot encoding remains bounded.
- `get_pending_labels` → `PayloadCodec` v1 fields `schemaVersion, deliveryKey, limit` (1–10); response fields `hasMore, count, (leftId, rightId, leftPresent, leftPayload, rightPresent, rightPayload)*`. It requires `--labels-root`. Exact retries with the same key/schema/limit replay the same batch after restart; changing the schema or limit fails. Delivery removes those pairs from the pending FIFO and saves the receipt atomically before returning. The batch cap keeps even legacy snapshot fields below the worker's line-size limit.
- `cancel` → the raw request ID of an in-flight operation in the envelope payload. It returns `cancel_requested` when the target is active/queued, or `not_active` if it has already finished. The target request still receives its own correlated `error` response after cancellation unwinds and cleans up; the worker remains available for later requests.
- `train_from_labels` → `PayloadCodec` v1 fields `artifact, idColumn, blockingColumn, featureCsv, labelColumn, maxRows, iterations, learningRate, l2, profile, input+`. It requires previously accepted positive and negative decisions in the configured provider, generates bounded candidate pairs, derives the binary label column, and writes a native classifier artifact. It does not create a released Zingg v0.7 blocking tree or LINK output.
- `shutdown` → closes the worker.

Launch configuration:

```text
java -jar runtime-worker.jar \
  --url jdbc:duckdb: \
  --threads 8 \
  --memory-bytes 4294967296 \
  --max-temp-bytes 10737418240 \
  --max-jobs 1 \
  --max-rows 1000000 \
  --max-spill-bytes 10737418240 \
  --max-collect-bytes 268435456 \
  --max-model-bytes 268435456 \
  --labels-root /data/labels \
  --input-root /data/input \
  --output-root /data/output
```

The worker emits structured tab-separated responses and keeps diagnostics out of stdout. Requests execute in order on one worker thread, while the reader handles `cancel` control messages concurrently. At most 65 normal operations may be active/queued (one executing plus a bounded queue of 64); excess requests fail with `worker request queue is full`. SQL predicates are allowlisted by `SqlSafety`; input and output paths are constrained by `PathPolicy` when roots are configured.

The Python facade accepts the same runtime options (`input_root`, `output_root`, `labels_root`, `threads`, `memory_bytes`, `max_temp_bytes`, `max_jobs`, `max_rows`, `max_spill_bytes`, `max_collect_bytes`, and `max_model_bytes`) and translates them to the worker flags. `max_temp_bytes` is applied by DuckDB as `max_temp_directory_size`. `DuckWorker.apply_labels(...)`, `enqueue_pending_label(...)`, and `get_pending_labels(...)` use versioned payloads. `--labels-root` opts into the atomic, checksummed label snapshot; without it applied labels remain in memory and pending-label worker operations are rejected. Snapshot v3 persists applied requests, pending pairs, delivery receipts, and producer keys; it reads v1/v2 snapshots and migrates on the next write. Startup takes the store lock before removing regular orphan `.labels-*.partial` files left by a killed process. Windows/WSL probes cover small concurrent-process and lost-response fixtures; test-only child JVMs halt at three snapshot stages. Sustained contention, power-loss durability, and receipt retention/compaction remain unqualified.

The corresponding Python methods are `DuckWorker.train(...)` for a blocking tree and `DuckWorker.train_classifier(...)` for a native logistic classifier. Classifier training is deterministic for a fixed input order and configuration, uses bounded batch gradient descent with L2 regularization, and writes the neutral `zingg-0.1-native` classifier artifact.

The read-only `status` operation returns the effective DuckDB settings (`memory_limit`, `max_temp_directory_size`, `temp_directory`, and `threads`), configured row/collect/output/spill budgets, and JVM heap counters. These diagnostics complement, but do not replace, independent process/RSS measurement. It is available as `DuckWorker.status()` and `WorkerClient.status()` in Python.

The read-only `explain <sql>` operation validates the query with the same read-only SQL policy as `count` and returns DuckDB's plan without executing the query. It is available as `DuckWorker.explain(sql)` and `WorkerClient.explain(sql)`.

`WorkerClient.submit(...)` and `DuckWorker.request_async(operation, payload)` return a `WorkerRequest` with its correlation ID, `result(timeout)`, and `cancel()`. The response-reader thread multiplexes out-of-order replies by request ID, so a cancellation acknowledgement can arrive before the target operation's error response. Java binds each request ID to a cancellation token; JDBC statements in the job are tracked and cancelled with `Statement.cancel()`, while Arrow Appender ingestion checks the token between rows/batches and drops partial materialization before replying. Cancellation is cooperative at those boundaries: it does not promise rollback of already committed external output or durable label effects. `cancel_request(request_id)` sends the same control request when the ID is managed separately.

`WorkerClient.cancel()` remains an emergency process-wide termination path: it waits up to five seconds after graceful process termination, then escalates to a hard kill and waits again. Use `close()` when the client will not be reused; the request-selective operation should normally leave the worker alive. Calls that already completed durable side effects remain committed, so callers should use idempotency keys/recovery and must not infer transaction rollback from a cancellation response.
