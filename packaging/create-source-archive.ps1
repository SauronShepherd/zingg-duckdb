param([Parameter(Mandatory=$true)][string]$Root,[Parameter(Mandatory=$true)][string]$Output)
$ErrorActionPreference = 'Stop'
$rootPath = (Resolve-Path -LiteralPath $Root).Path
$outputPath = [IO.Path]::GetFullPath($Output)
$stage = Join-Path ([IO.Path]::GetTempPath()) ("zingg-duckdb-source-" + [guid]::NewGuid().ToString('N'))
try {
  New-Item -ItemType Directory -Path $stage | Out-Null
  $files = Get-ChildItem -LiteralPath $rootPath -Recurse -File | Where-Object { $_.FullName -notmatch '[\\/]\.git[\\/]' -and $_.FullName -notmatch '[\\/]target[\\/]' -and $_.FullName -notmatch '[\\/]dist[\\/]' -and $_.FullName -notmatch '[\\/]__pycache__[\\/]' -and $_.FullName -notmatch '[\\/]\.pytest_cache[\\/]' }
  foreach ($file in $files) {
    $relative = [IO.Path]::GetRelativePath($rootPath, $file.FullName)
    $destination = Join-Path $stage $relative
    New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($destination)) | Out-Null
    Copy-Item -LiteralPath $file.FullName -Destination $destination
  }
  $hashes = foreach ($file in (Get-ChildItem -LiteralPath $stage -Recurse -File | Sort-Object FullName)) {
    $relative = [IO.Path]::GetRelativePath($stage, $file.FullName).Replace('\','/')
    [ordered]@{ path=$relative; sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
  }
  $manifest = [ordered]@{ schema='zingg-duckdb-source-archive-1'; compatibilityProfile='zingg-0.7.0-duckdb-1.5.5.1'; files=@($hashes) }
  $manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $stage 'SOURCE-MANIFEST.json') -Encoding utf8
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $outputPath) | Out-Null
  if (Test-Path -LiteralPath $outputPath) { Remove-Item -LiteralPath $outputPath -Force }
  Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $outputPath -CompressionLevel Optimal
} finally { if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force } }
