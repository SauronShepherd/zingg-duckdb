package io.zingg.duckdb.api;
import java.nio.file.*; import java.util.Objects;
public final class PathPolicy {
 private final Path inputRoot; private final Path outputRoot;
 public PathPolicy(Path inputRoot,Path outputRoot){this.inputRoot=normalize(inputRoot);this.outputRoot=normalize(outputRoot);}
 public Path input(Path path){return checked(path,inputRoot,"input");}
 public Path output(Path path){return checked(path,outputRoot,"output");}
 private static Path checked(Path path,Path root,String kind){if(path==null)throw new DuckException(kind+" path is required");String raw=path.toString();if(!isWindowsDrivePath(raw)&&raw.matches("(?i)^[a-z][a-z0-9+.-]*://.*"))throw new DuckException(kind+" remote URI schemes are not allowed: "+raw);Path p=normalize(path);if(root!=null&&!p.startsWith(root))throw new DuckException(kind+" path escapes configured root: "+p);if(hasSymbolicLinkComponent(p))throw new DuckException(kind+" symbolic links are not allowed: "+p);return p;}
 private static boolean hasSymbolicLinkComponent(Path path){for(Path current=path;current!=null;current=current.getParent())if(Files.exists(current,LinkOption.NOFOLLOW_LINKS)&&Files.isSymbolicLink(current))return true;return false;}
 private static boolean isWindowsDrivePath(String raw){return raw.length()>=3&&Character.isLetter(raw.charAt(0))&&raw.charAt(1)==':'&&(raw.charAt(2)=='\\'||raw.charAt(2)=='/');}
 private static Path normalize(Path p){return p==null?null:p.toAbsolutePath().normalize();}
}
