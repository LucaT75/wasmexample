#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
source="$root/plugins/src/main/c/arithmetic_plugin.c"
target="$root/plugins/target"
fixtures="$root/src/test/resources/wasm"

mkdir -p "$target" "$fixtures"

for operation in multiply sum diff; do
    case "$operation" in
        multiply) operation_id=0 ;;
        sum) operation_id=1 ;;
        diff) operation_id=2 ;;
    esac
    clang --target=wasm32-unknown-unknown -O2 -nostdlib "$source" \
        -Wl,--no-entry \
        -Wl,--export-memory \
        -Wl,--export=alloc \
        -Wl,--export="$operation" \
        -Wl,--initial-memory=1310720 \
        -Wl,--max-memory=16777216 \
        -Wl,--strip-all \
        -DPLUGIN_OPERATION="$operation_id" \
        -o "$target/$operation.wasm"
    cp "$target/$operation.wasm" "$fixtures/$operation.wasm"
done
