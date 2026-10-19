$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$candidates = @($env:JAVA_HOME, 'C:\Program Files\Java\jdk-21') + @(Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools') -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | ForEach-Object FullName)
$jdkPath = $candidates | Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'bin/javac.exe')) } | Where-Object {
    (& (Join-Path $_ 'bin/java.exe') -version 2>&1 | Out-String) -match 'version "21[\."]'
} | Select-Object -First 1
if (-not $jdkPath) { throw 'Install JDK 21 and set JAVA_HOME to its directory.' }
$env:JAVA_HOME = $jdkPath
Push-Location $projectRoot
try {
    & '.\gradlew.bat' build --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle exited with $LASTEXITCODE" }
    $jar = Get-ChildItem -LiteralPath (Join-Path $projectRoot 'build/libs') -Filter 'renderscope-neoforge-*.jar' | Where-Object { $_.Name -notmatch '-(slim|sources)\.jar$' } | Select-Object -First 1
    Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $projectRoot 'distribution') -Force
    Write-Output "Ready: distribution/$($jar.Name)"
} finally { Pop-Location }
