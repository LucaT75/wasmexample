# WASM plugins

This Maven module contains Java examples that TeaVM compiles to WebAssembly.
Each configured TeaVM execution produces a separate `.wasm` module and its
JavaScript runtime companion.

## Build

From the repository root, run:

```shell
./mvnw -f plugins/pom.xml package
```

The current build creates:

- `plugins/target/multiply.wasm`
- `plugins/target/sum.wasm`

## Create a plugin

Add a Java class under `src/main/java` and configure a TeaVM execution for its
main class in `pom.xml`. The `MultiplyPlugin` and `SumPlugin` classes are
working examples.

## Gateway contract

The gateway expects a WASM module to export:

- `alloc(i32) -> i32` to allocate memory for the input.
- A public function such as `multiply` that accepts
  `(inputPointer: i32, inputLength: i32)` and returns an `i64`.

The lower 32 bits of the returned value contain the output pointer; the upper
32 bits contain the output length. Input and output are UTF-8 encoded JSON, and
the output must be valid JSON.

The example plugins accept an object with exactly two integer fields,
`factor1` and `factor2`, and return an object with a `result` field. For
example, `{"factor1":6,"factor2":7}` produces `{"result":42}` in
`MultiplyPlugin`.

## Upload and execute a plugin

After building the WASM module and starting the application, upload it using
the plugin API:

```shell
curl -X POST http://localhost:8080/api/v1/plugin/multiply \
  -H 'Content-Type: application/wasm' \
  --data-binary @plugins/target/multiply.wasm
```

Then call an exported function:

```shell
curl -X POST http://localhost:8080/api/v1/plugin/multiply/multiply \
  -H 'Content-Type: application/json' \
  -d '{"factor1":6,"factor2":7}'
```

## Tests

The application tests load the `multiply`, `sum`, and `diff` modules from
`src/test/resources/wasm/`; they do not depend on TeaVM build output. The
standalone `diff.wasm` fixture is assembled from `diff.wat` without TeaVM. Run
the tests from the repository root:

```shell
./mvnw test
```

## Security

Do not compile untrusted code on a production server. Build plugins in a
controlled environment and upload only trusted WASM modules. Use appropriate
authentication and authorization when exposing the upload endpoint beyond
local development.
