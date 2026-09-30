package io.zingg.duckdb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
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
  void zeroColumnProjectionPreservesMultiplicityAndHidesPhysicalSentinel(@TempDir Path temp) {
    var config = new io.zingg.duckdb.api.RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("zero-column.duckdb"), 1, 0, null, 1);
    try (var runtime = new DuckRuntime(config); var job = runtime.openJob()) {
      var source = job.sql("SELECT * FROM (VALUES (1, 'x'), (1, 'x'), (2, 'y')) t(id, block)");
      var empty = source.selectZeroColumns();
      assertEquals(List.of(), empty.columns());
      assertEquals(List.of(), empty.schema());
      assertEquals(3, empty.count());
      assertEquals(3, empty.collect().size());
      assertEquals(List.of(), empty.collect().get(0).columns());
      assertEquals(List.of(), empty.collect().get(0).values());
      org.junit.jupiter.api.Assertions.assertThrows(io.zingg.duckdb.api.DuckException.class,
          () -> empty.writeCsv(temp.resolve("zero-columns.csv"), true));
      org.junit.jupiter.api.Assertions.assertFalse(java.nio.file.Files.exists(temp.resolve("zero-columns.csv")));
      org.junit.jupiter.api.Assertions.assertThrows(io.zingg.duckdb.api.DuckException.class,
          () -> empty.writeArrow(temp.resolve("zero-columns.arrow")));
      org.junit.jupiter.api.Assertions.assertFalse(java.nio.file.Files.exists(temp.resolve("zero-columns.arrow")));
      assertEquals(3, empty.filter(new DuckExpr("TRUE")).count());
      assertEquals(2, empty.limit(2).count());
      assertEquals(3, empty.sample(false, 1d).count());
      var replacementSample = empty.sample(true, 0.5d);
      assertEquals(List.of(), replacementSample.columns());
      org.junit.jupiter.api.Assertions.assertTrue(replacementSample.collect().stream()
          .allMatch(row -> row.columns().isEmpty() && row.values().isEmpty()));
      assertEquals(3, empty.cache().count());
      assertEquals(1, empty.distinct().count(), "all zero-column rows compare equal for distinct");
      assertEquals(3, source.drop("id", "block").count());
      var added = empty.withColumn("constant", 7);
      assertEquals(List.of("constant"), added.columns());
      assertEquals(3, added.count());
      assertEquals(7, ((Number) added.collect().get(0).get("constant")).intValue());
      var union = empty.union(empty, false, false);
      assertEquals(List.of(), union.columns());
      assertEquals(6, union.count());
      var byNameUnion = empty.union(source.select("block"), true, true);
      assertEquals(List.of("block"), byNameUnion.columns());
      assertEquals(6, byNameUnion.count());
      assertEquals(3L, byNameUnion.collect().stream().filter(row -> row.get("block") == null).count());
      var zeroJoin = empty.join(empty, "TRUE");
      assertEquals(List.of(), zeroJoin.columns());
      assertEquals(9, zeroJoin.count());
      var projectedJoin = empty.join(source.select("block"), "TRUE");
      assertEquals(List.of("block"), projectedJoin.columns());
      assertEquals(9, projectedJoin.count());
      assertEquals(1, empty.intersect(empty).count());
      assertEquals(0, empty.except(empty).count());
    }
  }

  @Test
  void withColumnEvaluatesPublicExpressionValuesInsteadOfSerializingThemAsLiterals(@TempDir Path temp) {
    var config = new io.zingg.duckdb.api.RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("with-column-expression.duckdb"), 1, 0, null, 1);
    try (var runtime = new DuckRuntime(config); var job = runtime.openJob()) {
      var frame = job.sql("SELECT 1 AS value UNION ALL SELECT 4");
      io.zingg.duckdb.api.Expression increment = () -> "\"value\" + 1";
      var rows = frame.withColumn("incremented", increment).sort("value ASC").collect();
      assertEquals(2, rows.size());
      assertEquals(2, ((Number) rows.get(0).get("incremented")).intValue());
      assertEquals(5, ((Number) rows.get(1).get("incremented")).intValue());
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

  @Test
  void collectEnforcesByteBudget(@TempDir Path temp) {
    var config = new io.zingg.duckdb.api.RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("collect-budget.duckdb"), 1, 0, null, 1);
    try (var runtime = new DuckRuntime(config);
         var job = runtime.openJob(new ResourceBudget(0, 0, 0, 0, 16, 0))) {
      org.junit.jupiter.api.Assertions.assertThrows(io.zingg.duckdb.api.DuckException.class,
          () -> job.sql("select 'a very long value' as value").collect());
    }
  }

  @Test
  void zinggSplitCreatesStringArrayColumn(@TempDir Path temp) {
    var config = new io.zingg.duckdb.api.RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("split.duckdb"), 1, 0, null, 1);
    try (var runtime = new DuckRuntime(config); var job = runtime.openJob()) {
      var split = job.sql("select 'a|b' as value").split("value", "|", "parts");
      assertEquals(java.util.List.of("parts"), split.columns());
      assertEquals("[a, b]", String.valueOf(split.collect().get(0).get("parts")));
    }
  }

  @Test
  void arrowEgressUsesBuiltInDuckdbWriterOffline(@TempDir Path temp) throws Exception {
    var config = new io.zingg.duckdb.api.RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("arrow.duckdb"), 1, 0, null, 1);
    Path output = temp.resolve("rows.arrow");
    try (var runtime = new DuckRuntime(config); var job = runtime.openJob()) {
      job.sql("select 1 as value, 'offline' as label").writeArrow(output);
      var roundTrip = new DuckPipeReader(job).read(java.util.List.of(output), DuckPipeReader.UnionMode.MATCH_BY_NAME);
      assertEquals(1, roundTrip.count());
      assertEquals(java.util.List.of("value", "label", "z_source", "z_zid"), roundTrip.columns());
    }
    assertEquals(true, java.nio.file.Files.exists(output));
    assertEquals(true, java.nio.file.Files.size(output) > 0);
    try (var allocator = new org.apache.arrow.memory.RootAllocator();
         var input = java.nio.file.Files.newInputStream(output);
         var reader = new org.apache.arrow.vector.ipc.ArrowStreamReader(input, allocator)) {
      var root = reader.getVectorSchemaRoot();
      assertEquals(true, reader.loadNextBatch());
      assertEquals(1, root.getRowCount());
      assertEquals("offline", String.valueOf(root.getVector("label").getObject(0)));
    }
  }

  @Test
  void arrowRoundTripHandlesNullsAndMultipleBatches(@TempDir Path temp) throws Exception {
    var config = new io.zingg.duckdb.api.RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("arrow-batches.duckdb"), 1, 0, null, 1);
    Path output = temp.resolve("batches.arrow");
    try (var runtime = new DuckRuntime(config); var job = runtime.openJob()) {
      job.sql("select i as value, case when i % 2 = 0 then cast(null as varchar) else 'x' end as label, i % 2 = 0 as flag, i::double + 0.5 as ratio from range(1025) t(i)").writeArrow(output);
      var jdbcPath = ArrowFileSupport.read((DuckJob) job, output);
      var appenderPath = ArrowFileSupport.readWithAppender((DuckJob) job, output);
      assertEquals(jdbcPath.schema(), appenderPath.schema());
      assertEquals(jdbcPath.collect(), appenderPath.collect(), "Appender must preserve nulls and every batch");
      var roundTrip = new DuckPipeReader(job).read(java.util.List.of(output), DuckPipeReader.UnionMode.MATCH_BY_NAME);
      assertEquals(1025, roundTrip.count());
      assertEquals(null, roundTrip.collect().get(0).get("label"));
      assertEquals("x", roundTrip.collect().get(1).get("label"));
    }
  }

  @Test
  void malformedArrowInputFailsWithoutPublishingAWorkTable(@TempDir Path temp) throws Exception {
    var config = new io.zingg.duckdb.api.RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("arrow-invalid.duckdb"), 1, 0, null, 1);
    Path input = temp.resolve("invalid.arrow");
    java.nio.file.Files.write(input, new byte[] {0x01, 0x02, 0x03, 0x04});
    try (var runtime = new DuckRuntime(config); var job = runtime.openJob()) {
      var reader = new DuckPipeReader(job);
      org.junit.jupiter.api.Assertions.assertThrows(io.zingg.duckdb.api.DuckException.class,
          () -> reader.read(java.util.List.of(input), DuckPipeReader.UnionMode.MATCH_BY_NAME));
    }
  }

  @Test
  void truncatedLaterArrowBatchRollsBackBothImportPaths(@TempDir Path temp) throws Exception {
    byte[] complete;
    try (var allocator = new org.apache.arrow.memory.RootAllocator();
         var root = org.apache.arrow.vector.VectorSchemaRoot.create(
             new org.apache.arrow.vector.types.pojo.Schema(List.of(
                 new org.apache.arrow.vector.types.pojo.Field("value",
                     org.apache.arrow.vector.types.pojo.FieldType.nullable(
                         new org.apache.arrow.vector.types.pojo.ArrowType.Int(32, true)), null))), allocator);
         var output = new java.io.ByteArrayOutputStream();
         var channel = java.nio.channels.Channels.newChannel(output);
         var writer = new org.apache.arrow.vector.ipc.ArrowStreamWriter(root, null, channel)) {
      var vector = (org.apache.arrow.vector.IntVector) root.getVector("value");
      writer.start();
      vector.setSafe(0, 10);
      root.setRowCount(1);
      writer.writeBatch();
      vector.setSafe(0, 20);
      root.setRowCount(1);
      writer.writeBatch();
      writer.end();
      complete = output.toByteArray();
    }

    int secondBatchOffset;
    try (var allocator = new org.apache.arrow.memory.RootAllocator();
         var source = new java.io.ByteArrayInputStream(complete);
         var reader = new org.apache.arrow.vector.ipc.ArrowStreamReader(source, allocator)) {
      assertEquals(true, reader.loadNextBatch());
      assertEquals(1, reader.getVectorSchemaRoot().getRowCount());
      secondBatchOffset = complete.length - source.available();
    }
    // Keep the second message's framing header but truncate before its FlatBuffer metadata.
    // The first batch is valid and can be committed; decoding the next batch must fail.
    assertEquals((byte) 0xff, complete[secondBatchOffset]);
    Path input = temp.resolve("truncated-second-batch.arrow");
    java.nio.file.Files.write(input, java.util.Arrays.copyOf(complete, secondBatchOffset + 8));

    var config = new io.zingg.duckdb.api.RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("truncated-batch.duckdb"), 1, 0, null, 1);
    try (var runtime = new DuckRuntime(config); var job = (DuckJob) runtime.openJob()) {
      int registeredBefore = job.materializedCount();
      for (boolean useAppender : new boolean[] {false, true}) {
        boolean[] firstBatchCommitted = {false};
        var failure = org.junit.jupiter.api.Assertions.assertThrows(
            io.zingg.duckdb.api.DuckException.class,
            () -> ArrowFileSupport.read(job, input, useAppender, importedRows -> {
              if (importedRows == 1) firstBatchCommitted[0] = true;
            }));
        assertEquals("Arrow IPC ingestion failed: " + input, failure.getMessage());
        org.junit.jupiter.api.Assertions.assertNotNull(failure.getCause());
        org.junit.jupiter.api.Assertions.assertTrue(firstBatchCommitted[0],
            "the first batch must be committed before the malformed later batch is decoded");
        assertEquals(registeredBefore, job.materializedCount(),
            "decoder failure must release materialization registration");
        try (var statement = job.connection().createStatement();
             var rows = statement.executeQuery("select count(*) from information_schema.tables where table_schema='"
                 + job.schemaName() + "'")) {
          org.junit.jupiter.api.Assertions.assertTrue(rows.next());
          assertEquals(0, rows.getInt(1), "decoder failure must drop the partially populated table");
        }
      }
    }
  }

  @Test
  void decimalInsertFailureAfterCommittedArrowBatchRollsBackBothImportPaths(@TempDir Path temp)
      throws Exception {
    Path input = temp.resolve("decimal-overflow.arrow");
    try (var allocator = new org.apache.arrow.memory.RootAllocator();
         var root = org.apache.arrow.vector.VectorSchemaRoot.create(
             new org.apache.arrow.vector.types.pojo.Schema(List.of(
                 new org.apache.arrow.vector.types.pojo.Field("amount",
                     org.apache.arrow.vector.types.pojo.FieldType.nullable(
                         new org.apache.arrow.vector.types.pojo.ArrowType.Decimal(5, 2, 128)), null))), allocator);
         var channel = java.nio.channels.Channels.newChannel(java.nio.file.Files.newOutputStream(input));
         var writer = new org.apache.arrow.vector.ipc.ArrowStreamWriter(root, null, channel)) {
      var vector = (org.apache.arrow.vector.DecimalVector) root.getVector("amount");
      writer.start();
      vector.setSafe(0, new java.math.BigDecimal("12.34"));
      root.setRowCount(1);
      writer.writeBatch();
      // DecimalVector's public setter enforces the declared precision, so write the
      // signed 128-bit unscaled payload directly to construct a well-framed IPC file
      // containing a value outside DECIMAL(5,2)'s legal range.
      vector.setSafe(0, new java.math.BigDecimal("999.99"));
      byte[] bigEndian = new java.math.BigInteger("100000").toByteArray();
      byte[] littleEndian = new byte[16];
      for (int i = 0; i < bigEndian.length; i++) littleEndian[i] = bigEndian[bigEndian.length - 1 - i];
      vector.getDataBuffer().setBytes(0, littleEndian);
      root.setRowCount(1);
      writer.writeBatch();
      writer.end();
    }

    var config = new io.zingg.duckdb.api.RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("decimal-overflow.duckdb"), 1, 0, null, 1);
    try (var runtime = new DuckRuntime(config); var job = (DuckJob) runtime.openJob()) {
      int registeredBefore = job.materializedCount();
      for (boolean useAppender : new boolean[] {false, true}) {
        boolean[] firstBatchCommitted = {false};
        var failure = org.junit.jupiter.api.Assertions.assertThrows(
            io.zingg.duckdb.api.DuckException.class,
            () -> ArrowFileSupport.read(job, input, useAppender, importedRows -> {
              if (importedRows == 1) firstBatchCommitted[0] = true;
            }));
        assertEquals("Arrow IPC ingestion failed: " + input, failure.getMessage());
        org.junit.jupiter.api.Assertions.assertInstanceOf(java.sql.SQLException.class, failure.getCause(),
            "the failure must originate from DuckDB's JDBC/Appender write path, not Arrow decoding");
        org.junit.jupiter.api.Assertions.assertTrue(firstBatchCommitted[0],
            "the first valid batch must commit before the out-of-range decimal is inserted");
        assertEquals(registeredBefore, job.materializedCount(),
            "write failure must release materialization registration");
        try (var statement = job.connection().createStatement();
             var rows = statement.executeQuery("select count(*) from information_schema.tables where table_schema='"
                 + job.schemaName() + "'")) {
          org.junit.jupiter.api.Assertions.assertTrue(rows.next());
          assertEquals(0, rows.getInt(1), "write failure must drop the partially populated table");
        }
      }
    }
  }

  @Test
  void forciblyKilledArrowImportLeavesNoOrphanTablesAfterDatabaseRestart(@TempDir Path temp)
      throws Exception {
    Path input = temp.resolve("kill-input.arrow");
    try (var allocator = new org.apache.arrow.memory.RootAllocator();
         var root = org.apache.arrow.vector.VectorSchemaRoot.create(
             new org.apache.arrow.vector.types.pojo.Schema(List.of(
                 new org.apache.arrow.vector.types.pojo.Field("value",
                     org.apache.arrow.vector.types.pojo.FieldType.nullable(
                         new org.apache.arrow.vector.types.pojo.ArrowType.Int(32, true)), null))), allocator);
         var channel = java.nio.channels.Channels.newChannel(java.nio.file.Files.newOutputStream(input));
         var writer = new org.apache.arrow.vector.ipc.ArrowStreamWriter(root, null, channel)) {
      var vector = (org.apache.arrow.vector.IntVector) root.getVector("value");
      writer.start();
      vector.setSafe(0, 10);
      root.setRowCount(1);
      writer.writeBatch();
      vector.setSafe(0, 20);
      root.setRowCount(1);
      writer.writeBatch();
      writer.end();
    }

    Path database = temp.resolve("killed-import.duckdb");
    Path ready = temp.resolve("first-batch-committed.txt");
    Path childLog = temp.resolve("arrow-kill-child.log");
    String javaCommand = Path.of(System.getProperty("java.home"), "bin",
        System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java")
        .toString();
    var child = new ProcessBuilder(javaCommand,
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
        "-cp", System.getProperty("java.class.path"),
        ArrowIngressKillProbe.class.getName(), database.toString(), input.toString(), ready.toString())
        .directory(temp.toFile()).redirectErrorStream(true).redirectOutput(childLog.toFile()).start();
    try {
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
      while (!java.nio.file.Files.exists(ready) && child.isAlive() && System.nanoTime() < deadline)
        Thread.sleep(10);
      org.junit.jupiter.api.Assertions.assertTrue(java.nio.file.Files.exists(ready),
          "child must commit the first Arrow batch and block before reading the next one; child output: "
              + java.nio.file.Files.readString(childLog));
      org.junit.jupiter.api.Assertions.assertTrue(child.destroyForcibly().waitFor(10,
          java.util.concurrent.TimeUnit.SECONDS), "forced worker termination must complete");
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
      }
    }

    try (var runtime = new DuckRuntime(new io.zingg.duckdb.api.RuntimeConfig(
             "jdbc:duckdb:" + database, 1, 0, null, 1));
         var job = (DuckJob) runtime.openJob();
         var statement = job.connection().createStatement()) {
      try (var rows = statement.executeQuery("select count(*) from information_schema.tables where table_name like 'zd_arrow_%'")) {
        org.junit.jupiter.api.Assertions.assertTrue(rows.next());
        assertEquals(0, rows.getInt(1), "restart must not expose a partially imported Arrow table");
      }
      try (var rows = statement.executeQuery("select count(*) from information_schema.schemata "
          + "where substr(schema_name, 1, 11) = '_zingg_run_' and schema_name <> '" + job.schemaName() + "'")) {
        org.junit.jupiter.api.Assertions.assertTrue(rows.next());
        assertEquals(0, rows.getInt(1), "restart must remove the killed job's orphan run schema");
      }
    }
  }

  @Test
  void arrowIngressRejectsSymbolicLinkWithoutReadingExternalTarget(@TempDir Path temp) throws Exception {
    var config = new io.zingg.duckdb.api.RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("arrow-symlink.duckdb"), 1, 0, null, 1);
    Path external = temp.resolve("external.arrow");
    Path linked = temp.resolve("linked.arrow");
    java.nio.file.Files.write(external, new byte[] {0x01, 0x02, 0x03, 0x04});
    try {
      java.nio.file.Files.createSymbolicLink(linked, external.getFileName());
    } catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
      org.junit.jupiter.api.Assumptions.abort("symbolic links are unavailable: " + unavailable);
    }
    try (var runtime = new DuckRuntime(config); var job = runtime.openJob()) {
      var failure = org.junit.jupiter.api.Assertions.assertThrows(io.zingg.duckdb.api.DuckException.class,
          () -> ArrowFileSupport.read((DuckJob) job, linked));
      org.junit.jupiter.api.Assertions.assertNotNull(failure.getCause());
      assertEquals(java.util.List.of(), job.sql("SELECT table_name FROM information_schema.tables "
          + "WHERE table_schema = " + DuckExpr.literal(((DuckJob) job).schemaName()).sql()
          + " AND table_name LIKE 'zd_arrow_%'").collect());
    }
    org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[] {0x01, 0x02, 0x03, 0x04},
        java.nio.file.Files.readAllBytes(external));
  }

  @Test
  void arrowRoundTripHandlesDateTimestampAndBinary(@TempDir Path temp) throws Exception {
    var config = new io.zingg.duckdb.api.RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("arrow-types.duckdb"), 1, 0, null, 1);
    Path output = temp.resolve("types.arrow");
    try (var runtime = new DuckRuntime(config); var job = runtime.openJob()) {
      job.sql("select DATE '2020-01-02' as day, TIMESTAMP '2020-01-02 03:04:05' as moment, CAST('123.4567' AS DECIMAL(18,4)) as amount, from_hex('CAFE') as payload").writeArrow(output);
      var jdbcPath = ArrowFileSupport.read((DuckJob) job, output);
      var appenderPath = ArrowFileSupport.readWithAppender((DuckJob) job, output);
      assertEquals(jdbcPath.schema(), appenderPath.schema());
      assertEquals(jdbcPath.collect(), appenderPath.collect(), "Appender must preserve date, decimal and binary values");
      var roundTrip = new DuckPipeReader(job).read(java.util.List.of(output), DuckPipeReader.UnionMode.MATCH_BY_NAME);
      assertEquals(1, roundTrip.count());
      assertEquals(java.time.LocalDate.of(2020, 1, 2), roundTrip.collect().get(0).get("day"));
      assertEquals(0, ((java.math.BigDecimal)roundTrip.collect().get(0).get("amount")).compareTo(new java.math.BigDecimal("123.4567")));
      assertEquals(true, roundTrip.schema().stream().anyMatch(c -> c.name().equals("amount") && c.typeName().contains("18,4")));
    }
  }

  @Test
  void arrowTimestampUnitsAndTimezoneArePreservedOnIngress(@TempDir Path temp) throws Exception {
    Path input = temp.resolve("timestamp-units.arrow");
    var fields = java.util.List.of(
        arrowTimestampField("seconds", org.apache.arrow.vector.types.TimeUnit.SECOND, null),
        arrowTimestampField("millis", org.apache.arrow.vector.types.TimeUnit.MILLISECOND, null),
        arrowTimestampField("micros", org.apache.arrow.vector.types.TimeUnit.MICROSECOND, null),
        arrowTimestampField("nanos", org.apache.arrow.vector.types.TimeUnit.NANOSECOND, null),
        arrowTimestampField("zoned", org.apache.arrow.vector.types.TimeUnit.MICROSECOND, "America/Los_Angeles"));
    try (var allocator = new org.apache.arrow.memory.RootAllocator();
         var root = org.apache.arrow.vector.VectorSchemaRoot.create(new org.apache.arrow.vector.types.pojo.Schema(fields), allocator);
         var channel = java.nio.channels.Channels.newChannel(java.nio.file.Files.newOutputStream(input));
         var writer = new org.apache.arrow.vector.ipc.ArrowStreamWriter(root, null, channel)) {
      ((org.apache.arrow.vector.TimeStampSecVector) root.getVector("seconds")).setSafe(0, 1_700_000_000L);
      ((org.apache.arrow.vector.TimeStampMilliVector) root.getVector("millis")).setSafe(0, 1_700_000_000_123L);
      ((org.apache.arrow.vector.TimeStampMicroVector) root.getVector("micros")).setSafe(0, 1_700_000_000_123_456L);
      ((org.apache.arrow.vector.TimeStampNanoVector) root.getVector("nanos")).setSafe(0, 1_700_000_000_123_456_789L);
      ((org.apache.arrow.vector.TimeStampMicroTZVector) root.getVector("zoned")).setSafe(0, 1_700_000_000_123_456L);
      ((org.apache.arrow.vector.TimeStampSecVector) root.getVector("seconds")).setSafe(1, -1L);
      ((org.apache.arrow.vector.TimeStampMilliVector) root.getVector("millis")).setSafe(1, -1L);
      ((org.apache.arrow.vector.TimeStampMicroVector) root.getVector("micros")).setSafe(1, -1L);
      ((org.apache.arrow.vector.TimeStampNanoVector) root.getVector("nanos")).setSafe(1, -1L);
      ((org.apache.arrow.vector.TimeStampMicroTZVector) root.getVector("zoned")).setSafe(1, -1L);
      root.setRowCount(2);
      writer.start();
      writer.writeBatch();
      writer.end();
    }
    var config = new io.zingg.duckdb.api.RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("timestamp-units.duckdb"), 1, 0, null, 1);
    try (var runtime = new DuckRuntime(config); var job = runtime.openJob()) {
      var jdbcPath = ArrowFileSupport.read((DuckJob) job, input);
      var appenderPath = ArrowFileSupport.readWithAppender((DuckJob) job, input);
      assertEquals(jdbcPath.schema(), appenderPath.schema());
      var rows = jdbcPath.collect();
      assertEquals(rows, appenderPath.collect(), "Appender path must preserve timestamps and zones");
      var row = rows.get(0);
      assertEquals(java.sql.Timestamp.valueOf(java.time.LocalDateTime.of(2023, 11, 14, 22, 13, 20)), row.get("seconds"));
      assertEquals(java.sql.Timestamp.valueOf(java.time.LocalDateTime.of(2023, 11, 14, 22, 13, 20, 123_000_000)), row.get("millis"));
      assertEquals(java.sql.Timestamp.valueOf(java.time.LocalDateTime.of(2023, 11, 14, 22, 13, 20, 123_456_000)), row.get("micros"));
      assertEquals(java.sql.Timestamp.valueOf(java.time.LocalDateTime.of(2023, 11, 14, 22, 13, 20, 123_456_000)), row.get("nanos"));
      assertEquals(java.time.Instant.parse("2023-11-14T22:13:20.123456Z"), ((java.time.OffsetDateTime) row.get("zoned")).toInstant());
      var beforeEpoch = rows.get(1);
      assertEquals(java.sql.Timestamp.valueOf(java.time.LocalDateTime.of(1969, 12, 31, 23, 59, 59)), beforeEpoch.get("seconds"));
      assertEquals(java.sql.Timestamp.valueOf(java.time.LocalDateTime.of(1969, 12, 31, 23, 59, 59, 999_000_000)), beforeEpoch.get("millis"));
      assertEquals(java.sql.Timestamp.valueOf(java.time.LocalDateTime.of(1969, 12, 31, 23, 59, 59, 999_999_000)), beforeEpoch.get("micros"));
      assertEquals(java.sql.Timestamp.valueOf(java.time.LocalDateTime.of(1969, 12, 31, 23, 59, 59, 999_999_000)), beforeEpoch.get("nanos"));
      assertEquals(java.time.Instant.parse("1969-12-31T23:59:59.999999Z"), ((java.time.OffsetDateTime) beforeEpoch.get("zoned")).toInstant());
    }
  }

  @Test
  void arrowUnsigned64MaximumIsPreservedExactly(@TempDir Path temp) throws Exception {
    Path input = temp.resolve("uint64.arrow");
    var fields = java.util.List.of(arrowIntegerField("u8", 8, false), arrowIntegerField("u16", 16, false),
        arrowIntegerField("u32", 32, false), arrowIntegerField("u64", 64, false));
    try (var allocator = new org.apache.arrow.memory.RootAllocator();
         var root = org.apache.arrow.vector.VectorSchemaRoot.create(new org.apache.arrow.vector.types.pojo.Schema(fields), allocator);
         var channel = java.nio.channels.Channels.newChannel(java.nio.file.Files.newOutputStream(input));
         var writer = new org.apache.arrow.vector.ipc.ArrowStreamWriter(root, null, channel)) {
      ((org.apache.arrow.vector.UInt1Vector) root.getVector("u8")).setSafe(0, 255);
      ((org.apache.arrow.vector.UInt2Vector) root.getVector("u16")).setSafe(0, 65_535);
      ((org.apache.arrow.vector.UInt4Vector) root.getVector("u32")).setSafe(0, -1);
      ((org.apache.arrow.vector.UInt8Vector) root.getVector("u64")).setSafe(0, -1L);
      root.setRowCount(1);
      writer.start();
      writer.writeBatch();
      writer.end();
    }
    var config = new io.zingg.duckdb.api.RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("uint64.duckdb"), 1, 0, null, 1);
    try (var runtime = new DuckRuntime(config); var job = runtime.openJob()) {
      var frame = ArrowFileSupport.read((DuckJob) job, input);
      assertEquals("BIGINT", frame.schema().stream().filter(c -> c.name().equals("u8")).findFirst().orElseThrow().typeName());
      assertEquals("BIGINT", frame.schema().stream().filter(c -> c.name().equals("u16")).findFirst().orElseThrow().typeName());
      assertEquals("BIGINT", frame.schema().stream().filter(c -> c.name().equals("u32")).findFirst().orElseThrow().typeName());
      assertEquals("DECIMAL(20,0)", frame.schema().stream().filter(c -> c.name().equals("u64")).findFirst().orElseThrow().typeName());
      var row = frame.collect().get(0);
      assertEquals(255L, ((Number) row.get("u8")).longValue());
      assertEquals(65_535L, ((Number) row.get("u16")).longValue());
      assertEquals(4_294_967_295L, ((Number) row.get("u32")).longValue());
      assertEquals(new java.math.BigDecimal("18446744073709551615"), row.get("u64"));
      var appenderFrame = ArrowFileSupport.readWithAppender((DuckJob) job, input);
      assertEquals(frame.schema(), appenderFrame.schema());
      assertEquals(frame.collect(), appenderFrame.collect());
    }
  }

  @Test
  void arrowSignedIntegerWidthsMatchBetweenJdbcAndAppender(@TempDir Path temp) throws Exception {
    Path input = temp.resolve("signed-widths.arrow");
    var fields = java.util.List.of(arrowIntegerField("i8", 8, true), arrowIntegerField("i16", 16, true),
        arrowIntegerField("i32", 32, true), arrowIntegerField("i64", 64, true));
    try (var allocator = new org.apache.arrow.memory.RootAllocator();
         var root = org.apache.arrow.vector.VectorSchemaRoot.create(new org.apache.arrow.vector.types.pojo.Schema(fields), allocator);
         var channel = java.nio.channels.Channels.newChannel(java.nio.file.Files.newOutputStream(input));
         var writer = new org.apache.arrow.vector.ipc.ArrowStreamWriter(root, null, channel)) {
      ((org.apache.arrow.vector.TinyIntVector) root.getVector("i8")).setSafe(0, Byte.MIN_VALUE);
      ((org.apache.arrow.vector.SmallIntVector) root.getVector("i16")).setSafe(0, Short.MIN_VALUE);
      ((org.apache.arrow.vector.IntVector) root.getVector("i32")).setSafe(0, Integer.MIN_VALUE);
      ((org.apache.arrow.vector.BigIntVector) root.getVector("i64")).setSafe(0, Long.MIN_VALUE);
      ((org.apache.arrow.vector.TinyIntVector) root.getVector("i8")).setSafe(1, Byte.MAX_VALUE);
      ((org.apache.arrow.vector.SmallIntVector) root.getVector("i16")).setSafe(1, Short.MAX_VALUE);
      ((org.apache.arrow.vector.IntVector) root.getVector("i32")).setSafe(1, Integer.MAX_VALUE);
      ((org.apache.arrow.vector.BigIntVector) root.getVector("i64")).setSafe(1, Long.MAX_VALUE);
      root.setRowCount(2);
      writer.start();
      writer.writeBatch();
      writer.end();
    }
    var config = new io.zingg.duckdb.api.RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("signed-widths.duckdb"), 1, 0, null, 1);
    try (var runtime = new DuckRuntime(config); var job = runtime.openJob()) {
      var jdbcFrame = ArrowFileSupport.read((DuckJob) job, input);
      var appenderFrame = ArrowFileSupport.readWithAppender((DuckJob) job, input);
      assertEquals(java.util.List.of("TINYINT", "SMALLINT", "INTEGER", "BIGINT"),
          jdbcFrame.schema().stream().map(io.zingg.duckdb.api.Column::typeName).toList());
      assertEquals(jdbcFrame.schema(), appenderFrame.schema());
      assertEquals(jdbcFrame.collect(), appenderFrame.collect());
      assertEquals(Byte.MIN_VALUE, jdbcFrame.collect().get(0).get("i8"));
      assertEquals(Short.MIN_VALUE, jdbcFrame.collect().get(0).get("i16"));
      assertEquals(Integer.MIN_VALUE, jdbcFrame.collect().get(0).get("i32"));
      assertEquals(Long.MIN_VALUE, jdbcFrame.collect().get(0).get("i64"));
    }
  }

  @Test
  void arrowHalfSingleAndDoublePrecisionSurviveIngressAppenderAndEgress(@TempDir Path temp) throws Exception {
    Path input = temp.resolve("floating-widths.arrow");
    Path output = temp.resolve("floating-widths-roundtrip.arrow");
    var singleType = new org.apache.arrow.vector.types.pojo.ArrowType.FloatingPoint(
        org.apache.arrow.vector.types.FloatingPointPrecision.SINGLE);
    var halfType = new org.apache.arrow.vector.types.pojo.ArrowType.FloatingPoint(
        org.apache.arrow.vector.types.FloatingPointPrecision.HALF);
    var doubleType = new org.apache.arrow.vector.types.pojo.ArrowType.FloatingPoint(
        org.apache.arrow.vector.types.FloatingPointPrecision.DOUBLE);
    var fields = List.of(
        new org.apache.arrow.vector.types.pojo.Field("half_value",
            org.apache.arrow.vector.types.pojo.FieldType.nullable(halfType), null),
        new org.apache.arrow.vector.types.pojo.Field("single_value",
            org.apache.arrow.vector.types.pojo.FieldType.nullable(singleType), null),
        new org.apache.arrow.vector.types.pojo.Field("double_value",
            org.apache.arrow.vector.types.pojo.FieldType.nullable(doubleType), null));
    try (var allocator = new org.apache.arrow.memory.RootAllocator();
         var root = org.apache.arrow.vector.VectorSchemaRoot.create(
             new org.apache.arrow.vector.types.pojo.Schema(fields), allocator);
         var channel = java.nio.channels.Channels.newChannel(java.nio.file.Files.newOutputStream(input));
         var writer = new org.apache.arrow.vector.ipc.ArrowStreamWriter(root, null, channel)) {
      var half = (org.apache.arrow.vector.Float2Vector) root.getVector("half_value");
      var single = (org.apache.arrow.vector.Float4Vector) root.getVector("single_value");
      var wide = (org.apache.arrow.vector.Float8Vector) root.getVector("double_value");
      half.setSafe(0, (short) 0x3e00); // 1.5
      half.setSafe(1, (short) 0x0001); // Smallest positive binary16 subnormal: 2^-24.
      half.setSafe(2, (short) 0x03ff); // Largest positive subnormal.
      half.setSafe(3, (short) 0x0400); // Smallest positive normal.
      half.setSafe(4, (short) 0x7bff); // Largest finite value.
      half.setSafe(5, (short) 0x8000); // Negative zero.
      half.setSafe(6, (short) 0x7c00); // Positive infinity.
      half.setSafe(7, (short) 0x7e00); // NaN.
      half.setNull(8);
      single.setSafe(0, -123.25f);
      wide.setSafe(0, -123.25d);
      single.setNull(1);
      wide.setSafe(1, 1.0d / 10.0d);
      single.setSafe(2, 0.1f);
      wide.setNull(2);
      root.setRowCount(9);
      writer.start();
      writer.writeBatch();
      writer.end();
    }

    var config = new io.zingg.duckdb.api.RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("floating-widths.duckdb"), 1, 0, null, 1);
    try (var runtime = new DuckRuntime(config); var job = runtime.openJob()) {
      var jdbcFrame = ArrowFileSupport.read((DuckJob) job, input);
      var appenderFrame = ArrowFileSupport.readWithAppender((DuckJob) job, input);
      assertEquals(List.of("FLOAT", "FLOAT", "DOUBLE"),
          jdbcFrame.schema().stream().map(io.zingg.duckdb.api.Column::typeName).toList());
      assertEquals(jdbcFrame.schema(), appenderFrame.schema());
      assertEquals(jdbcFrame.collect(), appenderFrame.collect());
      assertEquals(-123.25f, jdbcFrame.collect().get(0).get("single_value"));
      assertEquals(1.5f, jdbcFrame.collect().get(0).get("half_value"));
      assertEquals(Math.scalb(1.0f, -24), jdbcFrame.collect().get(1).get("half_value"));
      assertEquals(1023 * Math.scalb(1.0f, -24), jdbcFrame.collect().get(2).get("half_value"));
      assertEquals(Math.scalb(1.0f, -14), jdbcFrame.collect().get(3).get("half_value"));
      assertEquals(65504.0f, jdbcFrame.collect().get(4).get("half_value"));
      assertEquals(Float.floatToRawIntBits(-0.0f), Float.floatToRawIntBits(
          (Float) jdbcFrame.collect().get(5).get("half_value")));
      assertEquals(Float.POSITIVE_INFINITY, jdbcFrame.collect().get(6).get("half_value"));
      org.junit.jupiter.api.Assertions.assertTrue(Float.isNaN(
          (Float) jdbcFrame.collect().get(7).get("half_value")));
      assertEquals(null, jdbcFrame.collect().get(8).get("half_value"));
      assertEquals(1.0d / 10.0d, jdbcFrame.collect().get(1).get("double_value"));

      jdbcFrame.writeArrow(output);
      try (var allocator = new org.apache.arrow.memory.RootAllocator();
           var reader = new org.apache.arrow.vector.ipc.ArrowStreamReader(
               java.nio.file.Files.newInputStream(output), allocator)) {
        org.junit.jupiter.api.Assertions.assertTrue(reader.loadNextBatch());
        var schema = reader.getVectorSchemaRoot().getSchema();
        assertEquals(org.apache.arrow.vector.types.FloatingPointPrecision.SINGLE,
            ((org.apache.arrow.vector.types.pojo.ArrowType.FloatingPoint)
                schema.findField("half_value").getType()).getPrecision(),
            "DuckDB FLOAT egress intentionally widens Arrow HALF to SINGLE");
        assertEquals(org.apache.arrow.vector.types.FloatingPointPrecision.SINGLE,
            ((org.apache.arrow.vector.types.pojo.ArrowType.FloatingPoint)
                schema.findField("single_value").getType()).getPrecision());
        assertEquals(org.apache.arrow.vector.types.FloatingPointPrecision.DOUBLE,
            ((org.apache.arrow.vector.types.pojo.ArrowType.FloatingPoint)
                schema.findField("double_value").getType()).getPrecision());
      }
      var roundTrip = ArrowFileSupport.read((DuckJob) job, output);
      assertEquals(jdbcFrame.schema(), roundTrip.schema());
      assertEquals(jdbcFrame.collect(), roundTrip.collect());
    }
  }

  private static org.apache.arrow.vector.types.pojo.Field arrowIntegerField(String name, int width, boolean signed) {
    return new org.apache.arrow.vector.types.pojo.Field(name,
        org.apache.arrow.vector.types.pojo.FieldType.nullable(
            new org.apache.arrow.vector.types.pojo.ArrowType.Int(width, signed)), null);
  }

  private static org.apache.arrow.vector.types.pojo.Field arrowTimestampField(
      String name, org.apache.arrow.vector.types.TimeUnit unit, String timezone) {
    return new org.apache.arrow.vector.types.pojo.Field(name,
        org.apache.arrow.vector.types.pojo.FieldType.nullable(
            new org.apache.arrow.vector.types.pojo.ArrowType.Timestamp(unit, timezone)), null);
  }

  @Test
  void arrowEgressRejectsUnsupportedNestedTypesInsteadOfCoercing(@TempDir Path temp) {
    var config = new io.zingg.duckdb.api.RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("arrow-nested.duckdb"), 1, 0, null, 1);
    Path output = temp.resolve("nested.arrow");
    try (var runtime = new DuckRuntime(config); var job = runtime.openJob()) {
      var error = org.junit.jupiter.api.Assertions.assertThrows(io.zingg.duckdb.api.DuckException.class,
          () -> job.sql("select [1, 2, 3] as values").writeArrow(output));
      org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("unsupported JDBC type"));
      org.junit.jupiter.api.Assertions.assertFalse(java.nio.file.Files.exists(output));
    }
  }

  @Test
  void arrowFileFramingIsAcceptedOnIngress(@TempDir Path temp) throws Exception {
    var config = new io.zingg.duckdb.api.RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("arrow-file.duckdb"), 1, 0, null, 1);
    Path input = temp.resolve("external.arrow");
    try (var allocator = new org.apache.arrow.memory.RootAllocator();
         var root = org.apache.arrow.vector.VectorSchemaRoot.create(
             new org.apache.arrow.vector.types.pojo.Schema(java.util.List.of(
                 new org.apache.arrow.vector.types.pojo.Field("value",
                     org.apache.arrow.vector.types.pojo.FieldType.nullable(
                         new org.apache.arrow.vector.types.pojo.ArrowType.Int(32, true)), null))), allocator);
         var channel = java.nio.channels.Channels.newChannel(java.nio.file.Files.newOutputStream(input));
         var writer = new org.apache.arrow.vector.ipc.ArrowFileWriter(root, null, channel)) {
      var vector = (org.apache.arrow.vector.IntVector) root.getVector("value");
      vector.setSafe(0, 42);
      root.setRowCount(1);
      writer.start();
      writer.writeBatch();
      writer.end();
    }
    try (var runtime = new DuckRuntime(config); var job = runtime.openJob()) {
      var frame = new DuckPipeReader(job).read(java.util.List.of(input), DuckPipeReader.UnionMode.MATCH_BY_NAME);
      assertEquals(1, frame.count());
      assertEquals(42, ((Number)frame.collect().get(0).get("value")).intValue());
    }
  }

  @Test
  void arrowIngressBudgetFailureDropsPartialJobTableImmediately(@TempDir Path temp) throws Exception {
    Path input = temp.resolve("two-rows.arrow");
    var sourceConfig = new io.zingg.duckdb.api.RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("arrow-source.duckdb"), 1, 0, null, 1);
    try (var runtime = new DuckRuntime(sourceConfig); var job = runtime.openJob()) {
      job.sql("select * from range(2) t(value)").writeArrow(input);
    }

    var targetConfig = new io.zingg.duckdb.api.RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("arrow-target.duckdb"), 1, 0, null, 1);
    try (var runtime = new DuckRuntime(targetConfig);
         var job = (DuckJob) runtime.openJob(new ResourceBudget(0, 1, 0, 0, 0, 0))) {
      var reader = new DuckPipeReader(job);
      int registeredBefore = job.materializedCount();
      var failure = org.junit.jupiter.api.Assertions.assertThrows(
          io.zingg.duckdb.api.DuckException.class,
          () -> reader.read(java.util.List.of(input), DuckPipeReader.UnionMode.MATCH_BY_NAME));
      assertEquals("row budget exceeded: 2 > 1", failure.getMessage());
      assertEquals(registeredBefore, job.materializedCount(),
          "failed JDBC ingress must release its materialization registration immediately");
      var appenderFailure = org.junit.jupiter.api.Assertions.assertThrows(
          io.zingg.duckdb.api.DuckException.class, () -> ArrowFileSupport.readWithAppender(job, input));
      assertEquals("row budget exceeded: 2 > 1", appenderFailure.getMessage());
      assertEquals(registeredBefore, job.materializedCount(),
          "failed Appender ingress must release its materialization registration immediately");
      try (var statement = job.connection().createStatement();
           var rows = statement.executeQuery("select count(*) from information_schema.tables where table_schema='" + job.schemaName() + "'")) {
        org.junit.jupiter.api.Assertions.assertTrue(rows.next());
        assertEquals(0, rows.getInt(1), "failed Arrow ingress must not leave a registered table");
      }
    }
  }

  @Test
  void arrowIngressLaterBatchBudgetFailureRollsBackBothImportPaths(@TempDir Path temp) throws Exception {
    Path input = temp.resolve("later-batch-over-budget.arrow");
    try (var allocator = new org.apache.arrow.memory.RootAllocator();
         var root = org.apache.arrow.vector.VectorSchemaRoot.create(
             new org.apache.arrow.vector.types.pojo.Schema(java.util.List.of(
                 new org.apache.arrow.vector.types.pojo.Field("value",
                     org.apache.arrow.vector.types.pojo.FieldType.nullable(
                         new org.apache.arrow.vector.types.pojo.ArrowType.Int(32, true)), null))), allocator);
         var channel = java.nio.channels.Channels.newChannel(java.nio.file.Files.newOutputStream(input));
         var writer = new org.apache.arrow.vector.ipc.ArrowStreamWriter(root, null, channel)) {
      var vector = (org.apache.arrow.vector.IntVector) root.getVector("value");
      writer.start();
      vector.setSafe(0, 10);
      root.setRowCount(1);
      writer.writeBatch();
      vector.setSafe(0, 20);
      root.setRowCount(1);
      writer.writeBatch();
      writer.end();
    }

    var config = new io.zingg.duckdb.api.RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("later-batch-budget.duckdb"), 1, 0, null, 1);
    try (var runtime = new DuckRuntime(config);
         var job = (DuckJob) runtime.openJob(new ResourceBudget(0, 1, 0, 0, 0, 0))) {
      int registeredBefore = job.materializedCount();
      for (boolean useAppender : new boolean[] {false, true}) {
        var failure = org.junit.jupiter.api.Assertions.assertThrows(
            io.zingg.duckdb.api.DuckException.class,
            () -> {
              if (useAppender) ArrowFileSupport.readWithAppender(job, input);
              else ArrowFileSupport.read(job, input);
            });
        assertEquals("row budget exceeded: 2 > 1", failure.getMessage());
        assertEquals(registeredBefore, job.materializedCount(),
            "later-batch failure must remove the job's materialization registration");
        try (var statement = job.connection().createStatement();
             var rows = statement.executeQuery("select count(*) from information_schema.tables where table_schema='" + job.schemaName() + "'")) {
          org.junit.jupiter.api.Assertions.assertTrue(rows.next());
          assertEquals(0, rows.getInt(1), "later-batch failure must drop the partially populated table immediately");
        }
      }
    }
  }

  @Test
  void arrowIngressUnexpectedFailureAfterCommittedBatchRollsBackBothImportPaths(@TempDir Path temp)
      throws Exception {
    Path input = temp.resolve("observer-failure.arrow");
    try (var allocator = new org.apache.arrow.memory.RootAllocator();
         var root = org.apache.arrow.vector.VectorSchemaRoot.create(
             new org.apache.arrow.vector.types.pojo.Schema(java.util.List.of(
                 new org.apache.arrow.vector.types.pojo.Field("value",
                     org.apache.arrow.vector.types.pojo.FieldType.nullable(
                         new org.apache.arrow.vector.types.pojo.ArrowType.Int(32, true)), null))), allocator);
         var channel = java.nio.channels.Channels.newChannel(java.nio.file.Files.newOutputStream(input));
         var writer = new org.apache.arrow.vector.ipc.ArrowStreamWriter(root, null, channel)) {
      var vector = (org.apache.arrow.vector.IntVector) root.getVector("value");
      writer.start();
      vector.setSafe(0, 10);
      root.setRowCount(1);
      writer.writeBatch();
      vector.setSafe(0, 20);
      root.setRowCount(1);
      writer.writeBatch();
      writer.end();
    }

    var config = new io.zingg.duckdb.api.RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("observer-failure.duckdb"), 1, 0, null, 1);
    try (var runtime = new DuckRuntime(config); var job = (DuckJob) runtime.openJob()) {
      int registeredBefore = job.materializedCount();
      for (boolean useAppender : new boolean[] {false, true}) {
        var failure = org.junit.jupiter.api.Assertions.assertThrows(
            io.zingg.duckdb.api.DuckException.class,
            () -> ArrowFileSupport.read(job, input, useAppender, importedRows -> {
              if (importedRows == 1) throw new java.io.IOException("injected post-batch failure");
            }));
        assertEquals("Arrow IPC ingestion failed: " + input, failure.getMessage());
        org.junit.jupiter.api.Assertions.assertInstanceOf(java.io.IOException.class, failure.getCause());
        assertEquals("injected post-batch failure", failure.getCause().getMessage());
        assertEquals(registeredBefore, job.materializedCount(),
            "post-batch failure must release materialization registration");
        try (var statement = job.connection().createStatement();
             var rows = statement.executeQuery("select count(*) from information_schema.tables where table_schema='" + job.schemaName() + "'")) {
          org.junit.jupiter.api.Assertions.assertTrue(rows.next());
          assertEquals(0, rows.getInt(1), "post-batch failure must remove the committed partial table");
        }
      }
    }
  }

  @Test
  void arrowIngressInterruptionPreservesInterruptAndRollsBackBothImportPaths(@TempDir Path temp)
      throws Exception {
    Path input = temp.resolve("interrupted.arrow");
    try (var allocator = new org.apache.arrow.memory.RootAllocator();
         var root = org.apache.arrow.vector.VectorSchemaRoot.create(
             new org.apache.arrow.vector.types.pojo.Schema(java.util.List.of(
                 new org.apache.arrow.vector.types.pojo.Field("value",
                     org.apache.arrow.vector.types.pojo.FieldType.nullable(
                         new org.apache.arrow.vector.types.pojo.ArrowType.Int(32, true)), null))), allocator);
         var channel = java.nio.channels.Channels.newChannel(java.nio.file.Files.newOutputStream(input));
         var writer = new org.apache.arrow.vector.ipc.ArrowStreamWriter(root, null, channel)) {
      var vector = (org.apache.arrow.vector.IntVector) root.getVector("value");
      writer.start();
      vector.setSafe(0, 10);
      root.setRowCount(1);
      writer.writeBatch();
      vector.setSafe(0, 20);
      root.setRowCount(1);
      writer.writeBatch();
      writer.end();
    }

    var config = new io.zingg.duckdb.api.RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("interrupted.duckdb"), 1, 0, null, 1);
    try (var runtime = new DuckRuntime(config); var job = (DuckJob) runtime.openJob()) {
      int registeredBefore = job.materializedCount();
      for (boolean useAppender : new boolean[] {false, true}) {
        try {
          var failure = org.junit.jupiter.api.Assertions.assertThrows(
              io.zingg.duckdb.api.DuckException.class,
              () -> ArrowFileSupport.read(job, input, useAppender, importedRows -> {
                if (importedRows == 1) throw new InterruptedException("injected Arrow cancellation");
              }));
          assertEquals("Arrow IPC ingestion failed: " + input, failure.getMessage());
          org.junit.jupiter.api.Assertions.assertInstanceOf(InterruptedException.class, failure.getCause());
          org.junit.jupiter.api.Assertions.assertTrue(Thread.currentThread().isInterrupted(),
              "interrupted status must survive Arrow cleanup and exception wrapping");
          assertEquals(registeredBefore, job.materializedCount(),
              "interrupted ingestion must release its materialization registration");
          try (var statement = job.connection().createStatement();
               var rows = statement.executeQuery("select count(*) from information_schema.tables where table_schema='"
                   + job.schemaName() + "'")) {
            org.junit.jupiter.api.Assertions.assertTrue(rows.next());
            assertEquals(0, rows.getInt(1), "interrupted ingestion must drop the partially populated table");
          }
        } finally {
          Thread.interrupted();
        }
      }
    }
  }

  @Test
  void requestCancellationDropsPartialArrowMaterialization(@TempDir Path temp) throws Exception {
    Path input = temp.resolve("request-cancel.arrow");
    try (var allocator = new org.apache.arrow.memory.RootAllocator();
         var root = org.apache.arrow.vector.VectorSchemaRoot.create(
             new org.apache.arrow.vector.types.pojo.Schema(List.of(
                 new org.apache.arrow.vector.types.pojo.Field("value",
                     org.apache.arrow.vector.types.pojo.FieldType.nullable(
                         new org.apache.arrow.vector.types.pojo.ArrowType.Int(32, true)), null))), allocator);
         var channel = java.nio.channels.Channels.newChannel(java.nio.file.Files.newOutputStream(input));
         var writer = new org.apache.arrow.vector.ipc.ArrowStreamWriter(root, null, channel)) {
      var vector = (org.apache.arrow.vector.IntVector) root.getVector("value");
      writer.start();
      vector.setSafe(0, 10);
      root.setRowCount(1);
      writer.writeBatch();
      vector.setSafe(0, 20);
      root.setRowCount(1);
      writer.writeBatch();
      writer.end();
    }

    var config = new io.zingg.duckdb.api.RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("request-cancel-arrow.duckdb"), 1, 0, null, 1);
    try (var runtime = new DuckRuntime(config)) {
      for (boolean useAppender : new boolean[] {false, true}) {
        var cancellation = new DuckCancellation();
        try (var ignored = DuckCancellation.install(cancellation);
             var job = (DuckJob) runtime.openJob()) {
          int registeredBefore = job.materializedCount();
          var failure = org.junit.jupiter.api.Assertions.assertThrows(
              io.zingg.duckdb.api.DuckException.class,
              () -> ArrowFileSupport.read(job, input, useAppender, importedRows -> {
                if (importedRows == 1) cancellation.cancel();
              }));
          assertEquals("operation cancelled", failure.getMessage());
          assertEquals(registeredBefore, job.materializedCount());
          try (var statement = job.connection().createStatement();
               var rows = statement.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema='"
                   + job.schemaName() + "'")) {
            org.junit.jupiter.api.Assertions.assertTrue(rows.next());
            assertEquals(0, rows.getInt(1),
                "request cancellation must drop committed partial Arrow rows before returning");
          }
        }
      }
    }
  }

  @Test
  void predicatePartitionHasExplicitNameAndAlias(@TempDir Path temp) {
    var config = new io.zingg.duckdb.api.RuntimeConfig(
        "jdbc:duckdb:" + temp.resolve("partition.duckdb"), 1, 0, null, 1);
    try (var runtime = new DuckRuntime(config); var job = runtime.openJob()) {
      var frame = job.sql("select * from range(4) t(value)");
      var named = frame.partitionByPredicate(new io.zingg.duckdb.engine.DuckExpr("value % 2 = 0"));
      var alias = frame.split(new io.zingg.duckdb.engine.DuckExpr("value % 2 = 0"));
      assertEquals(2, named.matching().count());
      assertEquals(2, named.remaining().count());
      assertEquals(named.matching().count(), alias.matching().count());
      assertEquals(named.remaining().count(), alias.remaining().count());
    }
  }
}
