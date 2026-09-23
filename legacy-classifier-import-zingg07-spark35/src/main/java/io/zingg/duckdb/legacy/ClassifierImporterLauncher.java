package io.zingg.duckdb.legacy;
import io.zingg.duckdb.api.DuckException;
import io.zingg.duckdb.model.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Isolated launcher for the optional Spark 3.5 classifier importer. */
public final class ClassifierImporterLauncher {
  private final ImportLimits limits;
  public ClassifierImporterLauncher(ImportLimits limits){this.limits=limits==null?ImportLimits.defaults():limits;}
  public Path launch(LegacyImporterSpec spec,Path input,Path output)throws Exception{
    if(!"zingg-classifier-spark35".equals(spec.name()))throw new DuckException("unexpected classifier importer: "+spec.name());
    if(Files.isSymbolicLink(input))throw new DuckException("classifier input directory must not be a symbolic link");
    if(!Files.isDirectory(input))throw new DuckException("classifier input directory missing");
    var active=spec.limits();Path in=input.toAbsolutePath().normalize(),out=output.toAbsolutePath().normalize(),launcher=spec.launcher().toAbsolutePath().normalize();ImportPreflight.validate(in,active);if(Files.isSymbolicLink(launcher)||!Files.isRegularFile(launcher))throw new DuckException("classifier importer launcher is not a regular file: "+launcher);if(in.equals(out)||out.startsWith(in.resolve(".")))throw new DuckException("classifier output must be outside input directory");Files.createDirectories(out);
    Path work=Files.createTempDirectory("zingg-classifier-import-");
    try {
      var pb=new ProcessBuilder(List.of(launcher.toString())).directory(work.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).redirectOutput(ProcessBuilder.Redirect.INHERIT);
      var env=pb.environment();env.put("ZINGG_IMPORT_INPUT",in.toString());env.put("ZINGG_IMPORT_OUTPUT",out.toString());env.put("ZINGG_IMPORT_MAX_BYTES",Long.toString(active.maxBytes()));env.put("ZINGG_IMPORT_MAX_DEPTH",Integer.toString(active.maxDepth()));env.put("ZINGG_IMPORT_MAX_REFERENCES",Long.toString(active.maxReferences()));env.put("ZINGG_IMPORT_TIMEOUT_MILLIS",Long.toString(active.timeoutMillis()));env.put("ZINGG_IMPORT_ALLOWED_CLASSES",String.join("\n",spec.allowedClasses()));env.put("ZINGG_IMPORT_NETWORK","disabled");
      Process process=pb.start();if(!process.waitFor(active.timeoutMillis(),TimeUnit.MILLISECONDS)){destroyTree(process);throw new DuckException("classifier importer timed out");}if(process.exitValue()!=0)throw new DuckException("classifier importer exited with code "+process.exitValue());new ClassifierImporter(active.maxBytes()).importNative(out);return out;
    } finally {try(var paths=Files.walk(work)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});}}
  }
  private static void destroyTree(Process process){process.descendants().forEach(child->{try{child.destroyForcibly();}catch(RuntimeException ignored){}});try{process.destroyForcibly();}catch(RuntimeException ignored){}try{process.waitFor(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
