package io.zingg.duckdb.engine;

import java.io.IOException;
import java.nio.file.*;

/** Best-effort independent process and spill-directory measurements. */
final class ProcessResourceSnapshot {
  private ProcessResourceSnapshot() {}

  static long residentBytes() {
    Path status = Path.of("/proc/self/status");
    if (!Files.isRegularFile(status)) return -1;
    try {
      for (String line : Files.readAllLines(status)) {
        if (line.startsWith("VmRSS:")) {
          String[] parts = line.trim().split("\\s+");
          long kib = Long.parseLong(parts[1]);
          return kib * 1024L;
        }
      }
    } catch (IOException | RuntimeException ignored) { }
    return -1;
  }

  static long directoryBytes(String value) {
    if (value == null || value.isBlank() || ":memory:".equals(value)) return 0;
    Path directory;
    try { directory = Path.of(value).toAbsolutePath().normalize(); }
    catch (RuntimeException e) { return -1; }
    if (!Files.isDirectory(directory)) return 0;
    long total = 0;
    try (var paths = Files.walk(directory)) {
      for (Path path : paths.filter(Files::isRegularFile).toList()) {
        try { total = Math.addExact(total, Files.size(path)); }
        catch (IOException | ArithmeticException e) { return -1; }
      }
      return total;
    } catch (IOException e) { return -1; }
  }
}
