# Semantic inventory

Compatibility profile: `zingg-0.7.0-duckdb-1.5.5.1`.

This inventory is the implementation-facing contract. New functions or operations must be added here before they are exposed through a public compatibility profile.

## Match and scoring types

| Type | Runtime implementation | Semantics |
|---|---|---|
| `BLOCKING_TREE` | `NativeTrainingService` | Deterministic frequency-based blocking artifact; null keys are represented as `__NULL__`. |
| `CLASSIFIER` | `LinearClassifier` / `ModelScorerRegistry` | Linear or logistic score with ordered features, optional polynomial expansion, and threshold metadata. |
| `predicate` | `Matcher.score` | Validated DuckDB predicate; rows are retained when the score is at least the configured threshold. |

## Hash and normalization functions

| Name | Null result | Notes |
|---|---|---|
| `java_hash` | implementation-defined compatibility hash | Preserves the Java-compatible hash contract. |
| `lower` | `null` | Locale-independent normalization. |
| `trim` | `null` | Java string trim semantics. |
| `sha256` | `null` | Lowercase hexadecimal digest. |
| `md5` | `null` | Lowercase hexadecimal digest for legacy compatibility only. |
| `length` | `null` | String length. |
| `java_round` | `null` | Numeric string conversion followed by Java-compatible rounding. |

## Similarity functions

| Name | Domain | Null behavior |
|---|---|---|
| `exact` | `[0,1]` | Equal values score `1`; null mismatch scores `0`. |
| `jaccard` | `[0,1]` | Token-set comparison with deterministic tokenization. |
| `normalized_levenshtein` | `[0,1]` | Edit-distance similarity normalized by the longer input. |

## ZFrame operation matrix

| Operation | Ordering | Null/type behavior | Duplicate behavior |
|---|---|---|---|
| `select` / `selectExpr` | Preserves requested expression order | DuckDB coercion rules | Preserves duplicates unless projected away |
| `filter` | Preserves input order | SQL three-valued logic | Preserves duplicates |
| `join` | Backend-dependent unless followed by `sort` | SQL comparison/coercion rules | Many-to-many matches retained |
| `union` | Left input followed by right input | Positional or name-based, with explicit missing-column policy | Preserves duplicates |
| `except` | Backend-defined; sort for stable output | Type-compatible columns required | Set difference semantics |
| `distinct` | Not guaranteed; sort for stable output | DuckDB equality semantics | Removes duplicate rows |
| `aggregate` | Group order is not guaranteed | DuckDB aggregate null rules | One row per group |
| `sort` | Explicitly deterministic for supplied expressions | DuckDB ordering and null placement | Does not remove duplicates |
| `limit` | Applies to current plan order | Rejects negative limits | Does not remove duplicates |
| `sample` | Seed must be explicit for reproducibility | Nulls are sampled as rows | Does not remove duplicates |
| `explode` | Element order follows list order | Null/empty lists produce no elements | Repeats parent rows per element |
| `split` | Each branch preserves source semantics | Predicate uses SQL three-valued logic | Rows may occur in both branches if predicate is unknown-handled explicitly |
| `cache` | Preserves logical rows | Materializes in run-private scope | Does not deduplicate |

## Required invariants

1. `z_zid` is assigned after source tagging and union, not before.
2. Match operations use by-name union; training operations use positional union.
3. Cross-job or cross-connection frame use is rejected.
4. Any output requiring reproducible comparison must specify an explicit sort.
5. Unsupported functions and model stages fail with their registered name/class, not a generic SQL error.
