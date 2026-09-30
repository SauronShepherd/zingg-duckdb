package io.zingg.duckdb.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.zingg.duckdb.api.DuckException;
import io.zingg.duckdb.api.RuntimeConfig;
import io.zingg.duckdb.engine.DuckCancellation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeClassifierTrainerTest {
  @Test
  void observesRequestCancellationDuringTrainingAndDoesNotPublishArtifact(@TempDir Path temp)
      throws Exception {
    try (var runtime = runtime(temp); var job = new ZinggJob(runtime)) {
      var input = job.sql("SELECT CAST(i AS DOUBLE) AS feature, CAST(i % 2 AS INTEGER) AS label "
          + "FROM range(2000) t(i)");
      Path artifact = temp.resolve("cancelled-model");
      var config = new NativeClassifierTrainer.Config("test-profile", List.of("feature"), "label",
          artifact, 1_000_000, 0.05, 1e-4, 2_000);
      var cancellation = new DuckCancellation();
      // The input is small enough to count/collect quickly, but one million
      // full-batch iterations cannot finish before this timer. This therefore
      // exercises cancellation inside the CPU training loop, not only JDBC IO.
      cancellation.cancelAfter(Duration.ofSeconds(5));
      try (var ignored = DuckCancellation.install(cancellation)) {
        DuckException failure = assertThrows(DuckException.class,
            () -> new NativeClassifierTrainer().train(input, config));
        assertTrue(failure.getMessage().contains("operation cancelled")
            || (failure.getCause() != null
                && String.valueOf(failure.getCause().getMessage()).contains("cancel")),
            "cancellation should be preserved as the training failure: " + failure);
      }
      assertTrue(Files.notExists(artifact), "cancelled classifier training must not publish an artifact");
      try (var entries = Files.list(temp)) {
        assertTrue(entries.noneMatch(path -> path.getFileName().toString().contains("model-artifact")),
            "cancelled training must not leave staging or backup artifacts");
      }
    }
  }

  @Test
  void rejectsOversizedInputInsteadOfSilentlyDroppingRows(@TempDir Path temp) {
    try (var runtime = runtime(temp); var job = new ZinggJob(runtime)) {
      var input = job.sql("SELECT * FROM (VALUES (0.0, 0), (1.0, 1), (2.0, 0)) AS t(feature, label)");
      Path artifact = temp.resolve("oversized-model");
      var config = config(artifact, List.of("feature"), 2);
      DuckException failure = assertThrows(DuckException.class,
          () -> new NativeClassifierTrainer().train(input, config));
      assertTrue(failure.getMessage().contains("classifier maxRows exceeded: 3 > 2"));
      assertTrue(Files.notExists(artifact));
    }
  }

  @Test
  void rejectsSingleClassLabelsBeforePublishingArtifact(@TempDir Path temp) {
    try (var runtime = runtime(temp); var job = new ZinggJob(runtime)) {
      var input = job.sql("SELECT * FROM (VALUES (0.0, 1), (1.0, 1)) AS t(feature, label)");
      Path artifact = temp.resolve("single-class-model");
      DuckException failure = assertThrows(DuckException.class,
          () -> new NativeClassifierTrainer().train(input, config(artifact, List.of("feature"), 2)));
      assertTrue(failure.getMessage().contains("both positive and negative labels"));
      assertTrue(Files.notExists(artifact));
    }
  }

  @Test
  void resultWeightsAreDefensivelyCopied(@TempDir Path temp) {
    try (var runtime = runtime(temp); var job = new ZinggJob(runtime)) {
      var input = job.sql("SELECT * FROM (VALUES (-1.0, 0), (1.0, 1)) AS t(feature, label)");
      var result = new NativeClassifierTrainer().train(input, config(temp.resolve("model"), List.of("feature"), 2));
      double original = result.weights()[0];
      double[] exposed = result.weights();
      exposed[0] = original + 1000;
      assertEquals(original, result.weights()[0]);
      assertEquals(2, result.rows());
    }
  }

  @Test
  void artifactConfigWeightsAreDefensivelyCopied(@TempDir Path temp) {
    double[] inputWeights = {0.25};
    var config = new NativeClassifierArtifact.Config(
        "test-profile", List.of("feature"), inputWeights, 0.1, true, temp.resolve("immutable-model"));
    inputWeights[0] = 50;
    double[] exposed = config.weights();
    exposed[0] = -50;
    assertEquals(0.25, config.weights()[0]);
  }

  @Test
  void artifactConfigRejectsDuplicateAndDimensionInconsistentFeatures(@TempDir Path temp) {
    assertThrows(IllegalArgumentException.class, () -> new NativeClassifierArtifact.Config(
        "test-profile", List.of("same", "same"), new double[] {1, 2}, 0, true, temp.resolve("duplicate")));
    assertThrows(IllegalArgumentException.class, () -> new NativeClassifierArtifact.Config(
        "test-profile", List.of("x1", "x2"), new double[] {1, 2}, 0, true, temp.resolve("wrong-dimension"),
        0.5, List.of("base1", "base2"), 2));
    assertThrows(IllegalArgumentException.class, () -> new NativeClassifierArtifact.Config(
        "test-profile", List.of("x1"), new double[] {1}, 0, true, temp.resolve("wrong-degree"),
        0.5, List.of(), 1));
  }

  @Test
  void directLinearClassifierConstructionEnforcesFeatureAndPolynomialInvariants() {
    assertThrows(IllegalArgumentException.class,
        () -> new LinearClassifier(List.of("duplicate", "duplicate"), new double[] {1, 1}, 0, true));
    assertThrows(IllegalArgumentException.class,
        () -> new LinearClassifier(List.of("x1", "x2"), List.of("base", "base"), 1,
            new double[] {1, 1}, 0, true, 0.5));
    assertThrows(IllegalArgumentException.class,
        () -> new LinearClassifier(List.of("x1"), List.of(), 1,
            new double[] {1}, 0, true, 0.5));
    assertThrows(IllegalArgumentException.class,
        () -> new LinearClassifier(List.of("x1"), List.of("base"), 2,
            new double[] {1}, 0, true, 0.5));
  }

  @Test
  void configRejectsDuplicateAndLeakingFeatures(@TempDir Path temp) {
    assertThrows(IllegalArgumentException.class,
        () -> config(temp.resolve("duplicate"), List.of("feature", "feature"), 10));
    assertThrows(IllegalArgumentException.class,
        () -> config(temp.resolve("leak"), List.of("label"), 10));
  }

  private static NativeClassifierTrainer.Config config(Path artifact, List<String> features, long maxRows) {
    return new NativeClassifierTrainer.Config("test-profile", features, "label", artifact,
        20, 0.05, 1e-4, maxRows);
  }

  private static CompatibilityRuntime runtime(Path temp) {
    return new CompatibilityRuntime(new RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("trainer.duckdb"), 1, 0, null, 1), null);
  }
}
