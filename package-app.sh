#!/usr/bin/env bash
# Build IGV-style app-image with bundled JRE for the *current* OS.
# jpackage cannot cross-compile — run on macOS / Windows / Linux (or use CI).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"

# Prefer bundled Temurin if present (local Mac), else JAVA_HOME / PATH
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/jpackage" ]]; then
  if [[ -x "$ROOT/runtime/jdk/Contents/Home/bin/jpackage" ]]; then
    export JAVA_HOME="$ROOT/runtime/jdk/Contents/Home"
  elif [[ -x "$ROOT/runtime/jdk/bin/jpackage" ]]; then
    export JAVA_HOME="$ROOT/runtime/jdk"
  fi
fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$PATH"

# Prefer VERSION file / env; keep in sync with pom.xml and AppVersion.VERSION
if [[ -z "${VERSION:-}" && -f "$ROOT/VERSION" ]]; then
  VERSION="$(tr -d '[:space:]' < "$ROOT/VERSION")"
fi
VERSION="${VERSION:-0.2.1}"
APP_NAME=SODAviewer
JAR="$ROOT/target/SODAviewer.jar"
[[ -f "$JAR" ]] || { echo "Missing $JAR — run ./build.sh first"; exit 1; }
command -v jpackage >/dev/null || { echo "jpackage not found (need JDK 17+)"; exit 1; }
command -v jlink >/dev/null || { echo "jlink not found (need JDK 17+)"; exit 1; }

OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
ARCH="$(uname -m)"
case "$ARCH" in
  arm64|aarch64) ARCH_SHORT=arm64 ;;
  x86_64|amd64)  ARCH_SHORT=x64 ;;
  *)             ARCH_SHORT="$ARCH" ;;
esac

case "$OS" in
  darwin)
    PLATFORM="macos-${ARCH_SHORT}"
    IMAGE_NAME="${APP_NAME}.app"
    ;;
  linux)
    PLATFORM="linux-${ARCH_SHORT}"
    IMAGE_NAME="${APP_NAME}"
    ;;
  msys*|cygwin*|mingw*|windows*)
    PLATFORM="windows-${ARCH_SHORT}"
    IMAGE_NAME="${APP_NAME}"
    ;;
  *)
    echo "Unsupported OS: $OS"; exit 1
    ;;
esac

DIST="$ROOT/dist"
RUNTIME="$DIST/runtime-${PLATFORM}"
OUT_ZIP="$DIST/${APP_NAME}-${VERSION}-${PLATFORM}.zip"
OUT_DMG=""

rm -rf "$RUNTIME" "$DIST/$IMAGE_NAME" "$OUT_ZIP"
# Windows/Linux app-image is a directory named APP_NAME
[[ "$OS" == darwin ]] || rm -rf "$DIST/$APP_NAME"
mkdir -p "$DIST"

# Include JavaFX deps (jdk.unsupported.desktop / jdk.jsobject / …) or the app
# fails at launch with FindException when overview WebView loads.
MODULES=java.base,java.desktop,java.logging,java.xml,java.datatransfer,java.prefs,java.net.http,java.scripting,jdk.unsupported,jdk.unsupported.desktop,jdk.jsobject,jdk.xml.dom,jdk.crypto.ec

echo "==> jlink (${PLATFORM})..."
jlink \
  --add-modules "$MODULES" \
  --strip-debug \
  --no-header-files \
  --no-man-pages \
  --compress=2 \
  --output "$RUNTIME"

# Stage jar + JavaFX natives for interactive index.html overview
STAGE="$DIST/jpackage-input"
rm -rf "$STAGE"
mkdir -p "$STAGE/javafx"
cp -f "$JAR" "$STAGE/SODAviewer.jar"
cp -f "$ROOT"/lib/javafx/javafx-*.jar "$STAGE/javafx/" 2>/dev/null || {
  echo "Missing lib/javafx/*.jar — overview WebView will not work in the bundle." >&2
}

# jpackage rejects macOS --app-version when the first component is 0
# (e.g. product 0.2.0). Pass a compatible value, then restore VERSION in Info.plist.
JPACKAGE_VERSION="$VERSION"
if [[ "$OS" == darwin && "$VERSION" == 0.* ]]; then
  JPACKAGE_VERSION="${VERSION#0.}"
  echo "==> jpackage app-version ${JPACKAGE_VERSION} (product ${VERSION}; macOS rejects leading 0)"
fi

