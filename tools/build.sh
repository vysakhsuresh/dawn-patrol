#!/bin/bash
# Compile the pure (android-free) game layers plus a tool, off-device.
#   tools/build.sh <OutputDir> <ToolFile.kt>
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KT="${DAWNPATROL_KT:-$HOME/.dawnpatrol-kt}"
[ -f "$KT/kotlin-compiler.jar" ] || { echo "run tools/kotlinc-setup.sh first"; exit 1; }
CP="$KT/kotlin-compiler.jar:$KT/kotlin-stdlib.jar:$KT/kotlin-reflect.jar:$KT/kotlin-script-runtime.jar:$KT/kotlin-daemon-embeddable.jar:$KT/trove4j.jar:$KT/annotations.jar"
OUT="$1"; shift
java -Xmx2g -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -cp "$KT/kotlin-stdlib.jar" -jvm-target 17 \
  "$ROOT"/app/src/main/java/com/dawnpatrol/game/{Art,World,Tune,Fb,Sim,Renderer,Warmup}.kt \
  "$@" -d "$OUT" 2>&1 | grep -v "^warning:" || true
