package io.zingg.duckdb.engine;

import io.zingg.duckdb.api.DuckException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Types;
import java.util.List;
import java.util.Locale;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowFileReader;
import org.apache.arrow.vector.types.pojo.Field;

/** Arrow IPC file ingress converted into a worker-shared, job-private table. */
final class ArrowFileSupport {
  private ArrowFileSupport() {}

  static DuckFrame read(DuckJob job, Path path) {
    String table = "zd_arrow_" + java.util.UUID.randomUUID().toString().replace("-", "");
    long memoryLimit = job.budget().memoryLimitBytes() > 0 ? job.budget().memoryLimitBytes() : Runtime.getRuntime().maxMemory();
    try (var allocator = new RootAllocator(Math.max(1L, memoryLimit));
         var channel = FileChannel.open(path, StandardOpenOption.READ);
         var reader = new ArrowFileReader(channel, allocator)) {
      reader.initialize();
      VectorSchemaRoot root = reader.getVectorSchemaRoot();
      List<Field> fields = root.getSchema().getFields();
      if (fields.isEmpty()) throw new DuckException("Arrow file has no columns: " + path);
      String columns = fields.stream().map(f -> DuckExpr.quote(f.getName()) + " " + sqlType(f)).collect(java.util.stream.Collectors.joining(", "));
      try (var statement = job.connection().createStatement()) {
        statement.execute("CREATE TABLE " + DuckExpr.quote(job.schemaName()) + "." + DuckExpr.quote(table) + " (" + columns + ")");
      }
      job.registerMaterialized(table);
      String placeholders = String.join(",", java.util.Collections.nCopies(fields.size(), "?"));
      long importedRows = 0;
      try (var insert = job.connection().prepareStatement("INSERT INTO " + DuckExpr.quote(job.schemaName()) + "." + DuckExpr.quote(table) + " VALUES (" + placeholders + ")")) {
        while (reader.loadNextBatch()) {
          long batchEnd = importedRows + root.getRowCount();
          if (batchEnd < importedRows) throw new DuckException("Arrow row count overflow");
          job.budget().enforceRows(batchEnd);
          for (int row = 0; row < root.getRowCount(); row++) {
            for (int col = 0; col < fields.size(); col++) {
              FieldVector vector = root.getVector(col);
              Object value = vector.isNull(row) ? null : vector.getObject(row);
              insert.setObject(col + 1, value);
            }
            insert.addBatch();
          }
          insert.executeBatch();
          importedRows = batchEnd;
        }
      }
      return new DuckFrame(job, "SELECT * FROM " + DuckExpr.quote(table), fields.stream().map(Field::getName).toList());
    } catch (Exception e) {
      if (e instanceof DuckException de) throw de;
      throw new DuckException("Arrow IPC ingestion failed: " + path, e);
    }
  }

  private static String sqlType(Field field) {
    String type = field.getType().getTypeID().name().toUpperCase(Locale.ROOT);
    return switch (type) {
      case "BOOL" -> "BOOLEAN";
      case "INT", "UINT" -> "BIGINT";
      case "FLOATINGPOINT" -> "DOUBLE";
      case "DATE" -> "DATE";
      case "TIMESTAMP" -> "TIMESTAMP";
      case "STRING", "LARGE_STRING" -> "VARCHAR";
      default -> throw new DuckException("unsupported Arrow field type: " + field.getType());
    };
  }
}
