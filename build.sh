#!/usr/bin/env bash
# Build SODAviewer.jar using bundled JDK (+ Maven when network works).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="$ROOT/runtime/jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$ROOT/runtime/maven/bin:$PATH"
cd "$ROOT"

build_local() {
  echo "Building with bundled javac (offline)…"
  mkdir -p lib target/classes target/fat
  if ! ls lib/jhdf-*.jar >/dev/null 2>&1; then
    echo "Missing lib/jhdf-*.jar — download deps first:" >&2
    echo "  See README or re-run agent setup." >&2
    exit 1
  fi
  if ! ls lib/javafx/javafx-web-*.jar >/dev/null 2>&1; then
    echo "Missing lib/javafx/*.jar (JavaFX WebView for index.html)." >&2
    exit 1
  fi
  CP="$(echo lib/*.jar | tr ' ' ':')"
  JFX="$(echo lib/javafx/javafx-*.jar | tr ' ' ':')"
  javac --release 17 \
    --module-path "$JFX" \
    --add-modules javafx.controls,javafx.web,javafx.swing \
    -cp "$CP" \
    -d target/classes \
    src/main/java/sodaviewer/*.java
  # Bundle welcome watercolor + other classpath resources
  if [[ -d src/main/resources ]]; then
    cp -R src/main/resources/. target/classes/
  fi
  rm -rf target/fat
  mkdir -p target/fat
  (
    cd target/fat
    for j in "$ROOT"/lib/*.jar; do jar xf "$j"; done
    rm -f META-INF/*.SF META-INF/*.DSA META-INF/*.RSA 2>/dev/null || true
    cp -R "$ROOT/target/classes/"* .
    mkdir -p META-INF
    APP_VER="$(tr -d '[:space:]' < "$ROOT/VERSION" 2>/dev/null || echo 0.2.4)"
    printf 'Manifest-Version: 1.0\nMain-Class: sodaviewer.Main\nImplementation-Title: SODAviewer\nImplementation-Version: %s\n\n' "$APP_VER" > META-INF/MANIFEST.MF
    jar cfm ../SODAviewer.jar META-INF/MANIFEST.MF .
  )
  ls -lh target/SODAviewer.jar
}

# Prefer offline local build when platform JavaFX jars are present
# (Maven needs OS classifiers for javafx-* natives).
if ls lib/javafx/javafx-web-*.jar >/dev/null 2>&1; then
  build_local
  exit 0
fi
if command -v mvn >/dev/null 2>&1; then
  if mvn -DskipTests package "$@"; then
    ls -lh target/SODAviewer.jar
    exit 0
  fi
  echo "Maven failed (often network/DNS). Falling back to local build…"
fi
build_local
