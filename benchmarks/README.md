# Benchmarks

`BenchmarkMain` performs one excluded warmup and then measures three independently reported workloads:

- runtime startup and shutdown;
- a DuckDB `range` aggregation;
- graph transitive closure on a chain, including the expected O(k²) output cardinality.
- offline Arrow IPC egress and file/stream ingress, including cardinality validation.

For a focused Arrow file-ingress comparison that excludes startup, graph work, row-ID assignment, and the pipe union, pass `--arrow-only`. It performs one excluded warmup, writes one Arrow IPC file per run, times both `ArrowFileSupport.read` (prepared JDBC row insertion) and `readWithAppender` (DuckDB's native JDBC Appender API), alternates which importer runs first, and verifies both imported cardinalities. By default there is no concurrent resource sampler, keeping timing evidence separate from resource diagnostics. Add `--resource-sampling` for a separate diagnostic run that polls process RSS and isolated DuckDB temp-directory bytes every 50 ms during each import; these best-effort maxima are not guaranteed peaks, and RSS remains process-wide rather than method-isolated. JSON includes raw samples, medians/statistics, file bytes, throughput, and Appender speedup. The Appender is a row-wise native append API—not Arrow C Data, zero-copy, or vectorized ingestion. Arrow requires the same Java module opens as Surefire. Pipe ingestion now uses the Appender route by default; retain the prepared-statement path as a comparison/fallback implementation until broader Arrow parity and reliability qualification is complete. Example on Windows:

```powershell
./mvnw.cmd -pl engine-duckdb -am -DskipTests package
./mvnw.cmd -q -pl engine-duckdb dependency:build-classpath '-Dmdep.outputFile=target/benchmark-classpath.txt'
$cp = (Get-Content engine-duckdb/target/benchmark-classpath.txt -Raw).Trim()
java --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED `
  -cp "engine-duckdb/target/classes;engine-api/target/classes;$cp" `
  io.zingg.duckdb.engine.BenchmarkMain --arrow-only --size 10000 --repetitions 5
```

Equivalent WSL/Linux invocation (first install reactor artifacts in that OS's local Maven repository, e.g. `./mvnw -pl engine-duckdb -am -DskipTests install`):

```bash
./mvnw -q -pl engine-duckdb dependency:build-classpath -Dmdep.outputFile=target/benchmark-classpath.txt
CP=$(cat engine-duckdb/target/benchmark-classpath.txt)
java --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  -cp "engine-duckdb/target/classes:engine-api/target/classes:$CP" \
  io.zingg.duckdb.engine.BenchmarkMain --arrow-only --size 10000 --repetitions 5
```

Build and run a smoke benchmark from the repository root:

```powershell
./mvnw.cmd -pl engine-duckdb -am -DskipTests package
$duck = (Get-ChildItem "$env:USERPROFILE\.m2\repository\org\duckdb\duckdb_jdbc\1.5.5.1\duckdb_jdbc-1.5.5.1.jar" | Select-Object -First 1 -ExpandProperty FullName)
java -cp "engine-duckdb\target\classes;engine-api\target\classes;$duck" io.zingg.duckdb.engine.BenchmarkMain --size 1000 --repetitions 5
```

Use increasing `--size` values for capacity observations. The 2026-09-28 Windows/WSL three-repetition direct-ingress baselines are in `arrow-ingress-2026-09-28-windows.json` and `arrow-ingress-2026-09-28-wsl.json`. The five-repetition Appender comparison from that date is recorded in the corresponding `arrow-ingress-appender-2026-09-28-*.json` files. These are single-machine diagnostics, not a comparison with Arrow C Data/CTAS vectorized ingestion, and include no RSS/spill accounting or release threshold. Results are machine- and JVM-dependent; store the JSON output with JDK, DuckDB version, OS, CPU, memory, and thread configuration before comparing runs. These benchmarks are not correctness tests and do not establish release thresholds by themselves.

### Current Arrow-ingress diagnostic (2026-09-30)

On the same 13th Gen Intel Core i7-1355U Windows host and WSL2 environment, `BenchmarkMain --arrow-only --size 10000 --repetitions 7` (one warmup excluded) reported. Machine-readable summary: [`arrow-ingress-2026-09-30-cross-platform.json`](arrow-ingress-2026-09-30-cross-platform.json).

| Environment | Java | JDBC median | Appender median | Appender/JDBC ratio |
| --- | --- | ---: | ---: | ---: |
| Windows host | Microsoft OpenJDK 21.0.12.1 | 2.617 s | 20.051 ms | 130.5× |
| Ubuntu 24.04 on WSL2 | Ubuntu OpenJDK 21.0.12.1 | 4.159 s | 17.084 ms | 243.4× |

The IPC fixture was 294,888 bytes; both implementations validated 10,000 imported rows on each measured run. Raw seven-run timing samples and min/max/mean/population-standard-deviation summaries are preserved in the linked JSON report. A background sampler polls process RSS and the isolated DuckDB temp directory every 50 ms during each JDBC and Appender import, then records maximum observed values and sample counts per run. The report also captures process RSS immediately before and after the full benchmark and bytes remaining in the isolated temp directory before and after. Sampled maxima are not guaranteed OS-level peaks: short operations can finish between polls, and sampling overhead can influence timing (especially on Windows, where RSS uses a system process query). These remain local diagnostics, not independent-runner or release evidence: the two environments share one laptop, WSL uses the Windows-mounted checkout/storage, and the benchmark excludes CPU counters, confidence intervals, 100k/capacity-sized inputs, and vectorized Arrow C Data/CTAS alternatives. Both harnesses exclude one warmup and emit raw measured samples plus descriptive summaries. Linux CI validates warmup metadata, sample counts, positive timings/RSS, and summary consistency for the general and Arrow harnesses; it still does not enforce regression budgets.

Correction for the 2026-09-30 report above: the table's timing medians are from runs with resource sampling disabled; a separate `--resource-sampling` run produced the per-method RSS/spill samples in the JSON report. Resource sampling is a separate diagnostic because its background work can perturb wall-clock timings. RSS is shared-process and cumulative; method order alternates to reduce ordering bias, but this is not process-isolated memory attribution. Appender phases often complete between 50 ms polls, yielding only boundary observations. Treat the earlier paragraph's claim that timings were sampled concurrently as superseded.

## Experimental GraalVM Native Image comparison

On Linux x64 with GraalVM for Java 21 and the required C toolchain installed, build and compare the actual worker against the JVM implementation:

```bash
./mvnw -DskipTests package
sh packaging/build-native-image.sh native-image dist-native zingg-duckdb-worker-linux-x64
GRAALVM_VERSION="Oracle GraalVM 21.0.12+7.1" \
python3 packaging/compare_native_image.py dist-native/zingg-duckdb-worker-linux-x64 \
  --warmups 1 --repetitions 5 --match-sizes 64 128 256 \
  --report benchmarks/native-image-local.json
```

The comparator performs fresh-process paired runs, discards configurable warmups, alternates which runtime launches first, and verifies ping, SQL row counts, classifier training/reload, and full MATCH rows at each increasing input size. It records latency, input/output rows per second, output bytes/hash, post-MATCH RSS/temp-directory bytes, artifact sizes, raw samples, and median/range/population-deviation summaries. A pair fails if either complete output rows or worker resource/configuration settings differ. Reports include Java and required Native Image toolchain versions, OS release/architecture, CPU count, launch flags, and artifact/JAR SHA-256. The WSL2 schema-v3 startup smoke is retained at `native-image-repeated-2026-09-29-wsl-v3.json`; the schema-v4 scaling run with sizes 64/128/256 is at `native-image-scaling-2026-09-29-wsl.json` after generation. These WSL measurements are still development diagnostics, not representative capacity limits, hosted/native Linux qualification, or release approval. Native Image remains experimental.
