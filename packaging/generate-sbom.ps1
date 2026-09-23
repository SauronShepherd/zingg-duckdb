param([string]$LockFile = "dependency-lock.json", [string]$Output = "sbom.spdx.json")
$ErrorActionPreference = "Stop"
$lock = Get-Content -LiteralPath $LockFile -Raw | ConvertFrom-Json
$root = (Resolve-Path -LiteralPath $LockFile).Path | Split-Path -Parent
$packages = @(
  [ordered]@{ name="zingg-duckdb"; version="0.1.0"; license="AGPL-3.0-only" },
  [ordered]@{ name="duckdb_jdbc"; version=[string]$lock.runtime.duckdbJdbc; license="MIT" },
  [ordered]@{ name="arrow-java"; version=[string]$lock.runtime.arrowJava; license="Apache-2.0" },
  [ordered]@{ name="zingg-compatibility-target"; version=[string]$lock.runtime.zinggCompatibility; license="AGPL-3.0" },
  [ordered]@{ name="spark-reference"; version=[string]$lock.runtime.sparkReference; license="Apache-2.0" },
  [ordered]@{ name="java"; version=[string]$lock.runtime.java; license="JDK-vendor-license" }
)
$moduleLocks = Get-ChildItem -LiteralPath $root -File -Recurse -Filter "dependency-lock.json" |
  Where-Object { $_.FullName -ne (Join-Path $root "dependency-lock.json") } |
  ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json }
foreach ($module in $moduleLocks) {
  $packages += [ordered]@{
    name="module-$([string]$module.module)"; version="0.1.0"; license="AGPL-3.0-only"
  }
}
$document = [ordered]@{
  spdxVersion="SPDX-2.3"; dataLicense="CC0-1.0"; SPDXID="SPDXRef-DOCUMENT";
  name="zingg-duckdb-0.1.0"; documentNamespace="https://zingg-duckdb.invalid/sbom/0.1.0";
  packages=@($packages | ForEach-Object { [ordered]@{ SPDXID="SPDXRef-Package-$($_.name)"; name=$_.name; versionInfo=$_.version; licenseConcluded=$_.license; licenseDeclared=$_.license } })
}
$document | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $Output -Encoding utf8
