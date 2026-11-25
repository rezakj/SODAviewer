#!/usr/bin/env bash
# Build a Windows x64 *portable* zip from any host (including macOS).
#
# jpackage cannot cross-compile a Windows .exe app-image. This script instead
# ships: fat JAR + Windows OpenJFX + bundled Temurin JRE + SODAviewer.bat.
# For a true jpackage .exe / .msi, run ./package-app.sh on Windows (or CI
# windows-2022) — see dist/WINDOWS-BUILD.txt after this script runs.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"

if [[ -z "${VERSION:-}" && -f "$ROOT/VERSION" ]]; then
  VERSION="$(tr -d '[:space:]' < "$ROOT/VERSION")"
fi
VERSION="${VERSION:-0.2.0}"
APP_NAME=SODAviewer
PLATFORM=windows-x64
JFX_VER="${JFX_VER:-17.0.14}"
JAR="$ROOT/target/SODAviewer.jar"

if [[ ! -f "$JAR" ]]; then
  echo "==> building fat JAR..."
  (cd "$ROOT" && ./build.sh)
fi
[[ -f "$JAR" ]] || { echo "Missing $JAR"; exit 1; }

DIST="$ROOT/dist"
STAGE="$DIST/${APP_NAME}-${VERSION}-${PLATFORM}"
OUT_ZIP="$DIST/${APP_NAME}-${VERSION}-${PLATFORM}.zip"
CACHE="$ROOT/dist/.cache-windows"
mkdir -p "$DIST" "$CACHE"

echo "==> fetch Temurin 17 Windows x64 JRE..."
JRE_ZIP="$CACHE/OpenJDK17U-jre_x64_windows.zip"
if [[ ! -f "$JRE_ZIP" ]]; then
  curl -fL --retry 3 --retry-delay 2 \
    -o "$JRE_ZIP" \
    "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jre/hotspot/normal/eclipse?project=jdk"
fi

echo "==> fetch OpenJFX ${JFX_VER} Windows jars..."
JFX_DIR="$CACHE/javafx-win-${JFX_VER}"
mkdir -p "$JFX_DIR"
BASE_MVN=https://repo.maven.apache.org/maven2/org/openjfx
for mod in base controls graphics swing web; do
  dest="$JFX_DIR/javafx-${mod}-${JFX_VER}-win.jar"
  if [[ ! -f "$dest" ]]; then
    curl -fL --retry 3 --retry-delay 2 \
      -o "$dest" \
      "$BASE_MVN/javafx-${mod}/${JFX_VER}/javafx-${mod}-${JFX_VER}-win.jar"
  fi
done

echo "==> stage ${STAGE}..."
rm -rf "$STAGE" "$OUT_ZIP"
mkdir -p "$STAGE/javafx" "$STAGE/runtime"

# Unpack JRE (zip has a single top-level jdk-*-jre folder)
TMP_JRE="$CACHE/jre-unpack"
rm -rf "$TMP_JRE"
mkdir -p "$TMP_JRE"
unzip -q "$JRE_ZIP" -d "$TMP_JRE"
JRE_ROOT="$(find "$TMP_JRE" -maxdepth 1 -type d ! -path "$TMP_JRE" | head -1)"
[[ -n "$JRE_ROOT" && -d "$JRE_ROOT" ]] || { echo "Failed to locate JRE root in zip"; exit 1; }
# Prefer rsync/cp -R contents into runtime/
cp -R "$JRE_ROOT"/. "$STAGE/runtime/"

cp -f "$JAR" "$STAGE/${APP_NAME}.jar"
cp -f "$JFX_DIR"/*.jar "$STAGE/javafx/"

# Windows launcher (CRLF)
python3 - "$STAGE/SODAviewer.bat" <<'PY'
import pathlib, sys
bat = pathlib.Path(sys.argv[1])
text = r"""@echo off
setlocal
set "DIR=%~dp0"
set "JAVA=%DIR%runtime\bin\java.exe"
if not exist "%JAVA%" (
  echo Bundled Java not found: %JAVA%
  echo Install Temurin 17+ or re-download this zip.
  exit /b 1
)
set "MODPATH=%DIR%javafx"
"%JAVA%" -Xmx4g --module-path "%MODPATH%" --add-modules javafx.controls,javafx.web,javafx.swing -jar "%DIR%SODAviewer.jar" %*
"""
bat.write_bytes(text.replace("\n", "\r\n").encode("ascii"))
PY

cat > "$STAGE/README-WINDOWS.txt" <<EOF
SODAviewer ${VERSION} — Windows x64 portable bundle
====================================================

This zip is built from macOS/Linux without jpackage cross-compile.
It includes a Temurin 17 JRE + Windows OpenJFX + the fat JAR.

Run
---
1. Unzip anywhere (no install).
2. Double-click SODAviewer.bat
   or:  SODAviewer.bat  path\\to\\archive.soda

Requirements
------------
- Windows x64 (Intel/AMD). Not ARM64.
- No system Java required (runtime\\ is bundled).

True Windows app-image (.exe via jpackage)
------------------------------------------
On a Windows machine (or GitHub Actions windows-2022):

  .\\build.sh          # or the CI javac/jar steps
  .\\package-app.sh    # produces dist\\SODAviewer-${VERSION}-windows-x64.zip with .exe

See WINDOWS-BUILD.txt in the dist folder of the source tree.
EOF

# Also refresh universal JAR + build notes in dist/
cp -f "$JAR" "$DIST/${APP_NAME}-${VERSION}.jar"

cat > "$DIST/WINDOWS-BUILD.txt" <<EOF
SODAviewer Windows packaging
============================

A) Portable zip (this machine / any OS) — DONE by package-windows-portable.sh
   Output: dist/${APP_NAME}-${VERSION}-${PLATFORM}.zip
   Contains: SODAviewer.bat + SODAviewer.jar + javafx\\ (win) + runtime\\ (Temurin JRE)
   Command:
     ./package-windows-portable.sh

B) Native jpackage app-image (.exe) — must run ON Windows
   jpackage cannot cross-compile from macOS.
   On Windows x64 with JDK 17+:
     ./build.sh
     ./package-app.sh
   Output: dist/${APP_NAME}-${VERSION}-windows-x64.zip  (folder with SODAviewer.exe)
   Or trigger GitHub Actions workflow "Release bundles" (windows-2022 matrix).

Compatible SODA: soda_version 1.0.0 / schema 1.0.0 (iCellR2 2.0.5, iCellPy 0.2.x)
EOF

echo "==> zip ${OUT_ZIP}..."
(
  cd "$DIST"
  # Prefer zip; fall back to ditto
  if command -v zip >/dev/null; then
    rm -f "$(basename "$OUT_ZIP")"
    zip -qr "$(basename "$OUT_ZIP")" "$(basename "$STAGE")"
  else
    ditto -c -k --sequesterRsrc --keepParent "$(basename "$STAGE")" "$(basename "$OUT_ZIP")"
  fi
)

du -sh "$STAGE" "$OUT_ZIP" "$DIST/${APP_NAME}-${VERSION}.jar"
echo
echo "Windows portable: $OUT_ZIP"
echo "Universal JAR:    $DIST/${APP_NAME}-${VERSION}.jar"
echo "Notes:            $DIST/WINDOWS-BUILD.txt"
echo "Run on Windows: unzip → SODAviewer.bat"
