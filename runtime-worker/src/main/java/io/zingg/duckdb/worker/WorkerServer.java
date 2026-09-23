package io.zingg.duckdb.worker;

import io.zingg.duckdb.compat.*;
import io.zingg.duckdb.protocol.*;
import java.io.*;
import java.nio.file.Path;
import java.util.List;

final class WorkerServer implements AutoCloseable {
 private final CompatibilityRuntime runtime;
 WorkerServer(String url){runtime=new CompatibilityRuntime(url,null);}
 void serve(Reader input,Writer output)throws IOException {var in=new BufferedReader(input);var out=new PrintWriter(output,true);String line;while((line=in.readLine())!=null){try{var m=ProtocolCodec.decode(line);ProtocolCodec.requireOperation(m.operation());String result;String status="ok";switch(m.operation()){case "ping"->result="pong";case "count"->{try(var job=runtime.openJob()){result=Long.toString(job.sql(m.payload()).count());}}case "run"->{String[] p=m.payload().split("\\|",-1);if(p.length!=4)throw new IllegalArgumentException("run payload: phase|input|output|predicate");var phase=ZinggJob.Phase.valueOf(p[0]);var config=new PipelineConfig(phase,List.of(Path.of(p[1])),Path.of(p[2]),p[3]);var r=new ZinggPipeline(runtime).execute(config);result=Long.toString(r.outputRows());}case "shutdown"->{result="stopping";out.println(ProtocolCodec.encode(new WorkerMessage(m.id(),status,result)));return;}default->{status="error";result="unsupported operation: "+m.operation();}}out.println(ProtocolCodec.encode(new WorkerMessage(m.id(),status,result)));}catch(Exception e){out.println(ProtocolCodec.encode(new WorkerMessage("","error",e.getMessage()==null?e.getClass().getSimpleName():e.getMessage())));}}}
 public void close(){runtime.close();}
}
