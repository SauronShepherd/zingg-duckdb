package io.zingg.duckdb.engine;

import io.zingg.duckdb.api.DuckException;
import io.zingg.duckdb.api.JobHandle;
import io.zingg.duckdb.api.JobId;
import io.zingg.duckdb.api.RuntimeConfig;
import io.zingg.duckdb.api.RuntimeDiagnostics;
import io.zingg.duckdb.api.RuntimeHandle;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.duckdb.DuckDBConnection;

public final class DuckRuntime implements RuntimeHandle {
  private static final Set<String> ACTIVE_RUN_SCHEMAS = ConcurrentHashMap.newKeySet();

  private final DuckDBConnection root;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Semaphore jobSlots;

  public DuckRuntime(String url) {
    this(new RuntimeConfig(url, Runtime.getRuntime().availableProcessors(), 0, null, 1));
  }

  public DuckRuntime(RuntimeConfig config) {
    jobSlots = new Semaphore(config.maxConcurrentJobs());
    DuckDBConnection connection = null;
    try {
      connection = (DuckDBConnection) DriverManager.getConnection(config.databaseUrl());
      try (var statement = connection.createStatement()) {
        statement.execute("SET threads=" + config.threads());
        if (config.memoryLimitBytes() > 0)
          statement.execute("SET memory_limit='" + config.memoryLimitBytes() + "B'");
        if (config.tempDirectory() != null)
          statement.execute("SET temp_directory='"
              + config.tempDirectory().toAbsolutePath().normalize().toString().replace("'", "''") + "'");
        if (config.maxTempDirectoryBytes() > 0)
          statement.execute("SET max_temp_directory_size='" + config.maxTempDirectoryBytes() + "B'");
      }
      cleanupOrphanRunSchemas(connection);
      root = connection;
    } catch (SQLException e) {
      if (connection != null) {
        try {
          connection.close();
        } catch (SQLException closeFailure) {
          e.addSuppressed(closeFailure);
        }
      }
      throw new DuckException("cannot open DuckDB: " + config.databaseUrl(), e);
    }
  }

  private static void cleanupOrphanRunSchemas(DuckDBConnection connection) throws SQLException {
    var stale = new ArrayList<String>();
    try (var statement = connection.createStatement();
         var schemas = statement.executeQuery("SELECT schema_name FROM information_schema.schemata "
             + "WHERE substr(schema_name, 1, 11) = '_zingg_run_'")) {
      while (schemas.next()) {
        String schema = schemas.getString(1);
        if (!ACTIVE_RUN_SCHEMAS.contains(schema)) stale.add(schema);
      }
    }
    for (String schema : stale) {
      try (var statement = connection.createStatement()) {
        statement.execute("DROP SCHEMA IF EXISTS " + DuckExpr.quote(schema) + " CASCADE");
      }
    }
  }

  public JobHandle openJob() {
    return openJob(ResourceBudget.unlimited());
  }

  public JobHandle openJob(ResourceBudget budget) {
    if (closed.get()) throw new DuckException("runtime is closed");
    try {
      jobSlots.acquire();
      try {
        JobId id = JobId.create();
        String schema = "_zingg_run_" + id.value().replace("-", "");
        ACTIVE_RUN_SCHEMAS.add(schema);
        try {
          return new DuckJob(root.duplicate(), id, () -> {
            ACTIVE_RUN_SCHEMAS.remove(schema);
            jobSlots.release();
          }, budget, DuckCancellation.current());
        } catch (SQLException | RuntimeException e) {
          ACTIVE_RUN_SCHEMAS.remove(schema);
          throw e;
        }
      } catch (SQLException | RuntimeException e) {
        jobSlots.release();
        throw e;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DuckException("interrupted waiting for job slot", e);
    } catch (SQLException e) {
      throw new DuckException("cannot duplicate DuckDB connection", e);
    }
  }

  public RuntimeDiagnostics diagnostics() {
    if (closed.get()) throw new DuckException("runtime is closed");
    try (var statement = root.createStatement();
         var result = statement.executeQuery("SELECT current_setting('memory_limit'), "
             + "current_setting('max_temp_directory_size'), current_setting('temp_directory'), "
             + "current_setting('threads')")) {
      if (!result.next()) throw new DuckException("DuckDB did not return runtime settings");
      var memory = Runtime.getRuntime();
      String tempDirectory = result.getString(3);
      return new RuntimeDiagnostics(result.getString(1), result.getString(2), tempDirectory,
          result.getString(4), memory.totalMemory() - memory.freeMemory(), memory.maxMemory(),
          ProcessResourceSnapshot.residentBytes(), ProcessResourceSnapshot.directoryBytes(tempDirectory));
    } catch (SQLException e) {
      throw new DuckException("cannot read DuckDB runtime diagnostics", e);
    }
  }

  public void close() {
    if (closed.compareAndSet(false, true)) {
      try {
        root.close();
      } catch (SQLException e) {
        throw new DuckException("cannot close DuckDB", e);
      }
    }
  }
}
