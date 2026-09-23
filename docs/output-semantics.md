# Output semantics

Graph output adds zero-scored implied pairs before entity min/max aggregation. Link output retains the released asymmetry: left-side projection is deduplicated while the right-side relation remains multiplicity-preserving. These operations are intentionally separate from connected-component discovery so that output compatibility does not alter graph topology.

File egress is selected by extension: CSV (`.csv`/default), Parquet (`.parquet`), JSON (`.json`/`.jsonl`), and Arrow IPC (`.arrow`/`.feather`). All writers execute on the owning DuckDB job connection through `COPY`, preserving the frame ownership and cleanup model.
Arrow IPC ingress is supported for `.arrow` and `.feather` inputs through the pinned Arrow Java runtime; unsupported complex fields are rejected with a typed diagnostic.

The compatibility function surface includes the explicit `HashRegistry` and `SimilarityRegistry`; similarity names are `exact`, `jaccard`, and `normalized_levenshtein`, and results are constrained to the inclusive range `[0,1]`.
