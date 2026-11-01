# C plugin examples

The C examples are freestanding modules compiled with Clang and `wasm-ld`.
Build them directly with:

```shell
sh plugins/c/build.sh
```

The script creates `plugins/target/c/multiply.wasm`, `sum.wasm`, and
`diff.wasm`. It also refreshes the C fixtures under
`src/test/resources/wasm/`.

See [`../README.md`](../README.md) for the shared ABI and application payload
contract.
