package io.zingg.duckdb.model;
import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/** Publishes complete native model artifacts through a staged directory swap. */
public final class ModelArtifactWriter {
 private final Path root;
 public ModelArtifactWriter(Path root){this.root=Objects.requireNonNull(root).toAbsolutePath().normalize();}
 public Path write(ModelManifest manifest,byte[] payload,ImportProvenance provenance)throws IOException{
  Objects.requireNonNull(manifest);Objects.requireNonNull(payload);Objects.requireNonNull(provenance);ModelFormat.requireSupported(manifest.formatVersion());
  Files.createDirectories(root.getParent());
  if(Files.exists(root)&&(!Files.isDirectory(root)||Files.isSymbolicLink(root)))throw new IOException("model artifact root must be a real directory");
  String checksum=NativeModelStore.sha256(payload);ModelManifest stamped=new ModelManifest(manifest.profile(),manifest.zinggVersion(),manifest.formatVersion(),manifest.modelType(),manifest.features(),checksum);
  Path stage=Files.createTempDirectory(root.getParent(),".model-artifact-");
  try{NativeModelStore.write(stage,stamped,payload);String provenanceJson="{\"sourcePath\":\""+escape(provenance.sourcePath())+"\",\"sourceVersion\":\""+escape(provenance.sourceVersion())+"\",\"importerVersion\":\""+escape(provenance.importerVersion())+"\",\"importedAt\":\""+provenance.importedAt()+"\",\"sha256\":\""+checksum+"\"}";Files.writeString(stage.resolve("provenance.json"),provenanceJson);publish(stage);return root;}finally{deleteTree(stage);}
 }
 private void publish(Path stage)throws IOException{Path backup=root.resolveSibling("."+root.getFileName()+".previous-"+UUID.randomUUID());boolean movedOld=false;try{if(Files.exists(root)){move(root,backup);movedOld=true;}move(stage,root);if(movedOld)deleteTree(backup);}catch(IOException failure){if(Files.exists(root))deleteTree(root);if(movedOld)try{move(backup,root);}catch(IOException rollback){failure.addSuppressed(rollback);}throw failure;}}
 private static void move(Path from,Path to)throws IOException{try{Files.move(from,to,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(from,to);}}
 private static void deleteTree(Path path)throws IOException{if(!Files.exists(path))return;try(var paths=Files.walk(path)){for(Path item:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(item);}}
 private static String escape(String value){return value.replace("\\","\\\\").replace("\"","\\\"");}
}
