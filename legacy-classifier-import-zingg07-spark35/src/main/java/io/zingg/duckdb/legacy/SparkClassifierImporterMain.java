package io.zingg.duckdb.legacy;
import io.zingg.duckdb.model.*;
import java.nio.charset.StandardCharsets; import java.nio.file.*; import java.time.Instant; import java.util.*;
import org.apache.spark.ml.PipelineModel; import org.apache.spark.ml.PipelineStage; import org.apache.spark.ml.classification.LogisticRegressionModel; import org.apache.spark.ml.feature.PolynomialExpansion; import org.apache.spark.ml.feature.VectorAssembler; import org.apache.spark.ml.tuning.CrossValidatorModel; import org.apache.spark.sql.SparkSession;
/** Spark 3.5.5 boundary for the released classifier pipeline. */
public final class SparkClassifierImporterMain {
  private SparkClassifierImporterMain() {}
  public static void main(String[] args) throws Exception {
    Path input=Path.of(required("ZINGG_IMPORT_INPUT")), output=Path.of(required("ZINGG_IMPORT_OUTPUT"));
    new ImportLimits(Long.parseLong(required("ZINGG_IMPORT_MAX_BYTES")),Integer.parseInt(required("ZINGG_IMPORT_MAX_DEPTH")),Long.parseLong(required("ZINGG_IMPORT_MAX_REFERENCES")),Long.parseLong(required("ZINGG_IMPORT_TIMEOUT_MILLIS")));
    SparkSession spark=SparkSession.builder().appName("zingg-duckdb-legacy-classifier-import").master("local[1]").config("spark.ui.enabled","false").getOrCreate();
    try { CrossValidatorModel cv=CrossValidatorModel.load(input.toString()); PipelineModel pipeline=(PipelineModel)cv.bestModel(); VectorAssembler assembler=null; PolynomialExpansion polynomial=null; LogisticRegressionModel logistic=null;
      List<String> unsupported=new ArrayList<>();
      for(PipelineStage stage:pipeline.stages()){if(stage instanceof VectorAssembler a)assembler=a;else if(stage instanceof PolynomialExpansion p)polynomial=p;else if(stage instanceof LogisticRegressionModel l)logistic=l;else unsupported.add(stage.getClass().getName());}
      if(!unsupported.isEmpty())throw new IllegalArgumentException("unsupported classifier pipeline stages: "+String.join(",",unsupported));
      if(assembler==null||polynomial==null||logistic==null)throw new IllegalArgumentException("unsupported classifier pipeline: required stages are missing (VectorAssembler, PolynomialExpansion, LogisticRegressionModel)"); if(polynomial.getDegree()!=3)throw new IllegalArgumentException("unsupported polynomial expansion degree: "+polynomial.getDegree());
      double[] weights=logistic.coefficients().toArray(); if(Arrays.stream(weights).anyMatch(w->!Double.isFinite(w))||!Double.isFinite(logistic.intercept()))throw new IllegalArgumentException("classifier contains non-finite coefficients"); double threshold=logistic.getThreshold(); if(!Double.isFinite(threshold)||threshold<0||threshold>1)throw new IllegalArgumentException("classifier threshold is outside [0,1]"); List<String> features=new ArrayList<>(); for(int i=0;i<weights.length;i++)features.add("expanded_feature_"+i);
      String featureJson=features.stream().map(f -> "\""+f+"\"").collect(java.util.stream.Collectors.joining(",")); String weightJson=Arrays.stream(weights).mapToObj(Double::toString).collect(java.util.stream.Collectors.joining(","));
      String assemblerJson=Arrays.stream(assembler.getInputCols()).map(f -> "\""+f+"\"").collect(java.util.stream.Collectors.joining(","));
      String payload="{\"kind\":\"linear-classifier\",\"features\":["+featureJson+"],\"weights\":["+weightJson+"],\"intercept\":"+logistic.intercept()+",\"logistic\":true,\"assemblerInputCols\":["+assemblerJson+"],\"polynomialDegree\":"+polynomial.getDegree()+",\"threshold\":"+threshold+"}";
      new ModelArtifactWriter(output).write(new ModelManifest("zingg-0.7.0","0.7.0",ModelFormat.CURRENT,ModelType.CLASSIFIER.name(),features,""),payload.getBytes(StandardCharsets.UTF_8),new ImportProvenance(input.toString(),"0.7.0","zingg-duckdb-legacy-spark35-0.1",Instant.now(),""));
    } finally { spark.stop(); }
  }
  private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalArgumentException("missing importer environment: "+name);return value;}
}
