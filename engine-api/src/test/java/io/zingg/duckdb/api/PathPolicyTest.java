package io.zingg.duckdb.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathPolicyTest {
  @Test
  void acceptsOnlyPathsUnderConfiguredRoot(@TempDir Path temp) throws Exception {
    var policy = new PathPolicy(temp, temp.resolve("out"));
    Path accepted = temp.resolve("input.csv");
    assertEquals(accepted.toAbsolutePath().normalize(), policy.input(accepted));
    assertThrows(DuckException.class, () -> policy.input(temp.getParent().resolve("escape.csv")));
  }

  @Test
  void rejectsRemoteUris() {
    var policy = new PathPolicy(null, null);
    assertThrows(RuntimeException.class, () -> policy.input(Path.of("https://example.test/data.csv")));
  }

  @Test
  void rejectsSymlinkComponents(@TempDir Path temp) throws Exception {
    Path target = Files.createFile(temp.resolve("real.csv"));
    Path link = temp.resolve("link.csv");
    try {
      Files.createSymbolicLink(link, target.getFileName());
    } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
      return;
    }
    var policy = new PathPolicy(temp, null);
    assertThrows(DuckException.class, () -> policy.input(link));
  }
}
