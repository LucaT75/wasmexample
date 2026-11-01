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
    "abiVersion": 1,
    "result": {
      "result": 42
    }
  }
}
```

The current example plugins read integer `factor1` and `factor2` fields from
the JSON input. The `multiply` plugin multiplies them; the `sum` plugin adds
them. Requests for unknown plugins return `404 Not Found`.

## WASM plugin contract

The application supports one binary contract, ABI v1. Plugins must export
`memory`, `alloc(i32) -> i32`, and at least one entry
point with signature `(i32, i32) -> i64`.

The executor supports zero-import modules and a limited TeaVM host interface:
`teavm.currentTimeMillis() -> f64`, `teavm.logString(i32)`,
`teavm.logInt(i32)`, and `teavm.logOutOfMemory()`. The clock returns current epoch
milliseconds, log functions use the application debug logger with per-instance
count and length limits, and out-of-memory reports fail execution explicitly.
String logging requires TeaVM's `teavm_stringData`, `teavm_arrayLength`, and
`teavm_charArrayData` exports. This is not a complete TeaVM runtime.
Unsupported imports or signatures are rejected on first execution, when the
executor resolves and caches the import plan. Each fresh instance receives its
own host logging state. Upload validation is unchanged.

The executor serializes the request JSON directly as UTF-8 bytes. It does not
interpret the fields or add an application envelope:

```json
{"factor1":6,"factor2":7}
```

The executor reads the returned bytes as JSON only because the REST API exposes
JSON; it does not require a particular output shape. The plugin owns the meaning
of both input and output and may return any valid JSON value. Input and output
payloads are each limited to 1 MiB, execution runs in a
bounded worker pool with a two-second timeout, and each request uses a fresh
WASM instance. Timeout interruption is best-effort; a non-cooperative
interpreter can continue occupying one of the bounded workers.

### Responsibility boundaries

The contract has two separate layers:

1. **Binary WASM ABI (mandatory):** how the service calls the module and
   exchanges byte buffers.
2. **Application payload (plugin-defined):** the meaning and structure of the
   input and output bytes.

There is **no common application envelope ABI** in the current contract. In
particular, the service does not add or require `abiVersion`, `result`, `error`,
`nowMillis`, or `input` fields.

| Responsibility | Service |                   Plugin |
|---|---:|-------------------------:|
| Binary ABI: `alloc`, pointers, lengths, packed `i64` | Calls and validates |           Must implement |
| UTF-8 JSON transport used by this REST API | Serializes and forwards |   Must parse and produce |
| Memory and input/output size limits | Enforces | Must operate within them |
| Exporting `alloc` and the requested entry point | Invokes |              Must export |
| Application input format and field meanings | Does not define |    Defines and validates |
| Application calculation | Does not perform |                 Performs |
| Application output format | Does not define or inspect |                  Defines |
| Plugin-specific envelope | Does not define |                  Defines |

A plugin may use any JSON structure, including its own envelope, and the
service forwards it without inspecting its fields. A plugin-specific error
format is therefore part of that plugin's application contract; WASM traps
remain technical execution errors handled by the service.

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
Gateway tests also generate a small ABI v1 module with TeaVM clock and diagnostic
imports, checking clock encoding, repeated execution, and explicit import errors.
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
