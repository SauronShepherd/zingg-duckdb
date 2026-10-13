package io.zingg.duckdb.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.zingg.duckdb.api.RuntimeConfig;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeLinkPhaseTest {
  @Test
  void sparsePathUsesPerEntityMissingEvidence(@TempDir Path temp) {
    var config = new RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("native-link-sparse-path.duckdb"), 1, 0, null, 1);
    try (var runtime = new CompatibilityRuntime(config, () -> 123L);
         var job = new ZinggJob(runtime)) {
      var scoredPairs = job.sql("SELECT * FROM (VALUES "
          + "('a', 'b', 0.9), ('b', 'c', 0.8)) t(z_zid, z_z_zid, z_score)");
      var output = new NativeLinkPhase().execute(job, scoredPairs, () -> 123L)
          .sort("z_zid").collect();

      assertEquals(List.of("a", "b", "c"), output.stream()
          .map(row -> row.get("z_zid").toString()).toList());
      assertEquals(0d, ((Number) output.get(0).get("z_minScore")).doubleValue(), 1e-12);
      assertEquals(.9d, ((Number) output.get(0).get("z_maxScore")).doubleValue(), 1e-12);
      assertEquals(.8d, ((Number) output.get(1).get("z_minScore")).doubleValue(), 1e-12);
      assertEquals(.9d, ((Number) output.get(1).get("z_maxScore")).doubleValue(), 1e-12);
      assertEquals(0d, ((Number) output.get(2).get("z_minScore")).doubleValue(), 1e-12);
      assertEquals(.8d, ((Number) output.get(2).get("z_maxScore")).doubleValue(), 1e-12);
    }
  }
}
