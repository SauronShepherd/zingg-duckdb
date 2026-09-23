package io.zingg.duckdb.engine;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

/**
 * Small dependency-free benchmark runner for repeatable smoke and capacity runs.
 * It reports medians in JSON and is intentionally separate from correctness tests.
 */
public final class BenchmarkMain {
  private BenchmarkMain() {}

  public static void main(String[] args) throws Exception {
    int size = integerArg(args, "--size", 1000);
    int repetitions = integerArg(args, "--repetitions", 5);
    if (size < 2 || repetitions < 1) throw new IllegalArgumentException("size >= 2 and repetitions >= 1 are required");
    long[] startup = new long[repetitions], sql = new long[repetitions], graph = new long[repetitions];
    int edgeCount = size - 1;
    for (int i = 0; i < repetitions; i++) {
      long begin = System.nanoTime();
      try (var runtime = new DuckRuntime("jdbc:duckdb:")) { startup[i] = System.nanoTime() - begin; }
      try (var connection = DriverManager.getConnection("jdbc:duckdb:")) {
        begin = System.nanoTime();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
            "select sum(i), avg(i) from range(" + size + ") t(i)")) { rows.next(); }
        sql[i] = System.nanoTime() - begin;
      }
      List<GraphOutput.Edge> edges = new ArrayList<>(edgeCount);
      for (int node = 0; node < edgeCount; node++) edges.add(new GraphOutput.Edge(node, node + 1, 1d));
      begin = System.nanoTime();
      int produced = GraphOutput.transitiveEdges(edges).size();
      graph[i] = System.nanoTime() - begin;
      if (produced != size * (size - 1) / 2) throw new IllegalStateException("graph result cardinality mismatch");
    }
    System.out.println("{\"benchmark\":\"zingg-duckdb-smoke\",\"size\":" + size
        + ",\"repetitions\":" + repetitions + ",\"graphInputEdges\":" + edgeCount
        + ",\"startupMedianNs\":" + median(startup) + ",\"sqlMedianNs\":" + median(sql)
        + ",\"graphMedianNs\":" + median(graph) + "}");
  }

  private static int integerArg(String[] args, String name, int fallback) {
    for (int i = 0; i + 1 < args.length; i++) if (name.equals(args[i])) return Integer.parseInt(args[i + 1]);
    return fallback;
  }

  private static long median(long[] values) {
    long[] copy = values.clone(); java.util.Arrays.sort(copy); return copy[copy.length / 2];
  }
}
