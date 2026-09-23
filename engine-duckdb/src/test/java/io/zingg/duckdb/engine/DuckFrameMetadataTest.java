package io.zingg.duckdb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DuckFrameMetadataTest {
  @Test
  void sqlFramesDiscoverColumnsBeforeComposition(@TempDir Path temp) {
    var config = new io.zingg.duckdb.api.RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("metadata.duckdb"), 1, 0, null, 1);
    try (var runtime = new DuckRuntime(config); var job = runtime.openJob()) {
      var frame = job.sql("select 7 as value, 'ok' as label");
      assertEquals(java.util.List.of("value", "label"), frame.columns());
      assertEquals(1, frame.select("value").count());
      assertEquals(java.util.List.of("value", "label", "source"),
          frame.withColumn("source", "test").columns());
    }
  }

  @Test
  void cacheMaterializesAndRemainsComposable(@TempDir Path temp) {
    var config = new io.zingg.duckdb.api.RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("cache.duckdb"), 1, 0, null, 1);
    try (var runtime = new DuckRuntime(config); var job = runtime.openJob()) {
      var cached = job.sql("select * from range(3) t(value)").cache();
      assertEquals(3, cached.count());
      assertEquals(3, cached.select("value").count());
    }
  }

  @Test
  void collectEnforcesByteBudget(@TempDir Path temp) {
    var config = new io.zingg.duckdb.api.RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("collect-budget.duckdb"), 1, 0, null, 1);
    try (var runtime = new DuckRuntime(config);
         var job = runtime.openJob(new ResourceBudget(0, 0, 0, 0, 16, 0))) {
      org.junit.jupiter.api.Assertions.assertThrows(io.zingg.duckdb.api.DuckException.class,
          () -> job.sql("select 'a very long value' as value").collect());
    }
  }

  @Test
  void zinggSplitCreatesStringArrayColumn(@TempDir Path temp) {
    var config = new io.zingg.duckdb.api.RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("split.duckdb"), 1, 0, null, 1);
    try (var runtime = new DuckRuntime(config); var job = runtime.openJob()) {
      var split = job.sql("select 'a|b' as value").split("value", "|", "parts");
      assertEquals(java.util.List.of("parts"), split.columns());
      assertEquals("[a, b]", String.valueOf(split.collect().get(0).get("parts")));
    }
  }

  @Test
  void predicatePartitionHasExplicitNameAndAlias(@TempDir Path temp) {
    var config = new io.zingg.duckdb.api.RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("partition.duckdb"), 1, 0, null, 1);
    try (var runtime = new DuckRuntime(config); var job = runtime.openJob()) {
      var frame = job.sql("select * from range(4) t(value)");
      var named = frame.partitionByPredicate(new io.zingg.duckdb.engine.DuckExpr("value % 2 = 0"));
      var alias = frame.split(new io.zingg.duckdb.engine.DuckExpr("value % 2 = 0"));
      assertEquals(2, named.matching().count());
      assertEquals(2, named.remaining().count());
      assertEquals(named.matching().count(), alias.matching().count());
      assertEquals(named.remaining().count(), alias.remaining().count());
    }
  }
}
