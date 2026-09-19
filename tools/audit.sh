#!/bin/bash
# Run the fairness + regression audit against the code that actually ships.
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KT="${DAWNPATROL_KT:-$HOME/.dawnpatrol-kt}"
O=$(mktemp -d)
"$ROOT/tools/build.sh" "$O" "$ROOT/tools/Audit.kt"
java -cp "$O:$KT/kotlin-stdlib.jar" com.dawnpatrol.game.Audit
