# Benchmarks

`BenchmarkMain` measures three independently reported workloads:

- runtime startup and shutdown;
- a DuckDB `range` aggregation;
- graph transitive closure on a chain, including the expected O(k²) output cardinality.

Build and run a smoke benchmark from the repository root:

```powershell
./mvnw.cmd -pl engine-duckdb -am -DskipTests package
$duck = (Get-ChildItem "$env:USERPROFILE\.m2\repository\org\duckdb\duckdb_jdbc\1.5.5.1\duckdb_jdbc-1.5.5.1.jar" | Select-Object -First 1 -ExpandProperty FullName)
java -cp "engine-duckdb\target\classes;engine-api\target\classes;$duck" io.zingg.duckdb.engine.BenchmarkMain --size 1000 --repetitions 5
```

Use increasing `--size` values for capacity observations. Results are machine- and JVM-dependent; store the JSON output with JDK, DuckDB version, OS, CPU, memory, and thread configuration before comparing runs. These benchmarks are not correctness tests and do not establish release thresholds by themselves.
