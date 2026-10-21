# WASM plugin examples

The examples are freestanding C programs compiled to WebAssembly with no
imports. Clang and its `wasm-ld` linker must be installed.

Build the binaries and refresh the application test fixtures from the
repository root:

```shell
sh plugins/build.sh
```

The script produces `multiply.wasm`, `sum.wasm`, and `diff.wasm` under
`plugins/target/` and `src/test/resources/wasm/`.

## Contract

Each module exports:

- `memory`
- `alloc(i32) -> i32`
- One or more entry points with signature `(i32, i32) -> i64`

The host passes a UTF-8 JSON request envelope:

```json
{"abiVersion":1,"nowMillis":0,"input":{"factor1":6,"factor2":7}}
```

The returned `i64` packs the output pointer in its lower 32 bits and the output
length in its upper 32 bits. The output is a UTF-8 JSON object containing
`abiVersion: 1` and either `result` or `error`.

The arithmetic examples read `factor1` and `factor2` from the input object and
return the computed value in `result.result`.

## Security

Do not compile untrusted code on a production server. Build plugins in a
controlled environment and upload only trusted WASM modules. Use appropriate
authentication and authorization when exposing the upload endpoint beyond
local development.
