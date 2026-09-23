package io.zingg.duckdb.engine;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.zingg.duckdb.api.DuckException;
import org.junit.jupiter.api.Test;

class ResourceBudgetTest {
  @Test
  void inputAndOutputBudgetsAreIndependentFromSpillBudget() {
    var budget = new ResourceBudget(0, 0, 10, 20, 30);
    assertThrows(DuckException.class, () -> budget.enforceInputBytes(11));
    assertThrows(DuckException.class, () -> budget.enforceOutputBytes(21));
    budget.enforceInputBytes(10);
    budget.enforceOutputBytes(20);
  }
}
