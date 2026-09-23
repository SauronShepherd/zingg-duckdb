package io.zingg.duckdb.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.zingg.duckdb.api.DuckException;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelFormatTest {
  @Test
  void currentFormatIsAccepted() {
    assertDoesNotThrow(() -> ModelFormat.requireSupported(ModelFormat.CURRENT));
  }

  @Test
  void unknownFormatIsRejected() {
    assertThrows(DuckException.class, () -> ModelFormat.requireSupported("zingg-99.0-native"));
  }

  @Test
  void manifestCopiesFeaturesAndRejectsUnknownTypes() {
    var features = new java.util.ArrayList<>(List.of("name", "email"));
    var manifest = new ModelManifest("zingg-0.7.0", "0.7.0", ModelFormat.CURRENT,
        "CLASSIFIER", features, "abc");
    features.add("mutated");
    org.junit.jupiter.api.Assertions.assertEquals(List.of("name", "email"), manifest.features());
    assertThrows(IllegalArgumentException.class, () -> new ModelManifest(
        "zingg-0.7.0", "0.7.0", ModelFormat.CURRENT, "UNKNOWN", List.of(), "abc"));
  }

  @Test
  void writerAndReaderRoundTripACompleteClassifierArtifact(@TempDir Path temp) throws Exception {
    byte[] payload = "{\"kind\":\"linear-classifier\",\"weights\":[1.0]}".getBytes(StandardCharsets.UTF_8);
    var manifest = new ModelManifest("zingg-0.7.0", "0.7.0", ModelFormat.CURRENT,
        "CLASSIFIER", List.of("name"), "ignored-before-stamping");
    var provenance = new ImportProvenance("fixture/model", "0.7.0", "test-importer",
        Instant.parse("2026-01-01T00:00:00Z"), "");
    Path artifact = new ModelArtifactWriter(temp.resolve("model")).write(manifest, payload, provenance);
    var loaded = ModelReader.load(artifact, 1024);
    assertEquals("CLASSIFIER", loaded.manifest().modelType());
    org.junit.jupiter.api.Assertions.assertArrayEquals(payload, loaded.payload());
    assertEquals(NativeModelStore.sha256(payload), loaded.manifest().sha256());
  }

  @Test
  void readerRejectsCorruptPayloadAndOversizedArtifact(@TempDir Path temp) throws Exception {
    byte[] payload = "{\"kind\":\"linear-classifier\"}".getBytes(StandardCharsets.UTF_8);
    var manifest = new ModelManifest("zingg-0.7.0", "0.7.0", ModelFormat.CURRENT,
        "CLASSIFIER", List.of(), "");
    Path artifact = new ModelArtifactWriter(temp.resolve("model")).write(manifest, payload,
        new ImportProvenance("fixture/model", "0.7.0", "test-importer", Instant.now(), ""));
    Files.write(artifact.resolve("model.bin"), "corrupt".getBytes(StandardCharsets.UTF_8));
    assertThrows(DuckException.class, () -> ModelReader.load(artifact, 1024));
    assertThrows(DuckException.class, () -> ModelReader.load(artifact, 1));
  }
}
