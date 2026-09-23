package io.zingg.duckdb.api;

import java.util.Locale;
import java.util.Set;

/** Explicit gate for DuckDB extensions and network-backed connectors. */
public record ConnectorPolicy(boolean offlineMode, Set<String> allowedExtensions) {
  public ConnectorPolicy {
    allowedExtensions = Set.copyOf(allowedExtensions == null ? Set.of() : allowedExtensions);
    if (allowedExtensions.stream().anyMatch(x -> x == null || x.isBlank() || !x.matches("[a-z0-9_]+")))
      throw new IllegalArgumentException("invalid extension name");
  }

  public static ConnectorPolicy offline() { return new ConnectorPolicy(true, Set.of()); }

  public void requireAllowed(String extension) {
    if (extension == null || extension.isBlank()) throw new IllegalArgumentException("extension is required");
    String normalized = extension.toLowerCase(Locale.ROOT);
    if (offlineMode || !allowedExtensions.contains(normalized))
      throw new DuckException("extension is not enabled by the runtime policy: " + normalized);
  }
}
