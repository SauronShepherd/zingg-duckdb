package io.zingg.duckdb.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.zingg.duckdb.protocol.ProtocolCodec;
import io.zingg.duckdb.protocol.WorkerMessage;
import io.zingg.duckdb.api.PathPolicy;
import io.zingg.duckdb.compat.CompatibilityRuntime;
import io.zingg.duckdb.compat.LocalPhaseExecutors;
import io.zingg.duckdb.compat.Matcher;
import io.zingg.duckdb.compat.TrainingPlan;
import io.zingg.duckdb.compat.ZinggJob;
import io.zingg.duckdb.api.RuntimeConfig;
import io.zingg.duckdb.protocol.PayloadCodec;
import java.io.BufferedReader;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.util.ArrayList;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfiguredWorkerServerTest {
  @Test
  void boundsInputWhileReadingAndContinuesAfterOversizedLine() throws Exception {
    String prefix="boundary\tping\t";
    String exactLimit=prefix+"x".repeat(ProtocolCodec.MAX_LINE_CHARS-prefix.length());
    String oversized="y".repeat(ProtocolCodec.MAX_LINE_CHARS+1);
    String input=exactLimit+"\n"+oversized+"\n"
        +ProtocolCodec.encode(new WorkerMessage("after-error","ping",""))+"\n"
        +ProtocolCodec.encode(new WorkerMessage("shutdown","shutdown",""))+"\n";
    var output=new StringWriter();
    var server=new ConfiguredWorkerServer(null,null,0,0,0,0,0,false);

    server.serve(new StringReader(input),output);

    String[] responses=output.toString().split("\\R");
    assertEquals(4,responses.length);
    assertEquals(new WorkerMessage("boundary","ok","pong"),ProtocolCodec.decode(responses[0]));
    var oversizedResponse=ProtocolCodec.decode(responses[1]);
    assertEquals("",oversizedResponse.id());
    assertEquals("error",oversizedResponse.operation());
    assertTrue(oversizedResponse.payload().contains("size limit"));
    assertEquals(new WorkerMessage("after-error","ok","pong"),ProtocolCodec.decode(responses[2]));
    assertEquals(new WorkerMessage("shutdown","ok","stopping"),ProtocolCodec.decode(responses[3]));
  }

  @Test
  void acceptsCancelControlWhileLongRunningRequestIsExecuting() throws Exception {
    var input = new PipedReader(64 * 1024);
    var inputWriter = new PipedWriter(input);
    var output = new PipedReader(64 * 1024);
    var outputWriter = new PipedWriter(output);
    try (var runtime = new CompatibilityRuntime("jdbc:duckdb:", null)) {
      var server = new ConfiguredWorkerServer(runtime, new PathPolicy(null, null), 0, 0, 0,
          0, 0, true);
      Thread serving = new Thread(() -> {
        try { server.serve(input, outputWriter); }
        catch (Exception failure) { throw new AssertionError(failure); }
      }, "worker-server-test");
      serving.start();
      var responses = new BufferedReader(output);
      inputWriter.write(ProtocolCodec.encode(new WorkerMessage("long-query", "count",
          "SELECT sum(i) FROM range(1000000000000) t(i)")) + "\n");
      inputWriter.flush();
      Thread.sleep(100);
      inputWriter.write(ProtocolCodec.encode(new WorkerMessage("cancel-1", "cancel", "long-query")) + "\n");
      inputWriter.flush();
      var first = ProtocolCodec.decode(responses.readLine());
      var second = ProtocolCodec.decode(responses.readLine());
      var byId = new java.util.HashMap<String, WorkerMessage>();
      byId.put(first.id(), first);
      byId.put(second.id(), second);
      assertEquals("cancel_requested", byId.get("cancel-1").payload());
      assertEquals("error", byId.get("long-query").operation());

      inputWriter.write(ProtocolCodec.encode(new WorkerMessage("after-cancel", "ping", "")) + "\n");
      inputWriter.flush();
      assertEquals(new WorkerMessage("after-cancel", "ok", "pong"),
          ProtocolCodec.decode(responses.readLine()));
      inputWriter.write(ProtocolCodec.encode(new WorkerMessage("stop", "shutdown", "")) + "\n");
      inputWriter.flush();
      assertEquals(new WorkerMessage("stop", "ok", "stopping"),
          ProtocolCodec.decode(responses.readLine()));
      serving.join(5_000);
      assertTrue(!serving.isAlive(), "server should finish after shutdown");
    }
  }

  @Test
  void boundedQueueRejectsOverflowButStillAcceptsCancellation() throws Exception {
    var input = new PipedReader(64 * 1024);
    var inputWriter = new PipedWriter(input);
    var output = new PipedReader(64 * 1024);
    var outputWriter = new PipedWriter(output);
    try (var runtime = new CompatibilityRuntime("jdbc:duckdb:", null)) {
      var server = new ConfiguredWorkerServer(runtime, new PathPolicy(null, null), 0, 0, 0,
          0, 0, true);
      Thread serving = new Thread(() -> {
        try { server.serve(input, outputWriter); }
        catch (Exception failure) { throw new AssertionError(failure); }
      }, "worker-queue-bound-test");
      serving.start();
      var responses = new BufferedReader(output);
      inputWriter.write(ProtocolCodec.encode(new WorkerMessage("long-query", "count",
          "SELECT sum(i) FROM range(1000000000000) t(i)")) + "\n");
      inputWriter.flush();
      Thread.sleep(100);
      for (int i = 0; i <= 64; i++)
        inputWriter.write(ProtocolCodec.encode(new WorkerMessage("queued-" + i, "ping", "")) + "\n");
      inputWriter.write(ProtocolCodec.encode(new WorkerMessage("cancel-full-queue", "cancel", "long-query")) + "\n");
      inputWriter.flush();

      var byId = new java.util.HashMap<String, WorkerMessage>();
      for (int i = 0; i < 67; i++) {
        var response = ProtocolCodec.decode(responses.readLine());
        byId.put(response.id(), response);
      }
      assertEquals("cancel_requested", byId.get("cancel-full-queue").payload());
      assertEquals("error", byId.get("long-query").operation());
      assertEquals("error", byId.get("queued-64").operation());
      assertEquals("worker request queue is full", byId.get("queued-64").payload());
      for (int i = 0; i < 64; i++)
        assertEquals(new WorkerMessage("queued-" + i, "ok", "pong"), byId.get("queued-" + i));

      inputWriter.write(ProtocolCodec.encode(new WorkerMessage("after-queue", "ping", "")) + "\n");
      inputWriter.flush();
      assertEquals(new WorkerMessage("after-queue", "ok", "pong"),
          ProtocolCodec.decode(responses.readLine()));
      inputWriter.write(ProtocolCodec.encode(new WorkerMessage("stop", "shutdown", "")) + "\n");
      inputWriter.flush();
      assertEquals(new WorkerMessage("stop", "ok", "stopping"),
          ProtocolCodec.decode(responses.readLine()));
      serving.join(5_000);
      assertTrue(!serving.isAlive(), "server should finish after shutdown");
    }
  }

  @Test
  void completionCancellationRaceKeepsEveryResponseCorrelatedAndWorkerUsable() throws Exception {
    final int races = 32;
    var input = new PipedReader(64 * 1024);
    var inputWriter = new PipedWriter(input);
    var output = new StringWriter();
    try (var runtime = new CompatibilityRuntime("jdbc:duckdb:", null)) {
      var server = new ConfiguredWorkerServer(runtime, new PathPolicy(null, null), 0, 0, 0,
          0, 0, false);
      Thread serving = new Thread(() -> {
        try { server.serve(input, output); }
        catch (Exception failure) { throw new AssertionError(failure); }
      }, "worker-cancel-completion-race-test");
      serving.start();
      for (int i = 0; i < races; i++) {
        inputWriter.write(ProtocolCodec.encode(new WorkerMessage("race-" + i, "ping", "")) + "\n");
        inputWriter.write(ProtocolCodec.encode(new WorkerMessage("cancel-" + i, "cancel", "race-" + i)) + "\n");
      }
      inputWriter.write(ProtocolCodec.encode(new WorkerMessage("race-stop", "shutdown", "")) + "\n");
      inputWriter.flush();
      serving.join(5_000);
      assertTrue(!serving.isAlive(), "server should finish after shutdown");

      var byId = new java.util.HashMap<String, WorkerMessage>();
      String[] responseLines = output.toString().split("\\R");
      assertEquals(races * 2 + 1, responseLines.length);
      for (String responseLine : responseLines) {
        var response = ProtocolCodec.decode(responseLine);
        assertTrue(byId.put(response.id(), response) == null,
            "response IDs must not be duplicated: " + response.id());
      }
      for (int i = 0; i < races; i++) {
        var request = byId.get("race-" + i);
        assertTrue(request != null, "missing request response race-" + i);
        var cancel = byId.get("cancel-" + i);
        assertTrue(cancel != null, "missing cancel acknowledgement cancel-" + i);
        assertEquals("ok", cancel.operation());
        assertTrue(cancel.payload().equals("cancel_requested") || cancel.payload().equals("not_active"),
            "unexpected cancel race outcome: " + cancel.payload());
        if (!request.equals(new WorkerMessage("race-" + i, "ok", "pong"))) {
          assertEquals("error", request.operation());
          assertEquals("operation cancelled", request.payload());
          assertEquals("cancel_requested", cancel.payload());
        }
      }
      assertEquals(new WorkerMessage("race-stop", "ok", "stopping"), byId.get("race-stop"));
    }
  }

  @Test
  void cancellingRunV2DuringArrowImportRemovesPartialStateAndDoesNotPublishOutput(
      @TempDir Path temp) throws Exception {
    Path input = temp.resolve("large-arrow-input.arrow");
    Path outputPath = temp.resolve("cancelled-output.csv");
    writeLargeArrowInput(input, 1_048_576, 65_536);

    try (var runtime = new CompatibilityRuntime(new RuntimeConfig("jdbc:duckdb:", 1, 0, null, 2), null)) {
    LocalPhaseExecutors.register(runtime,
        new TrainingPlan.Config("\"value\"", "z_training_block", 1_048_576),
        new Matcher.MatchConfig("z_zid", "z_training_block", "1.0", 0.5));
    var inputReader = new PipedReader(64 * 1024);
    var inputWriter = new PipedWriter(inputReader);
    var outputReader = new PipedReader(64 * 1024);
    var outputWriter = new PipedWriter(outputReader);
    var server = new ConfiguredWorkerServer(runtime, new PathPolicy(null, null), 0, 0, 0,
        0, 0, false);
    Thread serving = new Thread(() -> {
      try { server.serve(inputReader, outputWriter); }
      catch (Exception failure) { throw new AssertionError(failure); }
    }, "worker-run-v2-arrow-cancel-test");
    serving.setDaemon(true);
    serving.start();
    var responses = new BufferedReader(outputReader);
    String payload = PayloadCodec.encode(new String[] {
        ZinggJob.Phase.FIND_TRAINING_DATA.name(), outputPath.toString(), "TRUE", "",
        "", "", "", "", input.toString()
    });
    inputWriter.write(ProtocolCodec.encode(new WorkerMessage("arrow-run", "run_v2", payload)) + "\n");
    inputWriter.flush();
    Thread.sleep(100);
    inputWriter.write(ProtocolCodec.encode(
        new WorkerMessage("arrow-cancel", "cancel", "arrow-run")) + "\n");
    inputWriter.flush();

    var first = ProtocolCodec.decode(responses.readLine());
    var second = ProtocolCodec.decode(responses.readLine());
    var byId = new java.util.HashMap<String, WorkerMessage>();
    byId.put(first.id(), first);
    byId.put(second.id(), second);
    assertEquals("cancel_requested", byId.get("arrow-cancel").payload());
    assertEquals("error", byId.get("arrow-run").operation());
    assertTrue(byId.get("arrow-run").payload().equals("operation cancelled")
            || byId.get("arrow-run").payload().startsWith("Arrow IPC ingestion failed:"),
        "run_v2 must terminate with a cancellation/Arrow-import error, got: "
            + byId.get("arrow-run").payload());
    org.junit.jupiter.api.Assertions.assertFalse(Files.exists(outputPath),
        "cancelled run_v2 must not publish its output file");
    try (var entries = Files.list(temp)) {
      assertTrue(entries.noneMatch(entry -> entry.getFileName().toString()
              .startsWith(".cancelled-output.csv.partial-")),
          "cancelled run_v2 must not leave an atomic-output staging file");
    }
    try (var check = runtime.openJob()) {
      var rows = check.sql("SELECT count(*) AS n FROM information_schema.tables "
          + "WHERE table_name LIKE 'zd_arrow_%'").collect();
      assertEquals(0L, ((Number) rows.get(0).get("n")).longValue(),
          "cancelled Arrow import must not leave partial worker tables");
    }

    inputWriter.write(ProtocolCodec.encode(new WorkerMessage("after-arrow-cancel", "ping", "")) + "\n");
    inputWriter.flush();
    assertEquals(new WorkerMessage("after-arrow-cancel", "ok", "pong"),
        ProtocolCodec.decode(responses.readLine()));
    inputWriter.write(ProtocolCodec.encode(new WorkerMessage("stop", "shutdown", "")) + "\n");
    inputWriter.flush();
    assertEquals(new WorkerMessage("stop", "ok", "stopping"),
        ProtocolCodec.decode(responses.readLine()));
    serving.join(5_000);
    assertTrue(!serving.isAlive(), "server should finish after shutdown");
    inputWriter.close();
    serving.join(5_000);
    assertTrue(!serving.isAlive(), "server should exit after input closure");
    }
  }

  private static void writeLargeArrowInput(Path path, int rows, int batchSize) throws Exception {
    try (var allocator = new org.apache.arrow.memory.RootAllocator();
         var root = org.apache.arrow.vector.VectorSchemaRoot.create(
             new org.apache.arrow.vector.types.pojo.Schema(List.of(
                 new org.apache.arrow.vector.types.pojo.Field("value",
                     org.apache.arrow.vector.types.pojo.FieldType.nullable(
                         new org.apache.arrow.vector.types.pojo.ArrowType.Int(32, true)), null))), allocator);
         var channel = Channels.newChannel(Files.newOutputStream(path));
         var writer = new org.apache.arrow.vector.ipc.ArrowStreamWriter(root, null, channel)) {
      var vector = (org.apache.arrow.vector.IntVector) root.getVector("value");
      writer.start();
      for (int base = 0; base < rows; base += batchSize) {
        int count = Math.min(batchSize, rows - base);
        for (int row = 0; row < count; row++) vector.setSafe(row, base + row);
        root.setRowCount(count);
        writer.writeBatch();
      }
      writer.end();
    }
  }
}
