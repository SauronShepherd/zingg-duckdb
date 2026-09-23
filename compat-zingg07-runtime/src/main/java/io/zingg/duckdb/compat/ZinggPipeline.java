package io.zingg.duckdb.compat;

import io.zingg.duckdb.api.*; import java.io.IOException; import java.nio.file.*;

/** Executable phase pipeline for the first native DuckDB milestone. */
public final class ZinggPipeline {
  private final CompatibilityRuntime runtime;
  public ZinggPipeline(CompatibilityRuntime runtime){this.runtime=runtime;}
  public PipelineResult execute(PipelineConfig config){
    try(var job=new ZinggJob(runtime)){
      Frame input=job.read(config.inputs(),config.phase(),config.pathPolicy()); long in=input.count(); Frame output=runtime.phases().execute(config.phase(),job,input);
      if(config.phase()==ZinggJob.Phase.MATCH || config.phase()==ZinggJob.Phase.LINK){
        if(config.matcher()==null) output=job.match(output,config.predicate());
        else {var matcher=new Matcher(); var candidates=matcher.candidates(output,config.matcher());
          if(config.classifierModel()==null) output=matcher.score(candidates,config.matcher());
          else {try{Path modelPath=config.pathPolicy()==null?config.classifierModel():config.pathPolicy().input(config.classifierModel());var loaded=io.zingg.duckdb.model.ModelReader.load(modelPath,config.maxModelBytes());var scorer=(LinearClassifier)new ModelScorerRegistry().create(loaded);output=matcher.scoreLinear(candidates,config.matcher(),scorer);}catch(IOException e){throw new DuckException("classifier model load failed",e);}}
        }
      }
      Path outputPath=config.pathPolicy()==null?config.output():config.pathPolicy().output(config.output()); write(output,outputPath,config.maxOutputBytes()); return new PipelineResult(in,output.count(),config.phase().name(),outputPath.toString());
    }
  }
  private static void write(Frame frame,Path output,long maxBytes){
    try {
      Path target=output.toAbsolutePath().normalize(); Path parent=target.getParent();
      if(parent==null)throw new DuckException("output must have a parent directory: "+target);
      java.nio.file.Files.createDirectories(parent);
      String name=target.getFileName().toString(); String suffix="";
      int dot=name.lastIndexOf('.'); if(dot>=0) suffix=name.substring(dot);
      Path temporary=java.nio.file.Files.createTempFile(parent,"."+name+".partial-",suffix);
      try {
        var format=io.zingg.duckdb.api.OutputFormat.fromPath(target);
        if(format==io.zingg.duckdb.api.OutputFormat.PARQUET)frame.writeParquet(temporary);
        else if(format==io.zingg.duckdb.api.OutputFormat.JSON)frame.writeJson(temporary);
        else if(format==io.zingg.duckdb.api.OutputFormat.ARROW)frame.writeArrow(temporary);
        else frame.writeCsv(temporary,true);
        if(maxBytes>0&&java.nio.file.Files.size(temporary)>maxBytes)throw new DuckException("output exceeds configured byte limit: "+maxBytes);
        try { java.nio.file.Files.move(temporary,target,java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
        catch(java.nio.file.AtomicMoveNotSupportedException e) { java.nio.file.Files.move(temporary,target,java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
      } finally { java.nio.file.Files.deleteIfExists(temporary); }
    } catch(java.io.IOException e) { throw new DuckException("atomic output write failed",e); }
  }
}
