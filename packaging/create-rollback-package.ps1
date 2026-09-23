param([Parameter(Mandatory=$true)][string]$Bundle,[Parameter(Mandatory=$true)][string]$Output)
$ErrorActionPreference = 'Stop'
$bundlePath = (Resolve-Path -LiteralPath $Bundle).Path
$outputPath = [IO.Path]::GetFullPath($Output)
if (-not (Test-Path -LiteralPath (Join-Path $bundlePath 'SHA256SUMS') -PathType Leaf)) { throw 'bundle must contain SHA256SUMS' }
$stage = Join-Path ([IO.Path]::GetTempPath()) ("zingg-duckdb-rollback-" + [guid]::NewGuid().ToString('N'))
try {
  New-Item -ItemType Directory -Force -Path $stage | Out-Null
  Copy-Item -LiteralPath $bundlePath -Destination (Join-Path $stage 'bundle') -Recurse
  $hash = (Get-FileHash -LiteralPath (Join-Path $bundlePath 'SHA256SUMS') -Algorithm SHA256).Hash.ToLowerInvariant()
  [ordered]@{ schema='zingg-duckdb-rollback-1'; bundleName=(Split-Path $bundlePath -Leaf); bundleSha256=$hash; restore='replace the active bundle only after verifying SHA256SUMS and compatibility profile' } |
    ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $stage 'ROLLBACK-MANIFEST.json') -Encoding utf8
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $outputPath) | Out-Null
  if (Test-Path -LiteralPath $outputPath) { Remove-Item -LiteralPath $outputPath -Force }
  Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $outputPath -CompressionLevel Optimal
} finally { if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force } }
