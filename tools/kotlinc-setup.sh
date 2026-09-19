#!/bin/bash
# Fetches a standalone Kotlin compiler and builds Java stubs for the handful
# of android.* classes the app touches, so the pure-Kotlin layers (Art,
# World, Tune, Fb, Sim, Renderer) can be COMPILED AND RUN off-device.
#
# This exists because the Android SDK cannot always be reached from a build
# sandbox, and because "it compiles" and "the fairness proof passes" are
# things you want to know without an emulator in the loop.
set -e
DIR="${1:-$HOME/.dawnpatrol-kt}"
mkdir -p "$DIR"; cd "$DIR"
V=1.9.24
fetch() { [ -f "$2" ] || curl -sSL -o "$2" "$1"; }
for a in kotlin-compiler kotlin-stdlib kotlin-reflect kotlin-script-runtime kotlin-daemon-embeddable; do
  fetch "https://repo1.maven.org/maven2/org/jetbrains/kotlin/$a/$V/$a-$V.jar" "$a.jar"
done
fetch "https://repo1.maven.org/maven2/org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar" trove4j.jar
fetch "https://repo1.maven.org/maven2/org/jetbrains/annotations/24.1.0/annotations-24.1.0.jar" annotations.jar
echo "kotlin toolchain ready in $DIR"
