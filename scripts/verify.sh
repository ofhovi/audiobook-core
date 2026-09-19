#!/usr/bin/env bash
# The Python reference and the Kotlin port must agree. Run before any commit
# touching either parser. Exits non-zero on divergence.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
BUILD="${BUILD_DIR:-$ROOT/.build}"
FX="$BUILD/fx"

need() { command -v "$1" >/dev/null 2>&1 || { echo "missing: $1"; exit 1; }; }
need ffmpeg; need python3

echo "==> Python reference tests"
python3 -m pytest reference/test_mp4_audiobook.py -q

echo "==> Building fixtures"
mkdir -p "$BUILD"
python3 reference/crosscheck.py --fixtures "$FX"

KOTLINC="${KOTLINC:-$(command -v kotlinc || true)}"
if [ -z "$KOTLINC" ]; then
  echo "==> kotlinc not found; skipping cross-check"
  echo "    get it from https://github.com/JetBrains/kotlin/releases"
  exit 0
fi

JAR="$BUILD/ab.jar"
if [ ! -f "$JAR" ] || [ -n "$(find kotlin/src -name '*.kt' -newer "$JAR" 2>/dev/null)" ]; then
  echo "==> Compiling Kotlin port"
  "$KOTLINC" kotlin/src/*.kt -include-runtime -d "$JAR" 2>&1 | grep -v '^warning:' || true
fi

echo "==> Running Kotlin port"
FILES=$(find "$FX" -type f ! -name '*.json' ! -name '*.tsv' ! -name '*.txt' | sort)
java -cp "$JAR" audiobook.core.jvm.JvmSupportKt $FILES > "$FX/kt.tsv"
java -cp "$JAR" audiobook.core.jvm.HashMain     $FILES > "$FX/kt_hash.tsv"

echo "==> Comparing"
python3 reference/crosscheck.py --compare "$FX"
