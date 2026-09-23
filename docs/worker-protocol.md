# Worker protocol

The worker is line-oriented. Each request is three escaped tab-separated fields:

```text
request-id<TAB>operation<TAB>payload
```

Supported operations:

- `ping` → `pong`;
- `count` → executes a read-only count SQL expression on a job connection;
- `run` → `phase|input|output|predicate[|classifierModel]` and returns output row count; the optional model is used for native linear MATCH scoring;
- `train` blocking-tree → `input1;input2|artifact|blockingExpression|blockingColumn|maxRows` and returns the artifact path;
- `train` classifier → `input1;input2|artifact|label|feature1,feature2|maxRows|iterations|learningRate|l2|profile` and returns the artifact path. Features must be finite numeric columns and the label must be boolean or numeric `0/1`.
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
  --input-root /data/input \
  --output-root /data/output
```

The worker emits structured tab-separated responses and keeps diagnostics out of stdout. SQL predicates are allowlisted by `SqlSafety`; input and output paths are constrained by `PathPolicy` when roots are configured.

The Python facade accepts the same runtime options (`input_root`, `output_root`, `threads`, `memory_bytes`, `max_temp_bytes`, `max_jobs`, `max_rows`, `max_spill_bytes`, `max_collect_bytes`, and `max_model_bytes`) and translates them to the worker flags. `max_temp_bytes` is applied by DuckDB as `max_temp_directory_size`.

The corresponding Python methods are `DuckWorker.train(...)` for a blocking tree and `DuckWorker.train_classifier(...)` for a native logistic classifier. Classifier training is deterministic for a fixed input order and configuration, uses bounded batch gradient descent with L2 regularization, and writes the neutral `zingg-0.1-native` classifier artifact.

The read-only `status` operation returns the effective DuckDB settings (`memory_limit`, `max_temp_directory_size`, `temp_directory`, and `threads`), configured row/collect/output/spill budgets, and JVM heap counters. These diagnostics complement, but do not replace, independent process/RSS measurement. It is available as `DuckWorker.status()` and `WorkerClient.status()` in Python.

The read-only `explain <sql>` operation validates the query with the same read-only SQL policy as `count` and returns DuckDB's plan without executing the query. It is available as `DuckWorker.explain(sql)` and `WorkerClient.explain(sql)`.
