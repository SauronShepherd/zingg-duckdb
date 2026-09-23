package io.zingg.duckdb.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.zingg.duckdb.api.RuntimeDiagnostics;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkInfrastructureTest {
  @Test
  void historyComparesOnlyMatchingIdentities(@TempDir Path temp) throws Exception {
    var history = new BenchmarkHistory(temp.resolve("history.jsonl"));
    var baseline = new BenchmarkHistory.Measurement("profile", "input", "config", 100, 100, 100, 100, Instant.now());
    var current = new BenchmarkHistory.Measurement("profile", "input", "config", 110, 120, 100, 119, Instant.now());
    history.append(baseline);
    assertEquals(1, Files.readAllLines(temp.resolve("history.jsonl")).size());
    assertTrue(history.compare(current, baseline, BenchmarkHistory.Thresholds.defaults()).compatible());
    var different = new BenchmarkHistory.Measurement("profile", "other-input", "config", 1, 1, 1, 1, Instant.now());
    assertFalse(history.compare(different, baseline, BenchmarkHistory.Thresholds.defaults()).compatible());
  }

  @Test
  void profileCaptureIsWriteOnce(@TempDir Path temp) throws Exception {
    var diagnostics = new RuntimeDiagnostics("1 GiB", "1 GiB", ".tmp", "2", 10, 100, 20, 0);
    var capture = new ProfileCapture("profile", "build", "request", diagnostics, "select 1", 12, 3, 20, "input", "config", Instant.now());
    Path written = capture.write(temp);
    assertTrue(Files.size(written) > 0);
    assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> capture.write(temp));
  }
}
