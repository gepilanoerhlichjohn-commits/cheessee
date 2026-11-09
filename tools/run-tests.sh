#!/usr/bin/env bash
# Compiles the pure-Java core and runs every self-test. Works anywhere with a JDK 17+.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$(mktemp -d)}"
mkdir -p "$OUT"
javac -Xlint:all --release 17 -d "$OUT" $(find "$ROOT/core/src" -name '*.java')
java -Djava.awt.headless=true -cp "$OUT" app.cheessee.tests.AllTests
