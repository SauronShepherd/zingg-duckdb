[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$Bundle,[switch]$RequireJre,[switch]$RequireSignature)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $Bundle).Path
$required = @('SHA256SUMS','sbom.spdx.json','config/runtime-manifest.json','config/compatibility-profile.json','config/compatibility-capsule.json','config/release-policy.json','config/source-provenance.json','worker/runtime-worker-0.1.0-SNAPSHOT.jar','legacy/legacy-blocking-import-zingg07-spark35-0.1.0-SNAPSHOT.jar','legacy/legacy-classifier-import-zingg07-spark35-0.1.0-SNAPSHOT.jar','python/zingg_duckdb/__init__.py','python/zingg_duckdb/client.py','python/zingg_duckdb/worker.py','bin/zingg-duckdb.cmd','bin/zingg-duckdb.ps1','bin/zingg-duckdb.sh','packaging/invoke-legacy-import.ps1','packaging/sign-package.ps1')
if ($RequireJre) { $javaName = if ($IsWindows) { 'java.exe' } else { 'java' }; $required += "runtime/java/bin/$javaName"; $required += 'runtime/java/JAVA-VERSION.txt' }
foreach ($relative in $required) { if (-not (Test-Path -LiteralPath (Join-Path $root $relative) -PathType Leaf)) { throw "required bundle file is missing: $relative" } }
$sumFile = Join-Path $root 'SHA256SUMS'
foreach ($line in Get-Content -LiteralPath $sumFile) {
  if ([string]::IsNullOrWhiteSpace($line)) { continue }
  $parts = $line -split '  ', 2
  if ($parts.Count -ne 2) { throw "invalid SHA256SUMS line: $line" }
  $file = Join-Path $root $parts[1]
  if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "hashed file is missing: $($parts[1])" }
  $actual = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
  if ($actual -ne $parts[0].ToLowerInvariant()) { throw "hash mismatch: $($parts[1])" }
}
$profile = Get-Content -LiteralPath (Join-Path $root 'config/compatibility-profile.json') -Raw | ConvertFrom-Json
$capsule = Get-Content -LiteralPath (Join-Path $root 'config/compatibility-capsule.json') -Raw | ConvertFrom-Json
$policy = Get-Content -LiteralPath (Join-Path $root 'config/release-policy.json') -Raw | ConvertFrom-Json
$provenance = Get-Content -LiteralPath (Join-Path $root 'config/source-provenance.json') -Raw | ConvertFrom-Json
if ($provenance.schema -ne 'zingg-duckdb-provenance-1' -or -not $provenance.files -or $provenance.files.Count -lt 1) { throw 'source provenance is incomplete' }
if ([string]::IsNullOrWhiteSpace($provenance.workerArtifactSha256)) { throw 'worker artifact provenance is missing' }
if ($capsule.schema -ne 'zingg-duckdb-compatibility-capsule-1' -or $capsule.profile -ne $profile.id -or -not $capsule.notices -or $null -eq $capsule.patches) { throw 'compatibility capsule is incomplete' }
if ($policy.schema -ne 'zingg-duckdb-release-policy-1' -or $policy.jdk.major -ne 21 -or -not $policy.jdk.provenanceRequired -or -not $policy.signing.authenticodeRequiredForRelease) { throw 'release policy is incomplete' }
$sbom = Get-Content -LiteralPath (Join-Path $root 'sbom.spdx.json') -Raw | ConvertFrom-Json
if ([string]::IsNullOrWhiteSpace($sbom.spdxVersion)) { throw 'SBOM must declare spdxVersion' }
if (-not $sbom.packages -or $sbom.packages.Count -lt 1) { throw 'SBOM must contain at least one package' }
$manifest = Get-Content -LiteralPath (Join-Path $root 'config/runtime-manifest.json') -Raw | ConvertFrom-Json
if ($manifest.java.major -ne 21 -or -not $manifest.java.bundled) { throw 'runtime manifest must declare bundled Java 21' }
if ($profile.id -ne 'zingg-0.7.0-duckdb-1.5.5.1') { throw 'unexpected compatibility profile' }
if (-not $profile.rules.similarities) { throw 'similarity registry declaration is missing' }
if ($RequireJre) { $javaVersion = Get-Content -LiteralPath (Join-Path $root 'runtime/java/JAVA-VERSION.txt') -Raw; if ([string]::IsNullOrWhiteSpace($javaVersion) -or $javaVersion -notmatch '21') { throw 'embedded runtime must declare Java 21' }; $jdk = Get-Content -LiteralPath (Join-Path $root 'runtime/java/JDK-PROVENANCE.json') -Raw | ConvertFrom-Json; if ($jdk.schema -ne 'zingg-duckdb-jdk-provenance-1' -or $jdk.javaMajor -ne 21 -or [string]::IsNullOrWhiteSpace($jdk.javaSha256)) { throw 'JDK provenance is incomplete' } }
if (Get-ChildItem -LiteralPath $root -Recurse -Directory -Filter '__pycache__') { throw '__pycache__ must not be present in bundle' }
if ($RequireSignature) { $signature = Get-AuthenticodeSignature -FilePath (Join-Path $root 'SHA256SUMS'); if ($signature.Status -ne 'Valid') { throw "SHA256SUMS signature is not valid: $($signature.Status)" } }
Write-Output 'PACKAGE_VERIFY_SUCCESS'
