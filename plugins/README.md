# plugins/

Questo modulo è uno scaffolding per compilare plugin Java in WebAssembly con TeaVM.

Obiettivo
- Avere una cartella `plugins/` dove aggiungere servizi (plugin) in Java
- Con `mvn -f plugins/pom.xml package` viene generato un WASM distinto per ogni plugin configurato
- Il POM attuale genera `plugins/target/multiply.wasm` e `plugins/target/sum.wasm`
- TeaVM genera anche un companion JavaScript per ciascun WASM

Struttura
- plugins/
  - pom.xml  (configurazione TeaVM)
  - src/main/java/... (un plugin Java per modulo WASM)

Esempio rapido
1. Aprire la cartella `plugins/` come progetto Maven.
2. Implementare un plugin Java (vedi `MultiplyPlugin.java` di esempio).
3. Eseguire:
   mvn -f plugins/pom.xml package
4. I WebAssembly risultanti si troveranno in `plugins/target/`, uno per ciascuna
   esecuzione TeaVM dichiarata nel POM.

Interfaccia (requisiti gateway)
Il gateway esistente in questo repository si aspetta che il modulo WASM esponga almeno:
- una funzione `alloc(i32) -> i32` che alloca memoria per l'input
- una funzione pubblica (es: `multiply`) che prende `(inputPointer: i32, inputLength: i32)` e restituisce un `i64` dove i 32 bit meno significativi sono il puntatore di output e i 32 bit più significativi la lunghezza
- l'output deve essere un JSON UTF-8 valido

Il plugin `MultiplyPlugin` riceve un oggetto JSON con due fattori interi, ad esempio
`{"factor1":6,"factor2":7}`, e restituisce `{"result":42}`. Il metodo `multiply`
impacchetta puntatore e lunghezza dell'output nel valore `i64` richiesto dal gateway.

Il test d'integrazione carica entrambi i WASM generati da TeaVM. Per eseguirli,
compila prima il modulo `plugins`:

```shell
./mvnw -f plugins/pom.xml package
./mvnw test
```

Esempio d'uso con il gateway
- Dopo la compilazione copia il file `.wasm` nel posto dove vuoi conservarlo (o caricalo tramite l'API del gateway di questo progetto usando il servizio `uploadCustomerService`).

Sicurezza
Non compilare codice non attendibile sul server di produzione. Per lo sviluppo locale è sensato compilare i plugin sul proprio PC e caricare il wasm risultante al gateway.
