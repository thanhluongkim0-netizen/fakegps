param(
    [string]$InputDirectory = (Join-Path $PSScriptRoot '..\..\platform-tools\vrace-apks'),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\artifacts\vrace-test'),
    [string]$MapsApiKeyFile = '',
    [switch]$BoundedMap
)
$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path $PSScriptRoot -Parent
$unsignedDirectory = Join-Path $OutputDirectory 'unsigned'
$signedDirectory = Join-Path $OutputDirectory 'signed'
$keyDirectory = Join-Path $workspaceRoot '.tools\vrace-test-signing'
$originalFiles = @('base.apk', 'split_config.vi.apk', 'split_config.xxhdpi.apk') | ForEach-Object { Join-Path $InputDirectory $_ }
$mapsArguments = @()
if ($MapsApiKeyFile) {
    if (-not (Test-Path -LiteralPath $MapsApiKeyFile -PathType Leaf)) { throw 'Maps API key file does not exist.' }
    $mapsArguments = @('--maps-api-key-file', (Resolve-Path -LiteralPath $MapsApiKeyFile).Path)
}
if ($BoundedMap) { $mapsArguments += '--bounded-map' }

& node (Join-Path $PSScriptRoot 'apk_v2.mjs') verify @originalFiles
if ($LASTEXITCODE -ne 0) { throw 'Original APK signature verification failed.' }
& python (Join-Path $PSScriptRoot 'apk_test_build.py') --input-dir $InputDirectory --output-dir $unsignedDirectory @mapsArguments
if ($LASTEXITCODE -ne 0) { throw 'APK patch/content verification failed.' }
& node (Join-Path $PSScriptRoot 'apk_v2.mjs') sign $unsignedDirectory $signedDirectory $keyDirectory
if ($LASTEXITCODE -ne 0) { throw 'Test APK signing failed.' }
& python (Join-Path $PSScriptRoot 'apk_test_build.py') --input-dir $InputDirectory --verify-signed $signedDirectory @mapsArguments
if ($LASTEXITCODE -ne 0) { throw 'Signed APK content verification failed.' }
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'README.md') -Destination (Join-Path $signedDirectory 'HUONG-DAN.md')
$bundleFiles = @('base.apk', 'split_config.vi.apk', 'split_config.xxhdpi.apk', 'build-report.json', 'HUONG-DAN.md') | ForEach-Object { Join-Path $signedDirectory $_ }
$bundlePath = Join-Path $OutputDirectory 'vRace-test-2.5.5.zip'
Compress-Archive -LiteralPath $bundleFiles -DestinationPath $bundlePath -Force
Write-Output "Local test APK set: $signedDirectory"
Write-Output "Test bundle: $bundlePath"
Write-Output 'Device installation, login and backend acceptance remain untested.'
Write-Output 'Maps authorization must be checked on the phone; use the signer certificateSha1 in build-report.json.'
