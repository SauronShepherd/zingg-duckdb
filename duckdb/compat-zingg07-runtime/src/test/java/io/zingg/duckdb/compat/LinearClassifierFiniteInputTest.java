package io.zingg.duckdb.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.zingg.duckdb.api.DuckException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LinearClassifierFiniteInputTest {
  @Test
  void vectorizerRejectsNonFiniteNumbersWithFeatureContext() {
    var vectorizer = new FeatureVectorizer(List.of("nan", "positive", "negative"),
        FeatureVectorizer.MissingPolicy.FAIL);

    DuckException failure = assertThrows(DuckException.class,
        () -> vectorizer.vector(Map.of("nan", Double.NaN, "positive", 1d, "negative", -1d)));
    assertEquals("non-finite feature: nan", failure.getMessage());
  }

  @Test
  void vectorizerRejectsBigDecimalThatOverflowsDouble() {
    var vectorizer = new FeatureVectorizer(List.of("amount"), FeatureVectorizer.MissingPolicy.FAIL);

    DuckException failure = assertThrows(DuckException.class,
        () -> vectorizer.vector(Map.of("amount", new BigDecimal("1e10000"))));
    assertEquals("non-finite feature: amount", failure.getMessage());
  }

  @Test
  void publicScoreVectorRejectsNonFiniteValuesEvenWhenVectorizerIsBypassed() {
    var classifier = new LinearClassifier(List.of("feature"), new double[] {1d}, 0d, true);

    DuckException nan = assertThrows(DuckException.class,
        () -> classifier.scoreVector(new double[] {Double.NaN}));
    assertEquals("non-finite feature vector value at index: 0", nan.getMessage());

    DuckException infinity = assertThrows(DuckException.class,
        () -> classifier.scoreVector(new double[] {Double.POSITIVE_INFINITY}));
    assertEquals("non-finite feature vector value at index: 0", infinity.getMessage());
  }

  @Test
  void polynomialExpansionOverflowIsRejectedBeforeLogisticConversion() {
    var classifier = new LinearClassifier(List.of("x", "xSquared"), List.of("x"), 2,
        new double[] {0d, 1d}, 0d, true, 0.5d);

    DuckException failure = assertThrows(DuckException.class,
        () -> classifier.score(Map.of("x", 1e308)));
    assertEquals("non-finite feature vector value at index: 1", failure.getMessage());
  }

  @Test
  void finiteScoresRemainUnchanged() {
    var classifier = new LinearClassifier(List.of("feature"), new double[] {1d}, 0d, true);

    assertEquals(1d / (1d + Math.exp(-2d)), classifier.score(Map.of("feature", 2d)), 1e-12);
  }
}
