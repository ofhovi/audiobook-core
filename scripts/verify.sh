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
if [ ! -f "$JAR" ] || [ -n "$(find kotlin/common kotlin/jvm kotlin/jvmtest -name '*.kt' -newer "$JAR" 2>/dev/null)" ]; then
  echo "==> Compiling Kotlin port"
  set +e
  "$KOTLINC" kotlin/common/*.kt kotlin/jvm/*.kt kotlin/jvmtest/*.kt -include-runtime -d "$JAR" > "$BUILD/kotlinc.log" 2>&1
  KOTLINC_STATUS=$?
  set -e
  grep -v '^warning:' "$BUILD/kotlinc.log" || true
  # A failed compile must not fall through to running a stale jar as if it were current.
  if [ "$KOTLINC_STATUS" -ne 0 ]; then
    echo "==> Kotlin compilation failed"
    exit 1
  fi
fi

echo "==> Sync merge tests"
java -cp "$JAR" audiobook.core.jvm.JvmSyncTestKt

echo "==> Running Kotlin port"
FILES=$(find "$FX" -type f ! -name '*.json' ! -name '*.tsv' ! -name '*.txt' ! -name '*.jpg' ! -name '*.png' | sort)
java -cp "$JAR" audiobook.core.jvm.JvmSupportKt $FILES > "$FX/kt.tsv"
java -cp "$JAR" audiobook.core.jvm.HashMain     $FILES > "$FX/kt_hash.tsv"

echo "==> Comparing"
python3 reference/crosscheck.py --compare "$FX"
