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
}