echo "==> jpackage app-image..."
jpackage \
  --type app-image \
  --name "$APP_NAME" \
  --app-version "$JPACKAGE_VERSION" \
  --input "$STAGE" \
  --main-jar SODAviewer.jar \
  --main-class sodaviewer.Main \
  --runtime-image "$RUNTIME" \
  --dest "$DIST" \
  --java-options "-Xmx4g" \
  --java-options "-Dapple.laf.useScreenMenuBar=true" \
  --java-options "-Dapple.awt.application.name=SODAviewer" \
  --java-options "--module-path \$APPDIR/javafx" \
  --java-options "--add-modules javafx.controls,javafx.web,javafx.swing" \
  --description "SODA archive viewer — overview + gene expression on UMAP / PCA"

if [[ "$OS" == darwin ]]; then
  PLIST="$DIST/$IMAGE_NAME/Contents/Info.plist"
  if [[ -f "$PLIST" ]]; then
    /usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $VERSION" "$PLIST" 2>/dev/null \
      || /usr/libexec/PlistBuddy -c "Add :CFBundleShortVersionString string $VERSION" "$PLIST"
    /usr/libexec/PlistBuddy -c "Set :CFBundleVersion $VERSION" "$PLIST" 2>/dev/null \
      || /usr/libexec/PlistBuddy -c "Add :CFBundleVersion string $VERSION" "$PLIST"
  fi
  # Clear quarantine + ad-hoc sign so Finder can launch unsigned local builds
  xattr -cr "$DIST/$IMAGE_NAME" 2>/dev/null || true
  codesign --force --deep --sign - "$DIST/$IMAGE_NAME" 2>/dev/null || true
fi

echo "==> archive ${OUT_ZIP}..."
if [[ "$OS" == darwin ]]; then
  (cd "$DIST" && ditto -c -k --sequesterRsrc --keepParent "$IMAGE_NAME" "$(basename "$OUT_ZIP")")

  # Drag-to-Applications DMG (macOS installer-style)
  OUT_DMG="$DIST/${APP_NAME}-${VERSION}-${PLATFORM}.dmg"
  DMG_STAGE="$DIST/dmg-stage"
  rm -rf "$DMG_STAGE" "$OUT_DMG"
  mkdir -p "$DMG_STAGE"
  ditto "$DIST/$IMAGE_NAME" "$DMG_STAGE/$IMAGE_NAME"
  ln -sf /Applications "$DMG_STAGE/Applications"
  echo "==> DMG ${OUT_DMG}..."
  hdiutil create \
    -volname "$APP_NAME" \
    -srcfolder "$DMG_STAGE" \
    -ov -format UDZO \
    "$OUT_DMG"
  rm -rf "$DMG_STAGE"
else
  # Linux / Windows (Git Bash): zip the app-image directory
  if command -v zip >/dev/null; then
    (cd "$DIST" && zip -qr "$(basename "$OUT_ZIP")" "$APP_NAME")
  else
    (cd "$DIST" && tar -czf "${APP_NAME}-${VERSION}-${PLATFORM}.tar.gz" "$APP_NAME")
    OUT_ZIP="$DIST/${APP_NAME}-${VERSION}-${PLATFORM}.tar.gz"
  fi
fi

# Also copy portable fat JAR (any OS with Java 17+)
cp -f "$JAR" "$DIST/${APP_NAME}-${VERSION}.jar"

if [[ -n "${OUT_DMG}" && -f "${OUT_DMG}" ]]; then
  du -sh "$DIST/$IMAGE_NAME" "$OUT_ZIP" "$OUT_DMG" 2>/dev/null \
    || du -sh "$DIST/$APP_NAME" "$OUT_ZIP" "$OUT_DMG"
else
  du -sh "$DIST/$IMAGE_NAME" "$OUT_ZIP" 2>/dev/null \
    || du -sh "$DIST/$APP_NAME" "$OUT_ZIP"
fi
echo
echo "Platform bundle: ${OUT_ZIP}"
[[ -n "${OUT_DMG}" ]] && echo "macOS installer: ${OUT_DMG}"
echo "Universal JAR:   $DIST/${APP_NAME}-${VERSION}.jar"
case "$OS" in
  darwin)
    echo "Run: open $DIST/$APP_NAME.app"
    echo "Or:  open ${OUT_DMG}  -> drag SODAviewer to Applications"
    ;;
  linux)  echo "Run: $DIST/$APP_NAME/bin/$APP_NAME" ;;
  *)      echo "Run: $DIST/$APP_NAME/$APP_NAME.exe" ;;
esac
