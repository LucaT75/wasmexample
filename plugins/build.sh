#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

sh "$root/plugins/c/build.sh"
"$root/mvnw" -f "$root/plugins/teavm/pom.xml" package

mkdir -p "$root/plugins/target/teavm"
cp "$root/plugins/teavm/target/multiply.wasm" "$root/plugins/target/teavm/multiply.wasm"
cp "$root/plugins/teavm/target/sum.wasm" "$root/plugins/target/teavm/sum.wasm"
cp "$root/plugins/teavm/target/diff.wasm" "$root/plugins/target/teavm/diff.wasm"
