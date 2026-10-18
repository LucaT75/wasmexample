# wasmexample

This project uses Quarkus, the Supersonic Subatomic Java Framework.

If you want to learn more about Quarkus, please visit its website: <https://quarkus.io/>.

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:

```shell script
./mvnw quarkus:dev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at <http://localhost:8080/q/dev/>.

## Eseguire un metodo WASM con JSON

Il gateway riceve il nome del servizio e il nome dell'export WASM da invocare,
insieme a un body JSON:

```shell
curl -X POST http://localhost:8080/api/v1/promotion/json-multiply/multiply \
  -H 'Content-Type: application/json' \
  -d '{"factor1":6,"factor2":7}'
```

Il modulo deve esporre una funzione `alloc(i32) -> i32` che riserva memoria per
l'input. Il metodo richiesto (in questo esempio `multiply`) riceve
`(inputPointer: i32, inputLength: i32)` con il JSON codificato in UTF-8 e
restituisce un `i64`: i 32 bit meno significativi contengono il puntatore
dell'output e i 32 bit più significativi la sua lunghezza in byte. Anche
l'output deve essere JSON valido codificato in UTF-8.

Il contratto standard è un oggetto JSON con esattamente due campi interi,
`factor1` e `factor2`, e un output JSON con il campo `result`, ad esempio
`{"factor1":6,"factor2":7}` -> `{"result":42}`. Ogni classe plugin produce un WASM
distinto: `MultiplyPlugin` crea `multiply.wasm` e `SumPlugin` crea `sum.wasm`.
Entrambi vengono compilati con TeaVM prima dei test:

```shell
./mvnw -f plugins/pom.xml package
./mvnw test
```

Per creare altri plugin TeaVM guarda la cartella [plugins/](/workspaces/wasmexample/plugins/README.md).


## Packaging and running the application

The application can be packaged using:

```shell script
./mvnw package
```

It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.

The application is now runnable using `java -jar target/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:

```shell script
./mvnw package -Dquarkus.package.jar.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar target/*-runner.jar`.

## Creating a native executable

You can create a native executable using:

```shell script
./mvnw package -Dnative
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using:

```shell script
./mvnw package -Dnative -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./target/wasmexample-1.0.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult <https://quarkus.io/guides/maven-tooling>.

## Related Guides

- REST ([guide](https://quarkus.io/guides/rest)): Build RESTful web services and APIs using Jakarta REST (formerly JAX-RS)
- Chicory ([guide](https://docs.quarkiverse.io/chicory/dev/)): A Quarkus extension for Chicory, that takes care of the plumbing behind the scenes so developers can just 
focus on their applications.

- REST Jackson ([guide](https://quarkus.io/guides/rest#json-serialisation)): Jackson serialization support for Quarkus REST. This extension is not compatible with the quarkus-resteasy extension, or any of the extensions that depend on it
- Hibernate ORM with Panache ([guide](https://quarkus.io/guides/hibernate-orm-panache)): Simplified JPA/Hibernate data access layer with active record and repository patterns
- JDBC Driver - PostgreSQL ([guide](https://quarkus.io/guides/datasource)): Connect to the PostgreSQL database via JDBC

## Provided Code

### Hibernate ORM

Create your first JPA entity

[Related guide section...](https://quarkus.io/guides/hibernate-orm)


[Related Hibernate with Panache section...](https://quarkus.io/guides/hibernate-orm-panache)


### REST

Easily start your REST Web Services

[Related guide section...](https://quarkus.io/guides/getting-started-reactive#reactive-jax-rs-resources)
