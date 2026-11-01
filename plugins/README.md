# WASM plugin examples

This directory contains equivalent C and TeaVM plugin examples. Both
toolchains produce WebAssembly modules for the same binary ABI; the
application does not depend on the plugin compiler.

Build the binaries and refresh the application test fixtures from the
repository root:

```shell
sh plugins/build.sh
```

The script builds both toolchains and produces:

```text
plugins/target/c/multiply.wasm
plugins/target/c/sum.wasm
plugins/target/c/diff.wasm
plugins/target/teavm/multiply.wasm
plugins/target/teavm/sum.wasm
plugins/target/teavm/diff.wasm
```

The C build requires Clang with `wasm-ld`. The TeaVM build uses the repository
Maven wrapper and TeaVM 0.13.0. Build-specific details are documented in
[`c/README.md`](c/README.md) and [`teavm/README.md`](teavm/README.md).

## Shared binary contract

Each generated module exports exactly:

- `memory`: the module memory used for input and output;
- `alloc(i32) -> i32`: returns the address where the host writes the input;
- one operation entry point with signature `(i32, i32) -> i64`.

The operation entry point receives the input pointer and input length. Its
returned `i64` packs the output pointer in the lower 32 bits and the output
length in the upper 32 bits:

```text
packed = (outputLength << 32) | outputPointer
```

The host passes the request directly as UTF-8 JSON bytes. There is no common
`abiVersion`/`input` envelope added by the host.

## Input and output of these examples

The three example plugins accept this JSON object:

```json
{"factor1":6,"factor2":7}
```

Both `factor1` and `factor2` must be signed 64-bit integers. The fields must be
present exactly once. Whitespace is allowed around JSON tokens, but these
examples do not implement a general JSON parser and do not accept arbitrary
JSON types or nested objects.

Each generated file exposes a different operation. The C and TeaVM variants
have the same operation names:

| File | Exported method | Operation |
|---|---|---|
| `multiply.wasm` | `multiply` | `factor1 * factor2` |
| `sum.wasm` | `sum` | `factor1 + factor2` |
| `diff.wasm` | `diff` | `factor1 - factor2` |

Successful output from these examples is:

```json
{"abiVersion":1,"result":{"result":42}}
```

The `abiVersion`/`result` object is an **example-plugin envelope**, produced by
these C modules. It is not required by the host ABI and is not interpreted by
the service. On invalid input or arithmetic overflow, the examples return:

```json
{"abiVersion":1,"error":"invalid or overflowing factors"}
```

The service treats that JSON as plugin output. It does not convert it into an
HTTP error; application-specific error handling belongs to the caller or to the
plugin-specific contract.

## Building and using an example

After running the build script, choose either toolchain, upload a module and
invoke its exported method:

```shell
curl -X POST http://localhost:8080/api/v1/plugin/multiply \
  -H 'Content-Type: application/wasm' \
  --data-binary @plugins/target/c/multiply.wasm

curl -X POST http://localhost:8080/api/v1/plugin/multiply/multiply \
  -H 'Content-Type: application/json' \
  -d '{"factor1":6,"factor2":7}'
```

The REST response contains the plugin output unchanged inside the gateway
response:

```json
{
  "plugin": "multiply",
  "method": "multiply",
  "result": {
    "abiVersion": 1,
    "result": {
      "result": 42
    }
  }
}
```

The TeaVM variant can be uploaded by replacing `target/c` with
`target/teavm` in the upload command. The plugin name in the URL is independent
of the file name and can be chosen by the caller.

## Security

Do not compile untrusted code on a production server. Build plugins in a
controlled environment and upload only trusted WASM modules. Use appropriate
authentication and authorization when exposing the upload endpoint beyond
local development.
