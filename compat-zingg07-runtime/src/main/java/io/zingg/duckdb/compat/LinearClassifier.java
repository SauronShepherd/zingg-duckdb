package io.zingg.duckdb.compat;
import io.zingg.duckdb.api.DuckException; import io.zingg.duckdb.model.ModelScorer; import java.util.*;
public final class LinearClassifier implements ModelScorer {
 private final List<String> features; private final List<String> baseFeatures; private final int polynomialDegree; private final double[] weights; private final double intercept; private final boolean logistic; private final double threshold;
 public LinearClassifier(List<String> features,double[] weights,double intercept,boolean logistic){this(features,weights,intercept,logistic,0.5d);}
 public LinearClassifier(List<String> features,double[] weights,double intercept,boolean logistic,double threshold){this(features,List.of(),0,weights,intercept,logistic,threshold);}
 public LinearClassifier(List<String> features,List<String> baseFeatures,int polynomialDegree,double[] weights,double intercept,boolean logistic,double threshold){if(features==null||weights==null||features.size()!=weights.length||features.isEmpty())throw new IllegalArgumentException("feature/weight dimensions differ");if(java.util.Arrays.stream(weights).anyMatch(w->!Double.isFinite(w))||!Double.isFinite(intercept)||!Double.isFinite(threshold)||threshold<0||threshold>1)throw new IllegalArgumentException("invalid classifier coefficients or threshold");if(polynomialDegree<0||polynomialDegree>8)throw new IllegalArgumentException("invalid polynomial degree");this.features=List.copyOf(features);this.baseFeatures=List.copyOf(baseFeatures==null?List.of():baseFeatures);this.polynomialDegree=polynomialDegree;this.weights=weights.clone();this.intercept=intercept;this.logistic=logistic;this.threshold=threshold;}
 public double score(Map<String,Object> values){if(values==null)throw new DuckException("feature values required");double[] vector;if(!baseFeatures.isEmpty()){double[] base=new FeatureVectorizer(baseFeatures,FeatureVectorizer.MissingPolicy.FAIL).vector(values);vector=PolynomialFeatures.expand(base,polynomialDegree);}else vector=new FeatureVectorizer(features,FeatureVectorizer.MissingPolicy.FAIL).vector(values);return scoreVector(vector);}
 public double scoreVector(double[] vector){if(vector==null||vector.length!=weights.length)throw new DuckException("feature vector dimensions differ");double z=intercept;for(int i=0;i<weights.length;i++)z+=weights[i]*vector[i];return logistic?1d/(1d+Math.exp(-z)):z;}
 public List<String> features(){return features;}
 public double[] weights(){return weights.clone();}
 public double intercept(){return intercept;}
 public boolean logistic(){return logistic;}
 public double threshold(){return threshold;}
 public List<String> baseFeatures(){return baseFeatures;}
 public int polynomialDegree(){return polynomialDegree;}
}
