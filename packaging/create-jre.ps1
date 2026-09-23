param(
  [string]$JavaHome = $env:JAVA_HOME,
  [string]$Output = "dist\zingg-duckdb-0.1.0\runtime\java"
)
$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($JavaHome)) { throw "JAVA_HOME must point to a Java 21 JDK" }
$javaName = if ($IsWindows) { 'java.exe' } else { 'java' }
$jlinkName = if ($IsWindows) { 'jlink.exe' } else { 'jlink' }
$java = Join-Path $JavaHome (Join-Path 'bin' $javaName)
$jlink = Join-Path $JavaHome (Join-Path 'bin' $jlinkName)
if (-not (Test-Path $java) -or -not (Test-Path $jlink)) { throw "Java 21 JDK tools were not found under $JavaHome" }
$version = (& $java -version 2>&1 | Where-Object { $_ -match 'version "' } | Select-Object -First 1)
if ($version -notmatch 'version "21\.') { throw "Java 21 is required; found $version" }
$destination = Join-Path (Get-Location) $Output
if (Test-Path $destination) { Remove-Item -LiteralPath $destination -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Split-Path $destination) | Out-Null
& $jlink --add-modules java.base,java.logging,java.management,java.naming,java.sql,java.xml,jdk.crypto.ec --strip-debug --no-header-files --no-man-pages --compress=2 --output $destination
if ($LASTEXITCODE -ne 0) { throw "jlink failed: $LASTEXITCODE" }
& $java -version 2>&1 | Set-Content (Join-Path $destination "JAVA-VERSION.txt")
$javaHash = (Get-FileHash -LiteralPath $java -Algorithm SHA256).Hash.ToLowerInvariant()
[ordered]@{ schema='zingg-duckdb-jdk-provenance-1'; javaMajor=21; javaHome=(Resolve-Path $JavaHome).Path; javaExecutable=(Join-Path 'bin' $javaName); javaSha256=$javaHash; versionFile='JAVA-VERSION.txt' } |
  ConvertTo-Json -Depth 4 | Set-Content (Join-Path $destination "JDK-PROVENANCE.json") -Encoding utf8
Write-Output "Created $destination"
