param(
  [string]$NativeImage = "native-image",
  [string]$Output = "dist-native",
  [string]$BinaryName = "zingg-duckdb-worker"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $root "runtime-worker\target\runtime-worker-0.1.0-SNAPSHOT.jar"
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw "runtime worker jar is missing; run Maven package first" }
New-Item -ItemType Directory -Force -Path $Output | Out-Null
$target = Join-Path (Resolve-Path -LiteralPath $Output).Path $BinaryName
& $NativeImage `
  --no-fallback `
  --enable-url-protocols=http,https `
  --initialize-at-build-time=org.duckdb `
  -H:+UnlockExperimentalVMOptions `
  -H:ConfigurationFileDirectories="$root\runtime-worker\src\main\resources\META-INF\native-image" `
  -jar $jar $target
if ($LASTEXITCODE -ne 0) { throw "native-image failed: $LASTEXITCODE" }
if (-not (Test-Path -LiteralPath $target -PathType Leaf)) { throw "native worker was not produced: $target" }
Get-FileHash -Algorithm SHA256 -LiteralPath $target | ConvertTo-Json | Set-Content (Join-Path $Output "$BinaryName.sha256.json") -Encoding utf8
Write-Output $target
