param([Parameter(Mandatory=$true)][string]$Root,[Parameter(Mandatory=$true)][string]$Output)
$ErrorActionPreference = 'Stop'
$rootPath = (Resolve-Path -LiteralPath $Root).Path
$relativeFiles = @('pom.xml','dependency-lock.json','README.md','NOTICE.md','LICENSE')
$relativeFiles += 'runtime-worker/target/runtime-worker-0.1.0-SNAPSHOT.jar'
$relativeFiles += Get-ChildItem -LiteralPath $rootPath -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'pom.xml') } | ForEach-Object { Join-Path $_.Name 'pom.xml'; Join-Path $_.Name 'dependency-lock.json' }
$relativeFiles = $relativeFiles | Sort-Object -Unique
$files = @()
foreach ($relative in $relativeFiles) { $path = Join-Path $rootPath $relative; if (Test-Path -LiteralPath $path -PathType Leaf) { $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant(); $files += [ordered]@{path=$relative.Replace('\','/'); sha256=$hash} } }
$worker = $files | Where-Object { $_.path -eq 'runtime-worker/target/runtime-worker-0.1.0-SNAPSHOT.jar' } | Select-Object -First 1
$workerHash = ''
if ($worker) { $workerHash = $worker.sha256 }
$document = [ordered]@{schema='zingg-duckdb-provenance-1'; compatibilityProfile='zingg-0.7.0-duckdb-1.5.5.1'; workerArtifactSha256=$workerHash; files=$files}
$document | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $Output -Encoding utf8
