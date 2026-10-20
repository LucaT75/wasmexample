# wasmexample

A Quarkus REST application that stores WebAssembly (WASM) plugins and executes
their exported functions with Chicory.

## Requirements

- Java 21 or later
- Maven (or use the included Maven wrapper)

## Build the example plugins

The `plugins` module uses TeaVM to compile the Java examples to WASM. Build it
before running the application tests:

```shell
./mvnw -f plugins/pom.xml package
```

This generates `plugins/target/multiply.wasm` and `plugins/target/sum.wasm`.

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

Send the WASM binary as the request body. The plugin name is part of the URL:

```shell
curl -X POST http://localhost:8080/api/v1/plugin/multiply \
  -H 'Content-Type: application/wasm' \
  --data-binary @plugins/target/multiply.wasm
```

The endpoint also accepts `application/octet-stream`. A successful upload
returns `204 No Content`; an empty file returns `400 Bad Request`.

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

The current example plugins accept a JSON object containing exactly two
integer fields, `factor1` and `factor2`. The `multiply` plugin multiplies them;
the `sum` plugin adds them. Requests with invalid input return `400 Bad Request`
and requests for unknown plugins return `404 Not Found`.

## WASM plugin contract

A plugin module must export:

- `alloc(i32) -> i32`, which allocates memory for the input JSON.
- The function to execute (for example, `multiply` or `sum`), with the
  signature `(inputPointer: i32, inputLength: i32) -> i64`.

The input is UTF-8 encoded JSON. The returned `i64` packs the output pointer in
its lower 32 bits and the output length in its upper 32 bits. The referenced
output bytes must contain valid UTF-8 JSON.

See [plugins/README.md](plugins/README.md) for details on creating plugins with
TeaVM.

## Tests

The gateway tests load their WASM fixtures from `src/test/resources/wasm/`.
The `diff.wasm` fixture is assembled from `diff.wat` without TeaVM. Run the
application tests with:

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
production storage.

For information about native builds, see the
[Quarkus Maven tooling guide](https://quarkus.io/guides/maven-tooling).
