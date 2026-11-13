param(
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

$testFlag = if ($SkipTests) { '-DskipTests' } else { '' }
Write-Host 'Building application...'
& mvn -q clean package $testFlag
if ($LASTEXITCODE -ne 0) { throw 'Maven build failed.' }

$target = Join-Path $PSScriptRoot 'target'
$jar = Join-Path $target 'restaurant-pos.jar'
$lib = Join-Path $target 'lib'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Application jar was not produced.' }
if (-not (Test-Path -LiteralPath $lib)) { throw 'Runtime dependencies were not copied.' }

$dist = Join-Path $PSScriptRoot 'dist'
if (Test-Path -LiteralPath $dist) { Remove-Item -LiteralPath $dist -Recurse -Force }
New-Item -ItemType Directory -Path $dist | Out-Null

$stage = Join-Path $dist 'package-input'
New-Item -ItemType Directory -Path $stage | Out-Null
Copy-Item -LiteralPath $jar -Destination $stage
Copy-Item -LiteralPath $lib -Destination $stage -Recurse

$jpackage = Get-Command jpackage -ErrorAction SilentlyContinue
if ($jpackage) {
    Write-Host 'Creating Windows app image with jpackage...'
    & jpackage --type app-image --dest $dist --name MubarakCafePOS --app-version 1.0.0 `
        --vendor 'Mubarak Cafe & Restaurant' --description 'Mubarak Cafe & Restaurant POS' `
        --icon (Join-Path $PSScriptRoot 'src\main\resources\branding\logo.ico') `
        --input $stage --main-jar 'restaurant-pos.jar' `
        --main-class 'com.restaurantpos.app.Launcher' `
        --java-options '--enable-native-access=ALL-UNNAMED'
    if ($LASTEXITCODE -ne 0) { throw 'jpackage failed.' }
    Remove-Item -LiteralPath $stage -Recurse -Force
    Write-Host "Portable app created: dist\MubarakCafePOS\MubarakCafePOS.exe"
} else {
    $app = Join-Path $dist 'MubarakCafePOS'
    Move-Item -LiteralPath $stage -Destination $app
    $launcher = @'
@echo off
setlocal
cd /d "%~dp0"
start "" javaw --enable-native-access=ALL-UNNAMED -cp "restaurant-pos.jar;lib/*" com.restaurantpos.app.Launcher
'@
    Set-Content -LiteralPath (Join-Path $app 'Start Mubarak Cafe POS.bat') -Value $launcher -Encoding ASCII
    Write-Host "Portable app created: dist\MubarakCafePOS\Start Mubarak Cafe POS.bat"
}
