package io.zingg.duckdb.compat;
import io.zingg.duckdb.api.DuckException;
import io.zingg.duckdb.model.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/** Writes the neutral linear-classifier payload consumed by ModelScorerRegistry. */
public final class NativeClassifierArtifact {
  public record Config(String profile,List<String> features,double[] weights,double intercept,boolean logistic,Path output) {
    public Config {
      if(profile==null||profile.isBlank()||output==null) throw new IllegalArgumentException("profile and output are required");
      features=List.copyOf(features==null?List.of():features); weights=weights==null?new double[0]:weights.clone();
      if(features.isEmpty()||features.size()!=weights.length) throw new IllegalArgumentException("classifier feature/weight dimensions differ");
      if(features.stream().anyMatch(f->f==null||f.isBlank())) throw new IllegalArgumentException("classifier feature is blank");
      if(java.util.Arrays.stream(weights).anyMatch(w->!Double.isFinite(w))||!Double.isFinite(intercept)) throw new IllegalArgumentException("classifier coefficients must be finite");
    }
  }
  public Path write(Config config) {
    if(config==null) throw new IllegalArgumentException("classifier config is required");
    String features=config.features().stream().map(f->"\""+esc(f)+"\"").collect(Collectors.joining(","));
    String weights=java.util.Arrays.stream(config.weights()).mapToObj(Double::toString).collect(Collectors.joining(","));
    String payload="{\"kind\":\"linear-classifier\",\"features\":["+features+"],\"weights\":["+weights+"],\"intercept\":"+config.intercept()+",\"logistic\":"+config.logistic()+"}";
    try {
      var manifest=new ModelManifest(config.profile(),"0.7.0","zingg-0.1-native",ModelType.CLASSIFIER.name(),config.features(),"");
      var provenance=new ImportProvenance(config.output().toAbsolutePath().normalize().toString(),"0.7.0","zingg-duckdb-native-0.1",Instant.now(),"");
      new ModelArtifactWriter(config.output().toAbsolutePath().normalize()).write(manifest,payload.getBytes(StandardCharsets.UTF_8),provenance);
      return config.output().toAbsolutePath().normalize();
    } catch(Exception e) { if(e instanceof DuckException d) throw d; throw new DuckException("classifier artifact write failed",e); }
  }
  private static String esc(String s){StringBuilder out=new StringBuilder(s.length()+8);for(int i=0;i<s.length();i++){char c=s.charAt(i);switch(c){case '\\'->out.append("\\\\");case '"'->out.append("\\\"");case '\b'->out.append("\\b");case '\f'->out.append("\\f");case '\n'->out.append("\\n");case '\r'->out.append("\\r");case '\t'->out.append("\\t");default->{if(c<0x20)out.append(String.format("\\u%04x",(int)c));else out.append(c);}}}return out.toString();}
}
