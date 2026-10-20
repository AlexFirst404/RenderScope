param(
    [ValidateSet('forge', 'neoforge')]
    [string]$Loader = 'forge',
    [switch]$Smoke
)

$ErrorActionPreference = 'Stop'
$Loader = $Loader.ToLowerInvariant()
$projectRoot = Split-Path -Parent $PSScriptRoot
$properties = @{}
Get-Content -LiteralPath (Join-Path $projectRoot 'gradle.properties') | ForEach-Object {
    if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(\S+)\s*$') { $properties[$Matches[1]] = $Matches[2] }
}
if (-not $properties['mod_version'] -or -not $properties['minecraft_version']) { throw 'Missing mod_version or minecraft_version in gradle.properties.' }
$artifactName = "renderscope-$Loader-$($properties['mod_version'])+mc$($properties['minecraft_version']).jar"
$destination = if ($Loader -eq 'forge') { Join-Path $projectRoot 'distribution/forge' } else { Join-Path $projectRoot 'distribution' }
$candidates = @($env:JAVA_HOME, 'C:\Program Files\Java\jdk-21') + @(Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools') -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | ForEach-Object FullName)
$jdkPath = $candidates | Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'bin/javac.exe')) } | Where-Object {
    (& (Join-Path $_ 'bin/java.exe') -version 2>&1 | Out-String) -match 'version "21[\."]'
} | Select-Object -First 1
if (-not $jdkPath) { throw 'Install JDK 21 and set JAVA_HOME to its directory.' }
$env:JAVA_HOME = $jdkPath
Push-Location $projectRoot
try {
    & '.\gradlew.bat' "-Ploader=$Loader" build --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle exited with $LASTEXITCODE" }
    if ($Smoke) {
        $versionProperty = "${Loader}_version"
        $versionPattern = '^\s*' + [regex]::Escape($versionProperty) + '\s*=\s*(\S+)\s*$'
        $versionMatch = Select-String -LiteralPath (Join-Path $projectRoot 'gradle.properties') -Pattern $versionPattern | Select-Object -First 1
        if (-not $versionMatch) { throw "Missing $versionProperty in gradle.properties." }
        $expectedLoaderVersion = $versionMatch.Matches[0].Groups[1].Value
        $expectedLoaderName = if ($Loader -eq 'forge') { 'Forge' } else { 'NeoForge' }
        $smokeStartedAtUtc = [DateTime]::UtcNow
        & '.\gradlew.bat' "-Ploader=$Loader" runSmokeClient --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Minecraft smoke run exited with $LASTEXITCODE" }
        $smokeResultPath = Join-Path $destination 'smoke-result.json'
        if (-not (Test-Path -LiteralPath $smokeResultPath -PathType Leaf)) { throw 'Minecraft smoke run did not write smoke-result.json.' }
        $smokeResultFile = Get-Item -LiteralPath $smokeResultPath
        if ($smokeResultFile.LastWriteTimeUtc -lt $smokeStartedAtUtc) { throw 'Minecraft smoke result is stale; the current run did not produce fresh evidence.' }
        $smokeResult = Get-Content -LiteralPath $smokeResultPath -Raw | ConvertFrom-Json
        if ($smokeResult.success -isnot [bool] -or -not $smokeResult.success) { throw 'Minecraft smoke checks failed; inspect smoke-result.json and the client log.' }
        if ($smokeResult.loader -cne $expectedLoaderName -or $smokeResult.loaderVersion -cne $expectedLoaderVersion) {
            throw "Minecraft smoke result does not match $expectedLoaderName $expectedLoaderVersion."
        }
    }
    $artifactDirectory = Join-Path $projectRoot "build/$Loader/libs"
    $artifactPath = Join-Path $artifactDirectory $artifactName
    if (-not (Test-Path -LiteralPath $artifactPath -PathType Leaf)) { throw "Missing current bundled $Loader artifact: $artifactPath" }
    New-Item -ItemType Directory -Force -Path $destination | Out-Null
    Copy-Item -LiteralPath $artifactPath -Destination $destination -Force
    $relativeDestination = if ($Loader -eq 'forge') { 'distribution/forge' } else { 'distribution' }
    Write-Output "Ready: $relativeDestination/$artifactName"
} finally { Pop-Location }
