package io.zingg.duckdb.engine;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

/**
 * Small dependency-free benchmark runner for repeatable smoke and capacity runs.
 * It reports raw samples and descriptive timing summaries in JSON and is intentionally separate from correctness tests.
 */
public final class BenchmarkMain {
  private BenchmarkMain() {}

  public static void main(String[] args) throws Exception {
    int size = integerArg(args, "--size", 1000);
    int repetitions = integerArg(args, "--repetitions", 5);
    if (size < 2 || repetitions < 1) throw new IllegalArgumentException("size >= 2 and repetitions >= 1 are required");
    boolean sampleResources = java.util.Arrays.asList(args).contains("--resource-sampling");
    if (java.util.Arrays.asList(args).contains("--arrow-only")) {
      arrowOnly(size, repetitions, sampleResources);
      return;
    }
    long[] startup = new long[repetitions], sql = new long[repetitions], graph = new long[repetitions];
    long[] arrowWrite = new long[repetitions], arrowRead = new long[repetitions];
    int edgeCount = size - 1;
    for (int run = -1; run < repetitions; run++) { // exclude one cold pass from measured samples
      long startupNanos, sqlNanos, graphNanos, arrowWriteNanos, arrowReadNanos;
      long begin = System.nanoTime();
      try (var runtime = new DuckRuntime("jdbc:duckdb:")) { startupNanos = System.nanoTime() - begin; }
      try (var connection = DriverManager.getConnection("jdbc:duckdb:")) {
        begin = System.nanoTime();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
            "select sum(i), avg(i) from range(" + size + ") t(i)")) { rows.next(); }
      sqlNanos = System.nanoTime() - begin;
      }
      var arrow = java.nio.file.Files.createTempFile("zingg-duckdb-benchmark-", ".arrow");
      try (var runtime = new DuckRuntime("jdbc:duckdb:")) {
        try (var job = (DuckJob) runtime.openJob()) {
          begin = System.nanoTime();
          job.sql("select i, 'benchmark' as label from range(" + size + ") t(i)").writeArrow(arrow);
          arrowWriteNanos = System.nanoTime() - begin;
          begin = System.nanoTime();
          if (new DuckPipeReader(job).read(List.of(arrow), DuckPipeReader.UnionMode.MATCH_BY_NAME).count() != size)
            throw new IllegalStateException("Arrow benchmark cardinality mismatch");
          arrowReadNanos = System.nanoTime() - begin;
        }
      } finally { java.nio.file.Files.deleteIfExists(arrow); }
      List<GraphOutput.Edge> edges = new ArrayList<>(edgeCount);
      for (int node = 0; node < edgeCount; node++) edges.add(new GraphOutput.Edge(node, node + 1, 1d));
      begin = System.nanoTime();
      int produced = GraphOutput.transitiveEdges(edges).size();
      graphNanos = System.nanoTime() - begin;
      if (produced != size * (size - 1) / 2) throw new IllegalStateException("graph result cardinality mismatch");
      if (run >= 0) {
        startup[run] = startupNanos;
        sql[run] = sqlNanos;
        graph[run] = graphNanos;
        arrowWrite[run] = arrowWriteNanos;
        arrowRead[run] = arrowReadNanos;
      }
    }
    System.out.println("{\"benchmark\":\"zingg-duckdb-smoke\",\"size\":" + size
        + ",\"repetitions\":" + repetitions + ",\"warmupRuns\":1,\"graphInputEdges\":" + edgeCount
        + ",\"startupMedianNs\":" + median(startup) + ",\"sqlMedianNs\":" + median(sql)
        + ",\"graphMedianNs\":" + median(graph) + ",\"arrowWriteMedianNs\":" + median(arrowWrite)
        + ",\"arrowReadMedianNs\":" + median(arrowRead)
        + ",\"startupSamplesNs\":" + samples(startup) + ",\"sqlSamplesNs\":" + samples(sql)
        + ",\"graphSamplesNs\":" + samples(graph) + ",\"arrowWriteSamplesNs\":" + samples(arrowWrite)
        + ",\"arrowReadSamplesNs\":" + samples(arrowRead)
        + ",\"startupStats\":" + stats(startup) + ",\"sqlStats\":" + stats(sql)
        + ",\"graphStats\":" + stats(graph) + ",\"arrowWriteStats\":" + stats(arrowWrite)
        + ",\"arrowReadStats\":" + stats(arrowRead) + "}");
  }

  private static void arrowOnly(int size, int repetitions, boolean sampleResources) throws Exception {
    long[] write = new long[repetitions], ingest = new long[repetitions], appenderIngest = new long[repetitions];
    long[] jdbcPeakRss = new long[repetitions], jdbcPeakSpill = new long[repetitions], jdbcResourceSamples = new long[repetitions];
    long[] appenderPeakRss = new long[repetitions], appenderPeakSpill = new long[repetitions], appenderResourceSamples = new long[repetitions];
    long fileBytes = 0;
    var benchmarkRoot = java.nio.file.Files.createTempDirectory("zingg-duckdb-arrow-benchmark-");
    var spillDirectory = java.nio.file.Files.createDirectory(benchmarkRoot.resolve("spill"));
    long rssBefore = ProcessResourceSnapshot.residentBytes();
    long spillBefore = ProcessResourceSnapshot.directoryBytes(spillDirectory.toString());
    long rssAfter;
    long spillAfter;
    try {
      for (int run = -1; run < repetitions; run++) { // first pass warms the JVM and DuckDB
        var arrow = java.nio.file.Files.createTempFile(benchmarkRoot, "input-", ".arrow");
        try {
          long writeNanos;
          try (var runtime = benchmarkRuntime(spillDirectory); var job = (DuckJob) runtime.openJob()) {
            long begin = System.nanoTime();
            job.sql("select i, 'benchmark-payload' as label from range(" + size + ") t(i)").writeArrow(arrow);
            writeNanos = System.nanoTime() - begin;
          }
          long bytes = java.nio.file.Files.size(arrow);
          MeasuredImport jdbcImport;
          MeasuredImport appenderImport;
          if ((run & 1) == 0) {
            jdbcImport = measureImport(arrow, size, spillDirectory, false, sampleResources);
            appenderImport = measureImport(arrow, size, spillDirectory, true, sampleResources);
          } else {
            appenderImport = measureImport(arrow, size, spillDirectory, true, sampleResources);
            jdbcImport = measureImport(arrow, size, spillDirectory, false, sampleResources);
          }
          if (run >= 0) {
            write[run] = writeNanos;
            ingest[run] = jdbcImport.elapsedNanos();
            appenderIngest[run] = appenderImport.elapsedNanos();
            jdbcPeakRss[run] = jdbcImport.maxObservedRssBytes();
            jdbcPeakSpill[run] = jdbcImport.maxObservedSpillBytes();
            jdbcResourceSamples[run] = jdbcImport.sampleCount();
            appenderPeakRss[run] = appenderImport.maxObservedRssBytes();
            appenderPeakSpill[run] = appenderImport.maxObservedSpillBytes();
            appenderResourceSamples[run] = appenderImport.sampleCount();
            fileBytes = bytes;
          }
        } finally { java.nio.file.Files.deleteIfExists(arrow); }
      }
      rssAfter = ProcessResourceSnapshot.residentBytes();
      spillAfter = ProcessResourceSnapshot.directoryBytes(spillDirectory.toString());
    } finally {
      try (var paths = java.nio.file.Files.walk(benchmarkRoot)) {
        for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
          java.nio.file.Files.deleteIfExists(path);
      }
    }
    long medianIngest = median(ingest);
    long medianAppender = median(appenderIngest);
    long medianWrite = median(write);
    long rowsPerSecond = Math.round(size * 1_000_000_000d / medianIngest);
    long bytesPerSecond = Math.round(fileBytes * 1_000_000_000d / medianIngest);
    long appenderRowsPerSecond = Math.round(size * 1_000_000_000d / medianAppender);
    System.out.println("{\"benchmark\":\"zingg-duckdb-arrow-ingress\",\"size\":" + size
        + ",\"repetitions\":" + repetitions + ",\"warmupRuns\":1,\"fileBytes\":" + fileBytes
        + ",\"writeMedianNs\":" + medianWrite + ",\"ingestMedianNs\":" + medianIngest
        + ",\"ingestRowsPerSecond\":" + rowsPerSecond + ",\"ingestBytesPerSecond\":" + bytesPerSecond
        + ",\"appenderMedianNs\":" + medianAppender + ",\"appenderRowsPerSecond\":" + appenderRowsPerSecond
        + ",\"appenderSpeedup\":" + String.format(java.util.Locale.ROOT, "%.3f", medianIngest / (double) medianAppender)
        + ",\"writeSamplesNs\":" + samples(write) + ",\"jdbcIngestSamplesNs\":" + samples(ingest)
        + ",\"appenderIngestSamplesNs\":" + samples(appenderIngest)
        + ",\"writeStats\":" + stats(write) + ",\"jdbcIngestStats\":" + stats(ingest)
        + ",\"appenderIngestStats\":" + stats(appenderIngest)
        + ",\"resourceSamplingEnabled\":" + sampleResources
        + ",\"resourceSamplingIntervalMs\":" + (sampleResources ? 50 : 0)
        + ",\"jdbcMaxObservedRssBytes\":" + samples(jdbcPeakRss)
        + ",\"jdbcMaxObservedSpillBytes\":" + samples(jdbcPeakSpill)
        + ",\"jdbcResourceSampleCounts\":" + samples(jdbcResourceSamples)
        + ",\"appenderMaxObservedRssBytes\":" + samples(appenderPeakRss)
        + ",\"appenderMaxObservedSpillBytes\":" + samples(appenderPeakSpill)
        + ",\"appenderResourceSampleCounts\":" + samples(appenderResourceSamples)
        + ",\"processRssBeforeBenchmarkBytes\":" + rssBefore
        + ",\"processRssAfterBenchmarkBytes\":" + rssAfter
        + ",\"spillDirectoryBytesBefore\":" + spillBefore
        + ",\"spillDirectoryBytesAfter\":" + spillAfter + "}");
  }

  private static DuckRuntime benchmarkRuntime(java.nio.file.Path spillDirectory) {
    var config = new io.zingg.duckdb.api.RuntimeConfig("jdbc:duckdb:",
        Runtime.getRuntime().availableProcessors(), 0, spillDirectory, 1);
    return new DuckRuntime(config);
  }

  private static MeasuredImport measureImport(java.nio.file.Path arrow, int size,
      java.nio.file.Path spillDirectory, boolean useAppender, boolean sampleResources) throws Exception {
    try (var runtime = benchmarkRuntime(spillDirectory); var job = (DuckJob) runtime.openJob()) {
      if (!sampleResources) {
        long begin = System.nanoTime();
        DuckFrame imported = useAppender ? ArrowFileSupport.readWithAppender(job, arrow) : ArrowFileSupport.read(job, arrow);
        long elapsed = System.nanoTime() - begin;
        long rows = imported.count();
        if (rows != size) throw new IllegalStateException("Arrow-only " + (useAppender ? "appender" : "JDBC")
            + " benchmark cardinality mismatch: " + rows);
        return new MeasuredImport(elapsed, -1, -1, 0);
      }
      var monitor = new BenchmarkResourceMonitor(spillDirectory, 50);
      long begin = System.nanoTime();
      DuckFrame imported;
      long elapsed;
      try {
        imported = useAppender ? ArrowFileSupport.readWithAppender(job, arrow) : ArrowFileSupport.read(job, arrow);
      } finally {
        elapsed = System.nanoTime() - begin;
        monitor.close();
      }
      long rows = imported.count();
      if (rows != size) throw new IllegalStateException("Arrow-only " + (useAppender ? "appender" : "JDBC")
          + " benchmark cardinality mismatch: " + rows);
      return new MeasuredImport(elapsed, monitor.maxRssBytes(), monitor.maxSpillBytes(), monitor.samples());
    }
  }

  private record MeasuredImport(long elapsedNanos, long maxObservedRssBytes,
                                long maxObservedSpillBytes, long sampleCount) {}

  private static int integerArg(String[] args, String name, int fallback) {
    for (int i = 0; i + 1 < args.length; i++) if (name.equals(args[i])) return Integer.parseInt(args[i + 1]);
    return fallback;
  }

  private static long median(long[] values) {
    long[] copy = values.clone(); java.util.Arrays.sort(copy); return copy[copy.length / 2];
  }

  private static String samples(long[] values) {
    StringBuilder json = new StringBuilder("[");
    for (int i = 0; i < values.length; i++) {
      if (i > 0) json.append(',');
      json.append(values[i]);
    }
    return json.append(']').toString();
  }

  private static String stats(long[] values) {
    long min = values[0], max = values[0];
    double mean = 0;
    for (long value : values) {
      min = Math.min(min, value);
      max = Math.max(max, value);
      mean += value / (double) values.length;
    }
    double squaredDeviation = 0;
    for (long value : values) {
      double deviation = value - mean;
      squaredDeviation += deviation * deviation / values.length;
    }
    return "{\"minNs\":" + min + ",\"maxNs\":" + max
        + ",\"meanNs\":" + String.format(java.util.Locale.ROOT, "%.3f", mean)
        + ",\"populationStdDevNs\":" + String.format(java.util.Locale.ROOT, "%.3f", Math.sqrt(squaredDeviation)) + "}";
  }
}
