# Analisi del progetto esistente (prima dell'integrazione «Universal Bridge»)

Stato analizzato: commit `5074fdb` + correzione ZIP-Windows (build 5). Tutti i numeri sotto sono stati contati sul codice.

## 1. Architettura attuale

| Modulo | Tipo | Contenuto | Righe |
|---|---|---|---|
| `:core` | Kotlin/JVM puro, Java 8 (`-Xjdk-release=8`) | lettura archivi, sicurezza, manifest Bedrock, NBT, analisi, preparazione pacchetti, backup, archivio dati, piano di lancio | 3 733 (+ 2 140 di test) |
| `:app` | Android (Compose, Material 3, MVVM) | UI, `MainViewModel`, accesso ai file (SAF), FileProvider, lettori 7z/RAR, ponte verso Minecraft | 3 957 (+ 547 di test) |
| `ci/` | script | file di prova, finto Minecraft, prova su emulatore | 807 |

- Versioni: AGP 8.7.3, Kotlin 2.0.21, Gradle 8.11.1, JDK 17, Compose BOM 2024.12.01, compileSdk/targetSdk 35, minSdk 26.
- Package: `com.mmi.modinstaller` (non va cambiato). Firma fissa in `signing/release.keystore`: ogni build si installa sopra la precedente, quindi **dati e impostazioni dell'utente restano**.
- Dipendenze: AndroidX core/activity/lifecycle, Compose (ui, foundation, material3, icons-core), coroutines, commons-compress + xz (7z), junrar (RAR4), slf4j-nop. Nessuna libreria di rete, database o WorkManager.
- Pattern: MVVM con un solo `MainViewModel` (740 righe) e uno `UiState` immutabile (`StateFlow`); navigazione con un `enum Screen` (HOME, REPORT, RECENT, INSTALLED, BACKUPS, HISTORY, ABOUT); lavori lunghi in `runBusy` (coroutine, annullabili, con avanzamento) mostrati da un overlay.

## 2. Funzionalità già funzionanti (da non rompere)

Verificate da 134 test del motore + 31 dell'app + prova su emulatore in CI:

1. Scelta file con il selettore di sistema (SAF), «Apri con», «Condividi», selezione multipla; copia di lavoro nello spazio privato (`FileInbox`), limiti di dimensione e di spazio libero.
2. Analisi vera di `.mcpack/.mcaddon/.mcworld/.mctemplate/.zip/.rar(4)/.7z/.json`: integrità (CRC), password, zip-bomb, ZIP Slip, manifest, UUID, dipendenze (anche tra contenuti dello stesso file), versioni minime, mondi (`level.dat`), duplicati, cartelle di troppo, archivi annidati.
3. Preparazione del pacchetto: ricostruzione ZIP pulita, unione di più pacchetti in un solo `.mcaddon`, conteggio di ogni file, riverifica del risultato (ZIP «alla Windows» incluso).
4. Consegna a Minecraft Bedrock (Intent + FileProvider), stato «Inviato – da confermare» deciso solo dall'utente.
5. Elenchi persistenti: file recenti, contenuti gestiti, cronologia, backup prima di eliminare, annulla.
6. Rapporto dettagliato in italiano, anteprime, ricerca.

## 3. Schermate e componenti riutilizzabili

- `ui/Components.kt` (pulsanti, schede, intestazioni), `ui/Format.kt` (testi, dimensioni, date), `ui/Theme.kt` (Material 3 scuro/chiaro), `ui/Dialogs.kt`, overlay di avanzamento in `MmiApp.kt`.
- `core`: `PathSafety` (percorsi sicuri), `SafetyLimits`/`BoundedInputStream` (limiti sui byte realmente letti), `Json` (parser/serializzatore), `Nbt`, `McVersion`, `BackupManager`, `ArchiveReader/Opener`, `FormatSniffer`, `LibraryStore` (scrittura atomica), `formatBytes`.
- `data/`: `FileInbox` (SAF → copia di lavoro), `AppPaths`, `ErrorMessages`, `MinecraftBridge`.

## 4. Persistenza

Un solo archivio centrale: `files/library/library.json` (scritto in modo atomico, file illeggibile messo da parte e ripartenza pulita). Cartelle private: `prepared/`, `backups/`, cache `sessions/` (svuotata a ogni avvio). Nessuna scrittura fuori dallo spazio privato.

**Limite da gestire:** `LibraryStore.save()` riscrive solo le sue tre sezioni; chi aggiungesse altre sezioni nello stesso file le perderebbe al salvataggio successivo → l'estensione va fatta dentro `LibraryStore` (vedi piano).

## 5. Conflitti, rischi e vincoli individuati

- **Rete:** il manifest dichiara «nessun accesso a Internet» (e il README lo promette). Download Manager, Modrinth e modpack richiedono `INTERNET`: va introdotto in modo esplicito, solo su azione dell'utente, con elenco di host consentiti e senza mai inviare file dell'utente.
- **Java 8 nei moduli puri:** `:core` compila contro l'API di Java 8 per girare su Android 8. I nuovi moduli JVM seguono la stessa regola (niente `readAllBytes`, `Path.of`, ecc.).
- **Repository Gradle:** `FAIL_ON_PROJECT_REPOS` e solo Google/Maven Central: ogni libreria nuova deve esserci. Preferibile usare solo JDK/Android per i motori (nessun conflitto di versioni possibile).
- **Un solo ViewModel da 740 righe:** non va gonfiato; i nuovi motori hanno ViewModel propri, `MainViewModel` resta il motore del flusso Bedrock.
- **Dimensioni:** `SafetyLimits.maxArchiveBytes` = 2 GB vale per le mod Bedrock; i mondi enormi hanno bisogno di un percorso a flusso con limiti propri (spazio libero).
- **Lingua:** i testi dell'interfaccia sono in italiano nel codice Kotlin (non in `strings.xml`): il «supporto multilingua» richiede una migrazione dei testi, non ancora fatta.
- **Test locali:** nell'ambiente di sviluppo non c'è l'SDK Android; i moduli JVM si compilano e testano qui, il codice Android si compila e prova solo su GitHub Actions (compilazione + emulatore).

## 6. Cosa NON esiste ancora (e non va simulato)

Runtime Java, Mod Manager, Modpack, importazione/conversione di mondi Java, analisi JAR, Download Manager, diagnostica e impostazioni. Fino a quando un motore non è completato e verificato, la sua schermata lo dice apertamente.
