package io.zingg.duckdb.engine;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.zingg.duckdb.api.DuckException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class ResourceBudgetTest {
  @Test
  void inputAndOutputBudgetsAreIndependentFromSpillBudget() {
    var budget = new ResourceBudget(0, 0, 10, 20, 30);
    assertThrows(DuckException.class, () -> budget.enforceInputBytes(11));
    assertThrows(DuckException.class, () -> budget.enforceOutputBytes(21));
    budget.enforceInputBytes(10);
    budget.enforceOutputBytes(20);
  }

  @Test
  void collectBudgetIsIndependentAndEnforced() {
    var budget = new ResourceBudget(0, 0, 0, 0, 16, 0);
    assertThrows(DuckException.class, () -> budget.enforceCollectBytes(17));
    budget.enforceCollectBytes(16);
  }

  @Test
  void runtimeAppliesAndReportsLowResourceSettings(@TempDir Path temp) {
    var config = new io.zingg.duckdb.api.RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("limits.duckdb"), 2, 64L * 1024 * 1024,
        temp.resolve("spill"), 1, 128L * 1024 * 1024);
    try (var runtime = new DuckRuntime(config)) {
      var diagnostics = runtime.diagnostics();
      org.junit.jupiter.api.Assertions.assertTrue(diagnostics.memoryLimit().contains("64"));
      org.junit.jupiter.api.Assertions.assertTrue(diagnostics.maxTempDirectorySize().contains("128"));
      org.junit.jupiter.api.Assertions.assertEquals("2", diagnostics.threads());
    }
  }
}
