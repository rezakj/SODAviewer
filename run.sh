#!/usr/bin/env bash
# Launch SODAviewer using the bundled Temurin JDK + JavaFX WebView modules.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
JAVA_HOME="${JAVA_HOME:-}"
if [[ -z "$JAVA_HOME" || ! -x "$JAVA_HOME/bin/java" ]]; then
  if [[ -x "$ROOT/runtime/jdk/Contents/Home/bin/java" ]]; then
    JAVA_HOME="$ROOT/runtime/jdk/Contents/Home"
  elif command -v java >/dev/null 2>&1; then
    JAVA_BIN="$(command -v java)"
  fi
fi
JAVA_BIN="${JAVA_BIN:-$JAVA_HOME/bin/java}"
JAR="$ROOT/target/SODAviewer.jar"
if [[ ! -f "$JAR" ]]; then
  echo "Missing $JAR — build first (see README)." >&2
  exit 1
fi
JFX="$ROOT/lib/javafx"
if [[ ! -d "$JFX" ]] || ! ls "$JFX"/javafx-web-*.jar >/dev/null 2>&1; then
  echo "Missing JavaFX jars under lib/javafx (needed for interactive index.html)." >&2
  exit 1
fi
# Resolve platform-specific jars on module path
MODPATH="$(echo "$JFX"/javafx-*.jar | tr ' ' ':')"
exec "$JAVA_BIN" \
  -Dapple.laf.useScreenMenuBar=true \
  -Dapple.awt.application.name=SODAviewer \
  --module-path "$MODPATH" \
  --add-modules javafx.controls,javafx.web,javafx.swing \
  -jar "$JAR" "$@"
