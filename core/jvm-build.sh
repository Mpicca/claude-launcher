#!/usr/bin/env bash
# Compiles :core and runs its JUnit suite using only the jars bundled with a Gradle
# distribution — no network, no Android SDK. Handy for locked-down CI boxes.
# Normal developer path: `./gradlew :core:test`.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
GRADLE_LIB="${GRADLE_LIB:-/opt/gradle-8.14.3/lib}"
OUT="${OUT:-$HERE/build/jvm}"
rm -rf "$OUT"; mkdir -p "$OUT/main" "$OUT/test"

RT="$GRADLE_LIB/kotlin-stdlib-2.0.21.jar:$GRADLE_LIB/kotlinx-coroutines-core-jvm-1.6.4.jar:$GRADLE_LIB/kotlinx-serialization-core-jvm-1.6.2.jar:$GRADLE_LIB/kotlinx-serialization-json-jvm-1.6.2.jar"
TEST_RT="$GRADLE_LIB/junit-4.13.2.jar:$GRADLE_LIB/hamcrest-core-1.3.jar"
KC="$GRADLE_LIB/kotlin-compiler-embeddable-2.0.21.jar:$GRADLE_LIB/kotlin-reflect-2.0.21.jar:$GRADLE_LIB/kotlin-script-runtime-2.0.21.jar:$GRADLE_LIB/trove4j-1.0.20200330.jar:$GRADLE_LIB/annotations-24.0.1.jar:$RT"

kotlinc() {
  java -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -nowarn "$@" 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true
}

echo "== compiling main"
kotlinc -cp "$RT" -d "$OUT/main" $(find "$HERE/src/main/kotlin" -name '*.kt')
[ -n "$(find "$OUT/main" -name '*.class' | head -1)" ] || { echo "main compile failed"; exit 1; }
echo "== compiling tests"
kotlinc -cp "$RT:$TEST_RT:$OUT/main" -d "$OUT/test" $(find "$HERE/src/test/kotlin" -name '*.kt')
[ -n "$(find "$OUT/test" -name '*Test.class' | head -1)" ] || { echo "test compile failed"; exit 1; }
echo "== running tests"
CLASSES=$(cd "$OUT/test" && find . -name '*Test.class' | sed 's#^\./##; s#\.class$##; s#/#.#g' | tr '\n' ' ')
java -cp "$RT:$TEST_RT:$OUT/main:$OUT/test" org.junit.runner.JUnitCore $CLASSES 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS'
