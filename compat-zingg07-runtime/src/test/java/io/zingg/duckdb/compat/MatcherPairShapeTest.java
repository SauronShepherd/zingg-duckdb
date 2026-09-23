package io.zingg.duckdb.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.zingg.duckdb.engine.DuckRuntime;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MatcherPairShapeTest {
  @Test
  void candidatesUseZinggPrefixedRightSideColumns(@TempDir Path temp) {
    var config = new io.zingg.duckdb.api.RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("pairs.duckdb"), 1, 0, null, 1);
    try (var runtime = new DuckRuntime(config); var job = runtime.openJob()) {
      var input = job.sql("select * from (values (1, 'a'), (2, 'a')) v(z_zid, block)");
      var candidates = new Matcher().candidates(input, new Matcher.MatchConfig("z_zid", "block", "TRUE", 0.0));
      assertEquals(List.of("z_zid", "block", "z_z_zid", "z_block"), candidates.columns());
      assertEquals(1, candidates.count());
      assertTrue(candidates.collect().get(0).values().contains(2));
    }
  }
}
