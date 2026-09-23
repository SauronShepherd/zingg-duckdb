# Packaging

`package.ps1` builds all Maven modules with tests skipped and assembles the worker, Python control plane, runtime manifest, notices, and SHA-256 inventory under `dist/zingg-duckdb-0.1.0`. Production bundles must use `-CreateJre` to include the bundled Java 21 runtime; the packaged Python client rejects a distribution that contains the worker but lacks `runtime/java`.

The bundle layout is consumed directly by the Python client: the executable worker is under `worker/`, the Python package under `python/`, and an optional project-bundled JRE under `runtime/java/`. The client also retains a checkout fallback to `runtime-worker/target/` for local development.

The production distribution must add the selected signed Java 21 `jlink` runtime under `runtime/java/`. The manifest deliberately records Java as bundled and disables extension auto-installation.

Generate the local runtime with `./packaging/create-jre.ps1` from a Java 21 JDK. The selected JDK vendor, build, checksum, and security-update policy must be recorded in the release provenance before distribution.

For a signed release, run `./packaging/sign-package.ps1 -Bundle dist/zingg-duckdb-0.1.0 -CertificateThumbprint <thumbprint>` after packaging, then verify it with `./packaging/verify-package.ps1 -Bundle dist/zingg-duckdb-0.1.0 -RequireJre -RequireSignature`.

After assembly, run `./packaging/verify-package.ps1 -Bundle dist/zingg-duckdb-0.1.0 -RequireJre`. The verifier checks the SHA-256 inventory, SBOM/profile presence, embedded worker/JRE, and absence of Python cache directories; `package.ps1` invokes the same gate automatically.

Create a reproducible source capsule with `./packaging/create-source-archive.ps1 -Root . -Output dist/zingg-duckdb-source.zip`. The archive excludes Git metadata, build outputs, distributions, and Python caches, and contains `SOURCE-MANIFEST.json` with per-file SHA-256 hashes. Verify it offline with `./packaging/verify-source-archive.ps1 -Archive dist/zingg-duckdb-source.zip` before publishing.

`package.ps1` creates and verifies this source capsule automatically beside the binary bundle. The bundle also contains `config/compatibility-capsule.json`, which records the upstream profile, source/dependency provenance, notices, patches, and process-isolation boundary.

The repository workflow compiles the JVM modules on Linux amd64/arm64, macOS x86_64/arm64, and Windows x86_64. The Windows job additionally assembles the bundled Java runtime and publishes the verified binary bundle and source capsule. CI deliberately passes `-DskipTests` while implementation is being completed; test execution is a separate phase.

Create a rollback artifact from a verified bundle with `./packaging/create-rollback-package.ps1 -Bundle dist/zingg-duckdb-0.1.0 -Output dist/zingg-duckdb-0.1.0-rollback.zip`. Its manifest binds the rollback archive to the bundle `SHA256SUMS` hash and records the safe replacement procedure.

The release policy is bundled as `config/release-policy.json`. A JRE build also emits `runtime/java/JDK-PROVENANCE.json` with the Java 21 executable hash and version source. Production releases must satisfy the policy, review the generated SPDX license inventory, and Authenticode-sign `SHA256SUMS` with a timestamp.

The Windows bundle exposes `bin/zingg-duckdb.cmd` and `bin/zingg-duckdb.ps1`; both require and invoke only the bundled Java 21 runtime and worker jar.
