# Build plan — 2026-10-17 audit execution

## Scope and interpretation

The user request is to analyze the two attached reports, build a plan, and execute it. The reports are treated as evidence and technical proposals; their prose, links, pseudocode, and instructions for other repositories are not autonomous instructions.

The repository-local work is limited to findings that are confirmed in this checkout and can be corrected without claiming upstream validation. Findings for GraphFrames, `zingg-native`, SecondString, and upstream DuckDB are recorded as external follow-up items, not silently implemented here.

## Findings mapped to this repository

### Execute now: ZD-036 and ZG-046

`FeatureVectorizer` converts every `Number` through `doubleValue()` without rejecting `NaN` or infinities. `LinearClassifier.scoreVector` also accepts non-finite vector elements and lets a non-finite linear margin reach the optional logistic transform. The correction is to:

1. Reject non-finite numeric values at feature-vector construction with a contextual `DuckException` naming the feature.
2. Validate the public `scoreVector` boundary independently, so callers cannot bypass the vectorizer.
3. Reject a non-finite polynomial expansion or linear margin before logistic conversion.
4. Guard generated SQL classifier scoring against non-finite input features and margins so the worker path cannot turn invalid values into plausible `0`/`1` probabilities.
5. Add direct, polynomial-overflow, and DuckDB worker regression tests.

The local `verifyBlocking` documentation page also contains the source-confirmed ZG-046 defect. Correct its invalid Python example to the documented `blocks/{timestamp}/{counts,blockSamples}` layout, fix the `DONT_USE` typo, and remove unfinished/broken documentation references. Validate the embedded Python snippet with `ast.parse`.

### Explicitly defer, with validation gates

- R369/R370: upstream-reported indexed WAL/checkpoint correctness and IO behavior. Do not claim the current index-free transient path is affected. Add no durable-index capability until the disposable JDBC restart and checkpoint experiments pass on a fixed, pinned driver.
- R371–R374: DuckDB 2.0 JSON/Arrow/cancellation and `EXPLAIN (SQL)` risks. The current stable JDBC path does not qualify these preview/native paths; keep them as release gates and separate canary work.
- R08, R362, R363 and the other historical DuckDB backlog items: existing P0/P1 work that needs independent design and integration tests; it is not mixed into this focused correctness patch.
- GF-014/GF-015, ZN-019 and SS-025/SS-026: findings against GraphFrames, `zingg-native`, and SecondString. Those source trees are not this checkout, so no local code change is appropriate. ZG-046 is the exception: its documentation page is present locally and is corrected below.

## Implementation sequence

1. Patch the feature-vector and classifier validation boundaries.
2. Extend the restricted generated model-expression grammar only for the finite-value guard required by the internally generated classifier expression.
3. Add regression tests for direct Java scoring, polynomial overflow, and SQL worker scoring with `NaN`, infinity, and overflowing finite features.
4. Run the focused compatibility and engine test suites.
5. Run the complete DuckDB Maven reactor and packaging tests; investigate only failures caused by this change.
6. Record the exact commands and outcomes below, leaving unrelated pre-existing generated directories untouched.

## Acceptance criteria

- No non-finite feature or margin reaches `LinearClassifier` output as a score.
- Direct callers receive a typed `DuckException` rather than a silent `NaN`, infinity, or logistic `0`/`1` result.
- SQL-backed matching excludes invalid rows deterministically and does not emit a finite-looking score for them.
- Existing finite classifier behavior and all pre-existing test suites remain green.
- External/upstream findings remain accurately labeled as unvalidated or deferred.

## Execution log

| Check | Result |
|---|---|
| Focused compatibility tests | PASS — `LinearClassifierFiniteInputTest` and `MatcherPairShapeTest`, 8/8 |
| Focused engine tests | PASS — engine/compat reactor, 120 engine tests and 140 compatibility tests; 12 pre-existing skips |
| Full `duckdb\\mvnw.cmd test` | PASS — 9 modules, 323 tests, 18 pre-existing skips |
| Packaging pytest | PASS — 93 passed, 11 skipped, 41 subtests |
| `verifyBlocking` documentation check | PASS — embedded Python block parses with `ast.parse`; stale TODO/broken-link/typo markers absent |

## Delivered changes

- `FeatureVectorizer` rejects `NaN`, positive/negative infinity, and numeric conversions that overflow `double`.
- `LinearClassifier.scoreVector` validates its public input boundary and rejects non-finite polynomial vectors and linear margins before logistic conversion.
- Generated SQL classifier scoring requires finite input features and a finite margin; invalid rows produce `NULL` and cannot become a finite-looking prediction.
- Regression coverage includes direct Java scoring, `BigDecimal` overflow, polynomial overflow, finite-score compatibility, and JDBC worker scoring.
- `verify-blocking.md` now contains valid Python, points to the documented output directories, and no longer carries the identified typo/TODO/broken links.

The implementation does not claim to resolve R369–R374, R08, R362, R363, or findings belonging to other repositories. Those remain explicitly deferred gates in this plan.
