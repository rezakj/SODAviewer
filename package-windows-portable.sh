#!/usr/bin/env bash
# Build a Windows x64 *portable* zip from any host (including macOS/Linux CI).
#
# Ships: fat JAR + Windows OpenJFX + bundled Temurin JRE + SODAviewer.bat.
# Does not need jpackage or a Windows runner.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"

if [[ -z "${VERSION:-}" && -f "$ROOT/VERSION" ]]; then
  VERSION="$(tr -d '[:space:]' < "$ROOT/VERSION")"
fi
VERSION="${VERSION:-0.2.4}"
APP_NAME=SODAviewer
PLATFORM=windows-x64
JFX_VER="${JFX_VER:-17.0.14}"
JAR="$ROOT/target/SODAviewer.jar"

if [[ ! -f "$JAR" ]]; then
  echo "==> building fat JAR..."
  if [[ -x "$ROOT/build.sh" ]]; then
    (cd "$ROOT" && ./build.sh)
  else
    (cd "$ROOT" && mvn -B -DskipTests package)
  fi
fi
[[ -f "$JAR" ]] || { echo "Missing $JAR"; exit 1; }

DIST="$ROOT/dist"
STAGE="$DIST/${APP_NAME}-${VERSION}-${PLATFORM}"
OUT_ZIP="$DIST/${APP_NAME}-${VERSION}-${PLATFORM}.zip"
CACHE="$ROOT/dist/.cache-windows"
mkdir -p "$DIST" "$CACHE"

# --- helpers (Windows Git Bash often lacks unzip/zip/python3/du) ---------------
extract_zip() {
  local zipfile="$1" dest="$2"
  mkdir -p "$dest"
  if command -v unzip >/dev/null 2>&1; then
    unzip -q "$zipfile" -d "$dest"
  elif command -v bsdtar >/dev/null 2>&1; then
    bsdtar -xf "$zipfile" -C "$dest"
  elif command -v tar >/dev/null 2>&1 && tar -xf "$zipfile" -C "$dest" 2>/dev/null; then
    :
  elif command -v jar >/dev/null 2>&1; then
    (cd "$dest" && jar xf "$zipfile")
  else
    echo "No unzip/bsdtar/tar/jar available to extract $zipfile" >&2
    exit 127
  fi
}

make_zip() {
  local parent="$1" folder="$2" zipname="$3"
  (
    cd "$parent"
    rm -f "$zipname"
    if command -v zip >/dev/null 2>&1; then
      zip -qr "$zipname" "$folder"
    elif command -v jar >/dev/null 2>&1; then
      jar cMf "$zipname" "$folder"
    elif command -v bsdtar >/dev/null 2>&1; then
      bsdtar -a -cf "$zipname" "$folder"
    else
      echo "No zip/jar/bsdtar available to create $zipname" >&2
      exit 127
    fi
  )
}

write_bat() {
  local dest="$1"
  local PY=""
  if command -v python3 >/dev/null 2>&1; then
    PY=python3
  elif command -v python >/dev/null 2>&1; then
    PY=python
  fi
  if [[ -n "$PY" ]]; then
    "$PY" - "$dest" <<'PY'
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
  else
    # Fallback without Python: write CRLF via printf
    {
      printf '%s\r\n' '@echo off'
      printf '%s\r\n' 'setlocal'
      printf '%s\r\n' 'set "DIR=%~dp0"'
      printf '%s\r\n' 'set "JAVA=%DIR%runtime\bin\java.exe"'
      printf '%s\r\n' 'if not exist "%JAVA%" ('
      printf '%s\r\n' '  echo Bundled Java not found: %JAVA%'
      printf '%s\r\n' '  echo Install Temurin 17+ or re-download this zip.'
      printf '%s\r\n' '  exit /b 1'
      printf '%s\r\n' ')'
      printf '%s\r\n' 'set "MODPATH=%DIR%javafx"'
      printf '%s\r\n' '"%JAVA%" -Xmx4g --module-path "%MODPATH%" --add-modules javafx.controls,javafx.web,javafx.swing -jar "%DIR%SODAviewer.jar" %*'
    } > "$dest"
  fi
}

