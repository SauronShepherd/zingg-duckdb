package io.zingg.duckdb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.zingg.duckdb.api.DuckException;
import org.junit.jupiter.api.Test;

class HashRegistryTest {
  @Test
  void preservesJavaHashAndNullSemantics() {
    var registry = new HashRegistry();
    assertEquals("Aa".hashCode(), registry.apply("java_hash", "Aa"));
    assertEquals(0, registry.apply("java_hash", null));
    assertEquals("hello", registry.apply("lower", "HeLLo"));
    assertNull(registry.apply("trim", null));
  }

  @Test
  void hashesAndRoundsUseDocumentedRepresentations() {
    var registry = new HashRegistry();
    assertEquals("5d41402abc4b2a76b9719d911017c592", registry.apply("md5", "hello"));
    assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", registry.apply("sha256", "hello"));
    assertEquals(2L, registry.apply("java_round", "1.5"));
    assertThrows(DuckException.class, () -> registry.apply("java_round", "not-a-number"));
  }

  @Test
  void rejectsUnknownAndDuplicateFunctions() {
    var registry = new HashRegistry();
    assertThrows(DuckException.class, () -> registry.apply("missing", "x"));
    assertThrows(DuckException.class, () -> registry.register("lower", value -> value));
  }

  @Test
  void zinggJaccardNormalizesNullEmptyCaseAndPunctuation() {
    assertEquals(1.0, SimilarityFunctions.jaccard(null, "value"));
    assertEquals(1.0, SimilarityFunctions.jaccard("", "value"));
    assertEquals(1.0, SimilarityFunctions.jaccard("Slashes/And:Colons.,", "slashes and colons"));
    assertEquals(0.0, SimilarityFunctions.jaccard("alpha", "beta"));
  }
}
