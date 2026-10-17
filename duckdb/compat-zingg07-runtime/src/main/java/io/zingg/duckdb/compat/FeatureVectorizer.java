package io.zingg.duckdb.compat;

import io.zingg.duckdb.api.DuckException;
import java.util.List;
import java.util.Map;

public final class FeatureVectorizer {
  public enum MissingPolicy { FAIL, ZERO }

  private final List<String> names;
  private final MissingPolicy policy;

  public FeatureVectorizer(List<String> names, MissingPolicy policy) {
    if (names == null || names.isEmpty()) throw new IllegalArgumentException("feature names required");
    this.names = List.copyOf(names);
    this.policy = policy == null ? MissingPolicy.FAIL : policy;
  }

  public double[] vector(Map<String, Object> values) {
    if (values == null) throw new DuckException("feature values required");
    double[] out = new double[names.size()];
    for (int index = 0; index < names.size(); index++) {
      String name = names.get(index);
      Object value = values.get(name);
      if (value == null) {
        if (policy == MissingPolicy.ZERO) continue;
        throw new DuckException("missing feature: " + name);
      }
      if (!(value instanceof Number number))
        throw new DuckException("feature is not numeric: " + name);
      double numeric = number.doubleValue();
      if (!Double.isFinite(numeric))
        throw new DuckException("non-finite feature: " + name);
      out[index] = numeric;
    }
    return out;
  }

  public List<String> names() { return names; }
}
