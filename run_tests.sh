#!/usr/bin/env bash
# Compiles the server and the tests, then runs the whole test suite.
# Needs JDK 21+ (javac and java on the PATH). Exits non-zero if any test fails.
set -euo pipefail
cd "$(dirname "$0")"

echo "Compiling server and tests..."
rm -rf out
javac -d out src/*.java test/*.java

echo "Running tests..."
java -cp out TestRunner "$@"
