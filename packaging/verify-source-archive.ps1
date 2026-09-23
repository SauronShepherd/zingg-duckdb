param([Parameter(Mandatory=$true)][string]$Archive)
$ErrorActionPreference = 'Stop'
$archivePath = (Resolve-Path -LiteralPath $Archive).Path
$stage = Join-Path ([IO.Path]::GetTempPath()) ("zingg-duckdb-source-verify-" + [guid]::NewGuid().ToString('N'))
try {
  Expand-Archive -LiteralPath $archivePath -DestinationPath $stage -Force
  $manifestPath = Join-Path $stage 'SOURCE-MANIFEST.json'
  if (-not (Test-Path -LiteralPath $manifestPath)) { throw 'SOURCE-MANIFEST.json is missing' }
  $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
  if ($manifest.schema -ne 'zingg-duckdb-source-archive-1') { throw 'unsupported source archive schema' }
  foreach ($entry in $manifest.files) {
    $path = Join-Path $stage ($entry.path -replace '/', [IO.Path]::DirectorySeparatorChar)
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "missing source file: $($entry.path)" }
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256) { throw "source hash mismatch: $($entry.path)" }
  }
  Write-Output 'SOURCE_ARCHIVE_VERIFY_SUCCESS'
} finally { if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force } }
