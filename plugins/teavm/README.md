# TeaVM plugin examples

These Java examples are compiled to WebAssembly with TeaVM 0.13.0. Build them
directly with:

```shell
./mvnw -f plugins/teavm/pom.xml package
```

The build creates `multiply.wasm`, `sum.wasm`, and `diff.wasm` under
`plugins/teavm/target/`. The top-level `plugins/build.sh` copies them to
`plugins/target/teavm/`.

Each class exports the same operation as its C counterpart and uses the same
direct JSON input and packed `i64` output convention. TeaVM may emit runtime
imports; the application executor provides the supported TeaVM host functions
documented in the main project README.

See [`../README.md`](../README.md) for the shared ABI and application payload
contract.
