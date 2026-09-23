package io.zingg.duckdb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.zingg.duckdb.api.DuckException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DuckRuntimeOwnershipTest {
  @Test
  void jobsHaveIndependentTemporaryStateAndRejectCrossOwnerJoin(@TempDir Path temp) throws Exception {
    var config = new io.zingg.duckdb.api.RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("ownership.duckdb"), 2, 0, null, 2);
    try (var runtime = new DuckRuntime(config)) {
      try (var first = runtime.openJob(); var second = runtime.openJob()) {
        try (var statement = ((DuckJob) first).connection().createStatement()) {
          statement.execute("create temporary table local_rows(value integer)");
          statement.execute("insert into local_rows values (7)");
        }
        assertEquals(1, first.sql("select * from local_rows").count());
        assertThrows(Exception.class, () -> second.sql("select * from local_rows").count());
        assertThrows(DuckException.class, () -> first.sql("select 1").join(second.sql("select 1"), "true"));
      }
    }
  }
}
