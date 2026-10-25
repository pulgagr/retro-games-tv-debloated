#!/bin/sh
# Usage: tools/smbtest.sh <host> <share> <path>...
cd "$(dirname "$0")/.."
export PATH="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}/bin:$PATH"
mkdir -p build/smbtest
javac -d build/smbtest app/src/com/example/retro/Smb.java tools/SmbTest.java && java -cp build/smbtest SmbTest "$@"
