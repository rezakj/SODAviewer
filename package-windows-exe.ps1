# Build a native Windows x64 jpackage app-image (SODAviewer.exe).
# PowerShell / cmd only — do not run under Git Bash (path / $APPDIR mangling).
#
# Prerequisites: JDK 17+ with jlink/jpackage, fat JAR at target\SODAviewer.jar,
# Windows OpenJFX jars in lib\javafx\.
#
# Usage (from repo root, PowerShell):
#   .\package-windows-exe.ps1
#   $env:VERSION='0.2.4'; .\package-windows-exe.ps1

$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $Root

function Resolve-JdkTool([string]$Name) {
  $candidates = @()
  if ($env:JAVA_HOME) {
    $candidates += (Join-Path $env:JAVA_HOME "bin\$Name.exe")
    $candidates += (Join-Path $env:JAVA_HOME "bin\$Name")
  }
  $cmd = Get-Command $Name -ErrorAction SilentlyContinue
  if ($cmd) { $candidates += $cmd.Source }
  $cmdExe = Get-Command "$Name.exe" -ErrorAction SilentlyContinue
  if ($cmdExe) { $candidates += $cmdExe.Source }
  foreach ($c in $candidates) {
    if ($c -and (Test-Path -LiteralPath $c)) { return (Resolve-Path -LiteralPath $c).Path }
  }
  throw "$Name not found. Set JAVA_HOME to a JDK 17+ (not a JRE). JAVA_HOME=$($env:JAVA_HOME)"
}

if (-not $env:VERSION -or [string]::IsNullOrWhiteSpace($env:VERSION)) {
  if (Test-Path -LiteralPath (Join-Path $Root 'VERSION')) {
    $env:VERSION = (Get-Content -LiteralPath (Join-Path $Root 'VERSION') -Raw).Trim()
  } else {
    $env:VERSION = '0.2.4'
  }
}

$Version = $env:VERSION.Trim()
$AppName = 'SODAviewer'
$Platform = 'windows-x64'
$Jar = Join-Path $Root 'target\SODAviewer.jar'
if (-not (Test-Path -LiteralPath $Jar)) {
  throw "Missing $Jar — run 'mvn -B -DskipTests package' first"
}

$JfxDir = Join-Path $Root 'lib\javafx'
$JfxJars = @(Get-ChildItem -LiteralPath $JfxDir -Filter 'javafx-*-win.jar' -ErrorAction SilentlyContinue)
if ($JfxJars.Count -lt 5) {
  throw "Need Windows OpenJFX jars in $JfxDir (found $($JfxJars.Count)). Download javafx-*-win.jar first."
}

$Jpackage = Resolve-JdkTool 'jpackage'
$Jlink = Resolve-JdkTool 'jlink'
Write-Host "Using jpackage=$Jpackage"
Write-Host "Using jlink=$Jlink"
Write-Host "VERSION=$Version PLATFORM=$Platform"

$Dist = Join-Path $Root 'dist'
$Runtime = Join-Path $Dist "runtime-$Platform"
$Stage = Join-Path $Dist 'jpackage-input'
$AppImage = Join-Path $Dist $AppName
$OutZip = Join-Path $Dist "$AppName-$Version-$Platform-exe.zip"
$FlatExe = Join-Path $Dist 'SODAviewer.exe'
$VersionedJar = Join-Path $Dist "$AppName-$Version.jar"

New-Item -ItemType Directory -Force -Path $Dist | Out-Null
if (Test-Path -LiteralPath $Runtime) { Remove-Item -LiteralPath $Runtime -Recurse -Force }
if (Test-Path -LiteralPath $Stage) { Remove-Item -LiteralPath $Stage -Recurse -Force }
if (Test-Path -LiteralPath $AppImage) { Remove-Item -LiteralPath $AppImage -Recurse -Force }
if (Test-Path -LiteralPath $OutZip) { Remove-Item -LiteralPath $OutZip -Force }
if (Test-Path -LiteralPath $FlatExe) { Remove-Item -LiteralPath $FlatExe -Force }

$Modules = 'java.base,java.desktop,java.logging,java.xml,java.datatransfer,java.prefs,java.net.http,java.scripting,jdk.unsupported,jdk.unsupported.desktop,jdk.jsobject,jdk.xml.dom,jdk.crypto.ec'

Write-Host "==> jlink ($Platform)..."
& $Jlink `
  --add-modules $Modules `
  --strip-debug `
  --no-header-files `
  --no-man-pages `
  --compress=2 `
  --output $Runtime
if ($LASTEXITCODE -ne 0) { throw "jlink failed: $LASTEXITCODE" }

Write-Host "==> stage jpackage input..."
New-Item -ItemType Directory -Force -Path (Join-Path $Stage 'javafx') | Out-Null
Copy-Item -LiteralPath $Jar -Destination (Join-Path $Stage 'SODAviewer.jar') -Force
Copy-Item -Path (Join-Path $JfxDir 'javafx-*-win.jar') -Destination (Join-Path $Stage 'javafx') -Force

# Pass $APPDIR literally to jpackage (PowerShell must not expand it).
$ModulePathOpt = '--module-path $APPDIR/javafx'
$AddModulesOpt = '--add-modules javafx.controls,javafx.web,javafx.swing'

Write-Host "==> jpackage app-image..."
& $Jpackage `
  --type app-image `
  --name $AppName `
  --app-version $Version `
  --input $Stage `
  --main-jar SODAviewer.jar `
  --main-class sodaviewer.Main `
  --runtime-image $Runtime `
  --dest $Dist `
  --java-options '-Xmx4g' `
  --java-options $ModulePathOpt `
  --java-options $AddModulesOpt `
  --description 'SODA archive viewer - overview + gene expression on UMAP / PCA'
if ($LASTEXITCODE -ne 0) { throw "jpackage failed: $LASTEXITCODE" }

$Exe = Join-Path $AppImage 'SODAviewer.exe'
if (-not (Test-Path -LiteralPath $Exe)) {
  Write-Host 'dist tree:'
  Get-ChildItem -LiteralPath $Dist -Recurse -File | Select-Object -First 80 | ForEach-Object { $_.FullName }
  throw "Missing $Exe after jpackage"
}

Copy-Item -LiteralPath $Exe -Destination $FlatExe -Force
Copy-Item -LiteralPath $Jar -Destination $VersionedJar -Force

Write-Host "==> zip app-image -> $OutZip"
# tar.exe is on windows-2022; -a picks zip from the .zip extension.
Push-Location $Dist
try {
  & tar.exe -a -cf (Split-Path -Leaf $OutZip) $AppName
  if ($LASTEXITCODE -ne 0) { throw "tar zip failed: $LASTEXITCODE" }
}
finally {
  Pop-Location
}

if (-not (Test-Path -LiteralPath $OutZip) -or (Get-Item -LiteralPath $OutZip).Length -lt 1000) {
  throw "Zip missing or too small: $OutZip"
}

Get-Item -LiteralPath $Exe, $FlatExe, $OutZip, $VersionedJar | Format-Table Name, Length
Write-Host ""
Write-Host "Windows jpackage exe: $Exe"
Write-Host "Flattened launcher:   $FlatExe"
Write-Host "App-image zip:        $OutZip"
Write-Host "Universal JAR:        $VersionedJar"
Write-Host "Run: $Exe"
