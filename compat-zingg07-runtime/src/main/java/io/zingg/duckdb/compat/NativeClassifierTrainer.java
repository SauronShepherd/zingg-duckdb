package io.zingg.duckdb.compat;

import io.zingg.duckdb.api.DuckException;
import io.zingg.duckdb.api.Frame;
import io.zingg.duckdb.api.CancellationToken;
import io.zingg.duckdb.engine.DuckCancellation;
import java.nio.file.Path;
import java.util.List;

/** Deterministic, Spark-free trainer for the native binary linear classifier. */
public final class NativeClassifierTrainer {
  public record Config(String profile, List<String> features, String labelColumn, Path artifactDirectory,
      int iterations, double learningRate, double l2, long maxRows) {
    public Config {
      if (profile == null || profile.isBlank() || labelColumn == null || labelColumn.isBlank() || artifactDirectory == null)
        throw new IllegalArgumentException("classifier profile, label and artifact directory are required");
      features = List.copyOf(features == null ? List.of() : features);
      if (features.isEmpty() || features.stream().anyMatch(f -> f == null || f.isBlank()))
        throw new IllegalArgumentException("classifier features are required");
      if (features.stream().distinct().count() != features.size())
        throw new IllegalArgumentException("classifier features must be unique");
      if (features.contains(labelColumn))
        throw new IllegalArgumentException("classifier label column cannot also be a feature");
      if (iterations < 1 || iterations > 1_000_000 || !Double.isFinite(learningRate) || learningRate <= 0
          || !Double.isFinite(l2) || l2 < 0 || maxRows < 1) throw new IllegalArgumentException("invalid classifier training parameters");
    }
    public static Config defaults(String profile, List<String> features, String labelColumn, Path output) {
      return new Config(profile, features, labelColumn, output, 200, 0.05, 1e-4, 1_000_000);
    }
  }

  public record Result(Path artifactDirectory, long rows, double[] weights, double intercept) {
    public Result { weights = weights.clone(); }
    @Override public double[] weights() { return weights.clone(); }
  }

  public Result train(Frame input, Config config) {
    if (input == null || config == null) throw new IllegalArgumentException("classifier input and config are required");
    DuckCancellation requestCancellation = DuckCancellation.current();
    CancellationToken cancellation = requestCancellation == null
        ? CancellationToken.none() : requestCancellation;
    cancellation.throwIfCancelled();
    long inputRows = input.count(cancellation);
    if (inputRows > config.maxRows())
      throw new DuckException("classifier maxRows exceeded: " + inputRows + " > " + config.maxRows());
    Frame data = input;
    List<String> columns = data.columns();
    int[] featureIndexes = indexes(columns, config.features());
    int labelIndex = columns.indexOf(config.labelColumn());
    if (labelIndex < 0) throw new DuckException("classifier label column is not present: " + config.labelColumn());
    List<io.zingg.duckdb.api.Row> rows = data.collect(cancellation);
    if (rows.isEmpty()) throw new DuckException("classifier training input is empty");
    if (rows.size() != inputRows)
      throw new DuckException("classifier input row count changed between count and collect");
    double[] weights = new double[featureIndexes.length];
    double intercept = 0d;
    boolean hasPositive = false;
    boolean hasNegative = false;
    double[] labels = new double[rows.size()];
    for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
      if ((rowIndex & 0x3ff) == 0) cancellation.throwIfCancelled();
      labels[rowIndex] = label(rows.get(rowIndex).values().get(labelIndex));
      hasPositive |= labels[rowIndex] == 1d;
      hasNegative |= labels[rowIndex] == 0d;
    }
    if (!hasPositive || !hasNegative)
      throw new DuckException("classifier training requires both positive and negative labels");
    for (int iteration = 0; iteration < config.iterations(); iteration++) {
      cancellation.throwIfCancelled();
      double[] gradient = new double[weights.length];
      double interceptGradient = 0d;
      for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
        if ((rowIndex & 0x3ff) == 0) cancellation.throwIfCancelled();
        var row = rows.get(rowIndex);
        double[] x = vector(row, featureIndexes);
        double y = labels[rowIndex];
        double prediction = sigmoid(intercept + dot(weights, x));
        double error = prediction - y;
        interceptGradient += error;
        for (int i = 0; i < gradient.length; i++) gradient[i] += error * x[i];
      }
      double scale = 1d / rows.size();
      intercept -= config.learningRate() * interceptGradient * scale;
      for (int i = 0; i < weights.length; i++) {
        gradient[i] = gradient[i] * scale + config.l2() * weights[i];
        weights[i] -= config.learningRate() * gradient[i];
      }
    }
    if (!Double.isFinite(intercept) || java.util.Arrays.stream(weights).anyMatch(w -> !Double.isFinite(w)))
      throw new DuckException("classifier training produced non-finite coefficients");
    cancellation.throwIfCancelled();
    Path artifact = new NativeClassifierArtifact().write(new NativeClassifierArtifact.Config(
        config.profile(), config.features(), weights, intercept, true, config.artifactDirectory()));
    return new Result(artifact, rows.size(), weights, intercept);
  }

  private static int[] indexes(List<String> columns, List<String> features) {
    int[] result = new int[features.size()];
    for (int i = 0; i < result.length; i++) {
      result[i] = columns.indexOf(features.get(i));
      if (result[i] < 0) throw new DuckException("classifier feature is not present: " + features.get(i));
    }
    return result;
  }
  private static double[] vector(io.zingg.duckdb.api.Row row, int[] indexes) {
    double[] x = new double[indexes.length];
    for (int i = 0; i < indexes.length; i++) {
      Object value = row.values().get(indexes[i]);
      if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())) throw new DuckException("classifier feature must be finite numeric");
      x[i] = n.doubleValue();
    }
    return x;
  }
  private static double label(Object value) {
    if (value instanceof Boolean b) return b ? 1d : 0d;
    if (value instanceof Number n && (n.doubleValue() == 0d || n.doubleValue() == 1d)) return n.doubleValue();
    throw new DuckException("classifier label must be boolean or numeric 0/1");
  }
  private static double dot(double[] a, double[] b) { double result = 0d; for (int i = 0; i < a.length; i++) result += a[i] * b[i]; return result; }
  private static double sigmoid(double value) { if (value >= 0) { double e = Math.exp(-value); return 1d / (1d + e); } double e = Math.exp(value); return e / (1d + e); }
}
