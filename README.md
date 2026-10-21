# wasmexample

A Quarkus REST application that stores WebAssembly (WASM) plugins and executes
their exported functions with Chicory.

## Requirements

- Java 21 or later
- Maven (or use the included Maven wrapper)

## Build the example plugins

The examples are compiled from C to WASM with zero imports. Install Clang with
the LLVM WASM linker (`wasm-ld`), then build the examples and test fixtures:

```shell
sh plugins/build.sh
```

This generates `multiply.wasm`, `sum.wasm`, and `diff.wasm` under
`plugins/target/` and updates the corresponding test fixtures.

## Run in development mode

```shell
./mvnw quarkus:dev
```

In dev mode, Swagger UI is available at
<http://localhost:8080/q/swagger-ui/> and the OpenAPI document at
<http://localhost:8080/q/openapi>. Quarkus Dev UI is available at
<http://localhost:8080/q/dev/>.

## Plugin API

### Upload or replace a plugin

Send the WASM binary as the request body. Uploads are parsed and validated
before activation; accepted versions are retained by SHA-256 so a prior binary
can be reactivated without replacing its stored bytes. The application has one
supported plugin contract; no ABI/profile query parameter is needed:

```shell
curl -X POST http://localhost:8080/api/v1/plugin/multiply \
  -H 'Content-Type: application/wasm' \
  --data-binary @plugins/target/multiply.wasm
```

The endpoint also accepts `application/octet-stream`. A successful upload
returns `204 No Content`; invalid modules return `400 Bad Request`.

To reactivate a previously uploaded binary, use its SHA-256:

```shell
curl -X POST \
  'http://localhost:8080/api/v1/plugin/my-plugin/versions/<sha256>/activate'
```

### Execute an exported function

```shell
curl -X POST http://localhost:8080/api/v1/plugin/multiply/multiply \
  -H 'Content-Type: application/json' \
  -d '{"factor1":6,"factor2":7}'
```

The example returns a JSON object containing the plugin name, method name, and
plugin result:

```json
{
  "plugin": "multiply",
  "method": "multiply",
  "result": {
    "result": 42
  }
}
```

The current example plugins read integer `factor1` and `factor2` fields from
the JSON input. The `multiply` plugin multiplies them; the `sum` plugin adds
them. Requests for unknown plugins return `404 Not Found`.

## WASM plugin contract

The application supports one versioned contract, ABI v1. Plugins must declare
zero imports and export `memory`, `alloc(i32) -> i32`, and at least one entry
point with signature `(i32, i32) -> i64`.

The input bytes are a UTF-8 JSON envelope:

```json
{"abiVersion":1,"nowMillis":0,"input":{"factor1":6,"factor2":7}}
```

The output must be a JSON object with an `abiVersion` of `1` and either a
`result` or an `error`; `logs` are optional and length- and count-limited.
Input and output payloads are each limited to 1 MiB, execution runs in a
bounded worker pool with a two-second timeout, and each request uses a fresh
WASM instance. Timeout interruption is best-effort; a non-cooperative
interpreter can continue occupying one of the bounded workers.

A plugin module must export:

- `alloc(i32) -> i32`, which allocates memory for the input JSON.
- The function to execute (for example, `multiply` or `sum`), with the
  signature `(inputPointer: i32, inputLength: i32) -> i64`.

The input is UTF-8 encoded JSON. The returned `i64` packs the output pointer in
its lower 32 bits and the output length in its upper 32 bits. The referenced
output bytes must contain valid UTF-8 JSON.

See [plugins/README.md](plugins/README.md) for details on building zero-import
examples with Clang.

## Tests

The gateway tests load their WASM fixtures from `src/test/resources/wasm/`.
Run the application tests with:

```shell
./mvnw test
```

## Package and run

Build the application:

```shell
./mvnw package
```

Run the packaged application:

```shell
java -jar target/quarkus-app/quarkus-run.jar
```

The default configuration uses an in-memory H2 database and recreates its
schema at startup. It is intended for development and examples, not persistent
production storage. The plugin version fields and composite uniqueness
constraint require a database migration before using an existing persistent
deployment; do not use `drop-and-create` in production.

For information about native builds, see the
[Quarkus Maven tooling guide](https://quarkus.io/guides/maven-tooling).
