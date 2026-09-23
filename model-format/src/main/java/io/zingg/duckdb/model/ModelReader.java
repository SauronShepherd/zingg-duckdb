package io.zingg.duckdb.model;
import io.zingg.duckdb.api.DuckException;
import java.io.IOException; import java.io.UncheckedIOException; import java.nio.file.*; import java.util.*; import java.util.regex.*;
public final class ModelReader {
  private static final Set<String> PROFILES=Set.of("zingg-0.7.0");
  private ModelReader(){}
  public static LoadedModel load(Path root,long maxBytes) throws IOException {
    Path dir=root.toAbsolutePath().normalize();
    if(!Files.isDirectory(dir))throw new DuckException("model artifact directory is required: "+dir);
    try(var paths=Files.walk(dir)){for(var it=paths.iterator();it.hasNext();)if(Files.isSymbolicLink(it.next()))throw new DuckException("symbolic links are not allowed in model artifacts");}catch(UncheckedIOException e){throw e.getCause();}
    if(maxBytes>0){try(var paths=Files.walk(dir)){long total=0;for(var it=paths.filter(Files::isRegularFile).iterator();it.hasNext();){long size=Files.size(it.next());if(size>maxBytes-total)throw new DuckException("model artifact exceeds configured size limit");total+=size;}}catch(UncheckedIOException e){throw e.getCause();}}
    byte[] bytes=NativeModelStore.readPayload(dir);
    if(maxBytes>0&&bytes.length>maxBytes)throw new DuckException("model exceeds configured size limit");
    Path manifest=dir.resolve("manifest.json"); String json=Files.readString(manifest);
    String profile=field(json,"profile"),type=field(json,"modelType"),hash=field(json,"sha256"),format=field(json,"formatVersion"),version=field(json,"zinggVersion");
    if(!PROFILES.contains(profile)&&!"duckdb-native-0.1".equals(profile))throw new DuckException("unsupported model profile: "+profile);
    if(!Set.of("BLOCKING_TREE","BACKEND_BLOCKING_HISTOGRAM","CLASSIFIER").contains(type))throw new DuckException("unsupported model type: "+type);
    ModelFormat.requireSupported(format);
    if(!"0.7.0".equals(version)&&!"0.1.0".equals(version))throw new DuckException("unsupported model version: "+version);
    if(hash.isBlank()||!hash.equalsIgnoreCase(NativeModelStore.sha256(bytes)))throw new DuckException("model checksum mismatch");
    Path provenance=dir.resolve("provenance.json");if(!Files.isRegularFile(provenance))throw new DuckException("model provenance is missing");String provenanceJson=Files.readString(provenance);String provenanceHash=field(provenanceJson,"sha256");if(!hash.equalsIgnoreCase(provenanceHash))throw new DuckException("provenance checksum mismatch");if(field(provenanceJson,"sourcePath").isBlank()||field(provenanceJson,"importerVersion").isBlank())throw new DuckException("model provenance is incomplete");
    if (format.equals("zingg-0.1-native")) validateNativePayload(type, bytes);
    return new LoadedModel(new ModelManifest(profile,version,format,type,features(json),hash),bytes);
  }
  private static void validateNativePayload(String type, byte[] bytes) {
    String payload = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    if (payload.indexOf('\ufffd') >= 0) throw new DuckException("native model payload is not valid UTF-8");
    String expected = ModelType.CLASSIFIER.name().equals(type) ? "\"kind\":\"linear-classifier\"" : ModelType.BACKEND_BLOCKING_HISTOGRAM.name().equals(type) ? "\"kind\":\"duckdb-blocking-histogram\"" : "\"kind\":\"native-blocking-tree\"";
    if (!payload.contains(expected)) throw new DuckException("native model payload does not match manifest type: " + type);
  }
  private static String field(String json,String name){Matcher m=Pattern.compile("\\\""+Pattern.quote(name)+"\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"").matcher(json);if(!m.find())return "";return m.group(1).replace("\\\\\"","\"").replace("\\\\\\\\","\\\\");}
  private static List<String> features(String json){Matcher m=Pattern.compile("\\\"features\\\"\\s*:\\s*\\[([^]]*)\\]").matcher(json);if(!m.find()||m.group(1).isBlank())return List.of();List<String> out=new ArrayList<>();Matcher item=Pattern.compile("\\\"((?:\\\\.|[^\\\"])*)\\\"").matcher(m.group(1));while(item.find())out.add(item.group(1).replace("\\\\\"","\"").replace("\\\\\\\\","\\\\"));return out;}
  public record LoadedModel(ModelManifest manifest,byte[] payload){public LoadedModel{payload=payload.clone();}}
}
