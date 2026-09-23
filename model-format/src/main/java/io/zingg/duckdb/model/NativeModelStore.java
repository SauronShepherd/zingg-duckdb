package io.zingg.duckdb.model;
import io.zingg.duckdb.api.DuckException; import java.io.*; import java.nio.file.*; import java.util.HexFormat; import java.security.*;
public final class NativeModelStore {
 private NativeModelStore(){}
 public static void write(Path root,ModelManifest manifest,byte[] payload)throws IOException{Files.createDirectories(root); Files.writeString(root.resolve("manifest.json"),toJson(manifest)); Files.write(root.resolve("model.bin"),payload);}
 public static byte[] readPayload(Path root)throws IOException{if(!Files.isRegularFile(root.resolve("manifest.json")))throw new FileNotFoundException("manifest.json");return Files.readAllBytes(root.resolve("model.bin"));}
 private static String toJson(ModelManifest m){String features=m.features().stream().map(x->"\""+esc(x)+"\"").collect(java.util.stream.Collectors.joining(",","[","]"));return "{\"profile\":\""+esc(m.profile())+"\",\"zinggVersion\":\""+esc(m.zinggVersion())+"\",\"formatVersion\":\""+esc(m.formatVersion())+"\",\"modelType\":\""+esc(m.modelType())+"\",\"features\":"+features+",\"sha256\":\""+esc(m.sha256())+"\"}";}
 private static String esc(String s){return s==null?"":s.replace("\\","\\\\").replace("\"","\\\"");}
 public static String sha256(byte[] b){try{var d=MessageDigest.getInstance("SHA-256");return HexFormat.of().formatHex(d.digest(b));}catch(Exception e){throw new DuckException("cannot hash model",e);}}
}