echo "==> fetch Temurin 17 Windows x64 JRE..."
JRE_ZIP="$CACHE/OpenJDK17U-jre_x64_windows.zip"
if [[ ! -f "$JRE_ZIP" ]]; then
  curl -fL --retry 3 --retry-delay 2 \
    -o "$JRE_ZIP" \
    "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jre/hotspot/normal/eclipse?project=jdk"
fi
[[ -s "$JRE_ZIP" ]] || { echo "JRE download empty: $JRE_ZIP"; exit 1; }

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
  [[ -s "$dest" ]] || { echo "Missing $dest"; exit 1; }
done

echo "==> stage ${STAGE}..."
rm -rf "$STAGE" "$OUT_ZIP"
mkdir -p "$STAGE/javafx" "$STAGE/runtime"

TMP_JRE="$CACHE/jre-unpack"
rm -rf "$TMP_JRE"
mkdir -p "$TMP_JRE"
extract_zip "$JRE_ZIP" "$TMP_JRE"
JRE_ROOT="$(find "$TMP_JRE" -maxdepth 1 -type d ! -path "$TMP_JRE" | head -1)"
[[ -n "$JRE_ROOT" && -d "$JRE_ROOT" ]] || { echo "Failed to locate JRE root in zip"; ls -la "$TMP_JRE"; exit 1; }
cp -R "$JRE_ROOT"/. "$STAGE/runtime/"

cp -f "$JAR" "$STAGE/${APP_NAME}.jar"
cp -f "$JFX_DIR"/*.jar "$STAGE/javafx/"
write_bat "$STAGE/SODAviewer.bat"

cat > "$STAGE/README-WINDOWS.txt" <<EOF
SODAviewer ${VERSION} - Windows x64 portable bundle
====================================================

Includes a Temurin 17 JRE + Windows OpenJFX + the fat JAR.

Run
---
1. Unzip anywhere (no install).
2. Double-click SODAviewer.bat
   or:  SODAviewer.bat  path\\to\\archive.soda

Requirements
------------
- Windows x64 (Intel/AMD). Not ARM64.
- No system Java required (runtime\\ is bundled).
EOF

cp -f "$JAR" "$DIST/${APP_NAME}-${VERSION}.jar"

cat > "$DIST/WINDOWS-BUILD.txt" <<EOF
SODAviewer Windows packaging
============================

A) Portable zip - package-windows-portable.sh (CI path)
   Output: dist/${APP_NAME}-${VERSION}-${PLATFORM}.zip
   Contains: SODAviewer.bat + SODAviewer.jar + javafx\\ (win) + runtime\\ (Temurin JRE)

B) Native jpackage app-image (.exe) - optional, run ON Windows with JDK 17+
     ./build.sh && ./package-app.sh
EOF

echo "==> zip ${OUT_ZIP}..."
make_zip "$DIST" "$(basename "$STAGE")" "$(basename "$OUT_ZIP")"
[[ -s "$OUT_ZIP" ]] || { echo "Zip missing/empty: $OUT_ZIP"; exit 1; }

if command -v du >/dev/null 2>&1; then
  du -sh "$STAGE" "$OUT_ZIP" "$DIST/${APP_NAME}-${VERSION}.jar"
else
  ls -lh "$OUT_ZIP" "$DIST/${APP_NAME}-${VERSION}.jar"
fi
echo
echo "Windows portable: $OUT_ZIP"
echo "Universal JAR:    $DIST/${APP_NAME}-${VERSION}.jar"
echo "Notes:            $DIST/WINDOWS-BUILD.txt"
echo "Run on Windows: unzip -> SODAviewer.bat"
