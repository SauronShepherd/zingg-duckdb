package io.zingg.duckdb.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ProtocolCodecTest {
  @Test
  void roundTripPreservesEscapedFields() {
    var original = new WorkerMessage("request-1", "match.run", "{\"text\":\"line1\nline2\tvalue\\x\"}");
    assertEquals(original, ProtocolCodec.decode(ProtocolCodec.encode(original)));
  }

  @Test
  void rejectsMalformedOperationsAndMessages() {
    assertThrows(IllegalArgumentException.class, () -> ProtocolCodec.requireOperation("match/run"));
    assertThrows(IllegalArgumentException.class, () -> ProtocolCodec.decode("only-two-fields\tmatch.run"));
    assertThrows(IllegalArgumentException.class, () -> ProtocolCodec.decode("id\tbad/op\tpayload"));
  }

  @Test
  void enforcesLineSizeLimit() {
    String oversized = "id\tmatch\t" + "x".repeat(ProtocolCodec.MAX_LINE_CHARS);
    assertThrows(IllegalArgumentException.class, () -> ProtocolCodec.decode(oversized));
  }
}
