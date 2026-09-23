package io.zingg.duckdb.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class CompatibilityFunctionsTest {
  @Test
  void blockingKeysNormalizeAndUseSignedJavaHash() {
    assertEquals("Mixed", new BlockingKey(BlockingKey.Mode.RAW).apply("Mixed"));
    assertEquals("mixed", new BlockingKey(BlockingKey.Mode.TRIM_LOWER).apply("  Mixed "));
    assertEquals(Integer.toString("Aa".hashCode()), new BlockingKey(BlockingKey.Mode.JAVA_HASH).apply("Aa"));
    assertNull(new BlockingKey(BlockingKey.Mode.RAW).apply(null));
  }

  @Test
  void polynomialFeaturesUseRepeatedIndexOrdering() {
    assertArrayEquals(new double[] {2, 3, 4, 6, 9}, PolynomialFeatures.expand(new double[] {2, 3}, 2));
    assertEquals(List.of("(COALESCE(\"a\", 0))", "(COALESCE(\"b\", 0))",
        "(COALESCE(\"a\", 0) * COALESCE(\"a\", 0))",
        "(COALESCE(\"a\", 0) * COALESCE(\"b\", 0))",
        "(COALESCE(\"b\", 0) * COALESCE(\"b\", 0))"),
        PolynomialFeatures.sqlTerms(List.of("a", "b"), 2));
    assertThrows(IllegalArgumentException.class, () -> PolynomialFeatures.expand(new double[0], 2));
  }
}
