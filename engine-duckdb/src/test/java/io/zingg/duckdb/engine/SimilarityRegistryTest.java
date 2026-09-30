package io.zingg.duckdb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.zingg.duckdb.api.DuckException;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SimilarityRegistryTest {
  @Test
  void registeredSimilarityInventoryIsStable() {
    assertEquals(Set.of("exact", "jaccard", "normalized_levenshtein", "jaro", "jaro_winkler"),
        new SimilarityRegistry().names());
  }

  @Test
  void strictJaccardVectorsPreserveReleasedNormalization() {
    var registry = new SimilarityRegistry();
    assertEquals(1.0, registry.apply("jaccard", null, "value"));
    assertEquals(1.0, registry.apply("jaccard", "", "value"));
    assertEquals(1.0, registry.apply("jaccard", "Acme, Inc.", "acme inc"));
    assertEquals(1.0 / 3.0, registry.apply("jaccard", "acme north", "acme south"), 1e-12);
    assertEquals(0.0, registry.apply("jaccard", "alpha", "beta"));
  }

  @Test
  void releasedJaroWinklerDelegatesToJaro() {
    var registry = new SimilarityRegistry();
    assertEquals(registry.apply("jaro", "MARTHA", "MARHTA"),
        registry.apply("jaro_winkler", "MARTHA", "MARHTA"), 1e-12);
    assertEquals(1.0, registry.apply("jaro", "same", "same"));
    assertEquals(0.0, registry.apply("jaro", null, "same"));
  }

  @Test
  void unsupportedSimilarityFailsExplicitly() {
    assertThrows(DuckException.class, () -> new SimilarityRegistry().apply("cosine", "a", "b"));
  }
}
