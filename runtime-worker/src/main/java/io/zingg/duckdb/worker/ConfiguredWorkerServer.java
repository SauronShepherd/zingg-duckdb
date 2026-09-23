package io.zingg.duckdb.worker;

import io.zingg.duckdb.api.PathPolicy;
import io.zingg.duckdb.compat.*;
import io.zingg.duckdb.engine.SqlSafety;
import io.zingg.duckdb.protocol.*;
import java.io.*;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Configured line-protocol server; diagnostics always retain the request id. */
final class ConfiguredWorkerServer implements AutoCloseable {
  private final CompatibilityRuntime runtime;
  private final PathPolicy paths;
  private final long maxModelBytes; private final long maxOutputBytes;
  private final boolean unsafeDebugSql;
  ConfiguredWorkerServer(CompatibilityRuntime runtime, PathPolicy paths, long maxModelBytes, long maxOutputBytes, boolean unsafeDebugSql) { this.runtime=runtime; this.paths=paths; this.maxModelBytes=maxModelBytes; this.maxOutputBytes=maxOutputBytes; this.unsafeDebugSql=unsafeDebugSql; }

  void serve(Reader input, Writer output) throws IOException {
    var in=new BufferedReader(input); var out=new PrintWriter(output,true); String line;
    while((line=in.readLine())!=null) {
      String requestId="";
      try {
        if(line.length()>ProtocolCodec.MAX_LINE_CHARS) throw new IllegalArgumentException("worker message exceeds size limit");
        var message=ProtocolCodec.decode(line); requestId=message.id();
        String result;
        switch(message.operation()) {
          case "ping" -> result="pong";
          case "status" -> result=status();
          case "count" -> { requireUnsafeDebugSql(); try(var job=runtime.openJob()) { result=Long.toString(job.sql(SqlSafety.readOnlyQuery(message.payload())).count()); } }
          case "explain" -> { requireUnsafeDebugSql(); try(var job=runtime.openJob()) { result=job.sql(SqlSafety.readOnlyQuery(message.payload())).explain(); } }
          case "run" -> result=run(message.payload());
          case "train" -> result=train(message.payload());
          case "shutdown" -> { out.println(ProtocolCodec.encode(new WorkerMessage(requestId,"ok","stopping"))); return; }
          default -> throw new IllegalArgumentException("operation unavailable in configured server: "+message.operation());
        }
        out.println(ProtocolCodec.encode(new WorkerMessage(requestId,"ok",result)));
      } catch(Exception e) {
        String diagnostic=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
        out.println(ProtocolCodec.encode(new WorkerMessage(requestId,"error",diagnostic)));
      }
    }
  }

  private String status() {
    var d=runtime.diagnostics();
    var policy=runtime.connectorPolicy();
    return "memory_limit="+d.memoryLimit()+";max_temp_directory_size="+d.maxTempDirectorySize()+";temp_directory="+d.tempDirectory()+";threads="+d.threads()+";heap_used_bytes="+d.heapUsedBytes()+";heap_max_bytes="+d.heapMaxBytes()+";process_resident_bytes="+d.processResidentBytes()+";temp_directory_used_bytes="+d.tempDirectoryUsedBytes()+";offline_mode="+policy.offlineMode()+";unsafe_debug_sql="+unsafeDebugSql+";allowed_extensions="+String.join(",",policy.allowedExtensions());
  }

  private void requireUnsafeDebugSql(){if(!unsafeDebugSql)throw new IllegalArgumentException("count/explain SQL is disabled; restart with --unsafe-debug-sql for diagnostics only");}

  private String run(String payload) {
    String[] p=PayloadCodec.decode(payload, -1);
    if(p.length<5) throw new IllegalArgumentException("run payload requires phase, output, predicate, model, and input fields");
    var inputs=Arrays.stream(p,4,p.length).filter(x->!x.isBlank()).map(Path::of).toList();
    Path model=!p[3].isBlank()?Path.of(p[3]):null;
    var config=new PipelineConfig(ZinggJob.Phase.valueOf(p[0]),inputs,Path.of(p[1]),p[2],paths,null,model,maxModelBytes,maxOutputBytes);
    return Long.toString(new ZinggPipeline(runtime).execute(config).outputRows());
  }

  private String train(String payload) {
    String[] p=PayloadCodec.decode(payload, -1);
    if(p.length<6 && p.length!=10) throw new IllegalArgumentException("train payload requires 5 or 9 control fields plus input fields");
    boolean classifier=p.length>=10;
    int control=classifier?9:5;
    if(p.length<=control) throw new IllegalArgumentException("train payload requires at least one input");
    var inputs=Arrays.stream(p,control,p.length).filter(x->!x.isBlank()).map(Path::of).toList();
    try(var job=new ZinggJob(runtime)) {
      var frame=job.read(inputs,ZinggJob.Phase.TRAIN,paths);
      if (classifier) {
        var features=Arrays.stream(p[3].split(",",-1)).filter(x->!x.isBlank()).toList();
        var config=new NativeClassifierTrainer.Config(p[8],features,p[2],paths.output(Path.of(p[1])),Integer.parseInt(p[5]),Double.parseDouble(p[6]),Double.parseDouble(p[7]),Long.parseLong(p[4]));
        return new NativeClassifierTrainer().train(frame,config).artifactDirectory().toString();
      }
      var config=new NativeTrainingConfig("zingg-0.7.0",p[2],p[3],List.of(),paths.output(Path.of(p[1])),Long.parseLong(p[4]));
      return job.trainNative(frame,config).artifactDirectory().toString();
    }
  }
  public void close() {}
}
