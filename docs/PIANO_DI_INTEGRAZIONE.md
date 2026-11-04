# Piano di integrazione «Minecraft Universal Bridge» nell'app esistente

Regole che guidano tutto il lavoro (dalla direttiva):

- **Una sola app**, stesso package (`com.mmi.modinstaller`), stessa firma, stessa interfaccia, un solo archivio dati centrale.
- Niente da ricreare: l'importazione/analisi/preparazione Bedrock resta com'è; i nuovi motori la **riusano** (`FileInbox`, `PathSafety`, `SafetyLimits`, `BackupManager`, `Json`, `Nbt`, `LibraryStore`).
- Ogni fase: compilare → testare → provare l'emulatore (regressioni) → correggere → tag di backup funzionante.
- **Nessuna funzione finta.** Una schermata il cui motore non esiste dice «non ancora disponibile» e non ha pulsanti che fingono.

## Backup e ripristino

- Prima di toccare il codice: ramo **`backup/bedrock-installer-stable`** sul commit verificato (`ab9d7f1`, build 5), già su GitHub, e **Release `build-5`** con l'APK di quella versione (è anche la versione di partenza della prova di aggiornamento in CI). Il tag `backup/bedrock-installer-v1` esiste solo nella copia di lavoro: dalla sessione di sviluppo GitHub rifiuta la pubblicazione dei tag, quindi il punto di ripristino sul sito è il ramo.
- Dopo ogni fase verificata: il ramo `backup/bedrock-installer-stable` viene spostato sull'ultimo commit verificato e la Release `build-N` conserva l'APK.
- I dati dell'utente (`library.json`, `prepared/`, `backups/`) non vengono migrati né rinominati: il file viene solo **esteso** (nuove sezioni, lettura retrocompatibile, sezioni sconosciute conservate).

## Mappa dei requisiti: cosa è realizzabile e come lo dichiariamo

| Requisito | Esito previsto | Verifica |
|---|---|---|
| Dashboard a 11 sezioni, un'unica UI | Realizzato (fase 1) | emulatore |
| Archivio centrale esteso, impostazioni, registro errori | Realizzato (fase 1) | test JVM + emulatore |
| Diagnostica: dispositivo (RAM, CPU, spazio, temperatura), integrità dei dati dell'app, ultimi errori | Realizzato (fase 1) | test JVM + emulatore |
| Download Manager: coda, ripresa (Range), SHA-1/256/512, host consentiti, annullamento | Realizzato (fase 2) | test JVM con server HTTP locale |
| Analisi JAR **senza eseguire codice**: Fabric, Quilt, Forge, NeoForge, mcmod.info, OptiFine, resource pack, shader, datapack | Realizzato (fase 3) | test JVM con JAR sintetici |
| Mod Manager a profili indipendenti: importazione (anche multipla/ZIP), SHA-256, dipendenze mancanti, conflitti, backup e annullamento, attiva/disattiva | Realizzato (fase 3) | test JVM + emulatore |
| Ricerca e installazione da Modrinth (API ufficiale) | Realizzato (fase 3), prova in rete solo su dispositivo | test con risposte registrate; **non verificabile dal server di sviluppo** (rete bloccata) |
| CurseForge (ricerca/scarico mod) | **Non attivo**: l'API richiede una chiave personale; i modpack CurseForge si leggono e si installano solo per la parte che non richiede l'API | dichiarato nell'app |
| Modpack Modrinth `.mrpack` e CurseForge `manifest.json`: lettura, struttura, overrides, download con verifica, ripresa, profilo indipendente | Realizzato (fase 4) | test JVM con modpack sintetici |
| Mondi Java/Bedrock: riconoscimento (`level.dat`, `region`, `entities`, `poi`, `DIM-1`, `DIM1`, dimensioni personalizzate, `playerdata`, `datapacks`…), importazione **a flusso** (nessun mondo in RAM), checkpoint e ripresa, copia originale immutabile, avanzamento, pulizia | Realizzato (fase 5) | test JVM (anche migliaia di regioni, interruzione e ripresa) + emulatore |
| Conversione mondi Java→Bedrock | **Fase 6, parziale**: analisi di cosa si converte e cosa no, mappa dei blocchi, rapporto. La conversione completa (Anvil → LevelDB Bedrock) è un progetto a sé: Chunker è un programma Java e su Android servirebbe un runtime Java; la scrittura di LevelDB non esiste nel progetto | dichiarato nell'app; nessun «convertito» senza test |
| Conversione mod Java→Bedrock | **Fase 7, parziale**: estrazione statica delle funzioni (blocchi, oggetti, ricette, loot, lingue, texture) e classificazione (Compatibile / parziale / non convertibile). Il comportamento in codice Java **non** si converte in Script API | rapporto onesto, nessun codice finto |
| Runtime Java ARM64 integrato (Forge/Fabric/NeoForge/Quilt, OpenGL/ANGLE/GL4ES/Zink) | **Fuori da questa fase**: richiede NDK/JNI, librerie native per ABI, livello di traduzione grafica, accesso a un account Minecraft Java acquistato e un dispositivo reale; non è verificabile su emulatore CI. Fase 8: requisiti Java per versione, profili RAM, rilevamento dei launcher esterni (PojavLauncher e simili), esportazione del profilo | dichiarato nell'app |
| Prestazioni: RAM, CPU, GPU, temperatura, profili | Rilevamento reale (fase 1/8); **allocazione dinamica e controllo FPS applicabili solo con un runtime** | — |
| Multilingua | Interfaccia italiana; migrazione dei testi in risorse = lavoro separato, non ancora fatto | dichiarato |

## Fasi

### Fase 0 — Analisi, backup, piano
`docs/ANALISI_PROGETTO.md`, questo piano, tag di backup. *Uscita:* build 5 verificata (134 test + emulatore).

### Fase 1 — Dashboard e fondamenta
- `Section` (11 voci) e menu laterale; HOME è la dashboard con dati reali (Minecraft Bedrock trovato, contenuti gestiti, RAM e spazio liberi, spazio usato dall'app, stato del runtime Java, errori registrati). Le voci su mod, modpack e mondi compariranno quando esisteranno i rispettivi motori.
- INSTALLER BEDROCK = tutta l'interfaccia di prima, invariata (stesse schermate, stessi pulsanti); «Indietro» dalla sua home torna alla dashboard.
- `LibraryStore` esteso: sezioni generiche conservate al salvataggio (`DocumentStore`), registro errori, impostazioni; il file resta lo stesso e le versioni precedenti dell'app lo leggono ancora.
- DIAGNOSTICA (telefono, spazio, controllo dei file preparati, ultimi errori, arresti inattesi, rapporto da condividere) e IMPOSTAZIONI (tema, profilo prestazioni, manutenzione) reali. JAVA EDITION: requisiti e memoria consigliata, senza promettere un runtime. Le altre sezioni mostrano il loro stato vero.
- *Accettazione:* tutti i test precedenti verdi; la prova su emulatore ripete l'installazione Bedrock dalla nuova navigazione, controlla le sezioni, il tema e le impostazioni dopo il riavvio, un arresto provocato di proposito e l'**aggiornamento sopra la build 5** senza perdita di dati.

### Fase 2 — `:download-engine`
Modulo Kotlin puro (come `:core`, testabile sulla JVM) + sezione DOWNLOAD MANAGER dell'app.
- *Regole:* solo `https://` sulla porta 443; niente nome utente nell'indirizzo, IP o nomi locali; solo host consentiti (elenco noto
  + approvati dall'utente, uno per uno); ogni reindirizzamento è ricontrollato e un host non approvato ferma il download **prima**
  di contattarlo (stato «In pausa» con la domanda di consenso).
- *Trasferimento:* file parziale `<id>.part`; ripresa con `Range` + `If-Range` solo con ETag forte o `Last-Modified`; se il server
  ignora l'intervallo o il file è cambiato si riparte da zero; `Accept-Encoding: identity`; limite 2 GiB; controllo dello spazio
  libero; SHA-256 sempre calcolato e impronta attesa (SHA-1/256/512) verificata: se non corrisponde il file **non** viene tenuto.
- *Coda:* massimo 2 in parallelo, pausa/riprendi/annulla/riprova/elimina, nuovi tentativi automatici (1 s, 3 s, 8 s) solo per errori
  passeggeri (rete, 5xx, 408, 429). Niente parte da solo all'avvio dell'app: un download interrotto resta «In pausa».
- *Dati:* sezioni `downloads` e `download_hosts` dell'archivio centrale (`LibraryStore`); i file in `files/downloads/`.
- *Permesso:* `INTERNET` dichiarato e spiegato (Impostazioni, Informazioni, README); nessun file dell'utente viene mai inviato.
  La versione normale non ammette traffico non cifrato (controllo del manifest in CI); solo la versione di prova ammette `http`
  verso `10.0.2.2:8765` per la prova su emulatore.
- *Limiti dichiarati:* scarica solo finché il processo vive (nessun servizio in primo piano: da aggiungere se serve); la ripresa
  richiede ETag/Last-Modified; **Internet vero non è verificabile dalla CI** (si prova contro un server locale).
- *Accettazione:* 62 test JVM contro un vero server HTTP locale; prova su emulatore con `ci/dl_server.py` (download con impronta
  giusta e sbagliata, 404, reindirizzamento, pausa/ripresa con controllo del `Range` richiesto, rete che cade, consenso a un sito
  nuovo, chiusura dell'app a metà, apertura del file scaricato nell'INSTALLER BEDROCK) e tutte le prove precedenti invariate.

### Fase 3 — `:jar-analyzer` e `:mod-manager`
Profili Java indipendenti (`files/java/profiles/<id>/`), importazione `.jar`/`.zip`/multipla, riconoscimento di versione Minecraft e loader dai metadati, dipendenze mancanti e conflitti noti, SHA-256, backup prima di ogni modifica, annulla, attiva/disattiva, ricerca Modrinth.

### Fase 4 — `:modpack-engine`
`.mrpack` e `manifest.json` CurseForge → piano di installazione → profilo indipendente → file e overrides al posto giusto → download verificati e ripresi.

### Fase 5 — `:world-importer`
Riconoscimento Java/Bedrock, importazione a flusso da cartella (SAF) o archivio (ZIP64, `.mcworld`), checkpoint e ripresa, copia originale immutabile, report dei dati non supportati.

### Fasi 6–8 — conversione, analisi mod, runtime
Come da tabella: si fa ciò che è reale e verificabile, il resto è dichiarato.

### Fase 9–10 — diagnostica avanzata, rifinitura
Analisi dei log di crash, controlli prima dell'avvio, README, APK finale.

## Come si verifica ogni fase
1. Test JVM (`:core` e moduli nuovi) — in locale e in CI.
2. Compilazione APK in CI (`assembleDebug`/`assembleRelease`).
3. Prova su emulatore Android: navigazione, importazione Bedrock (regressione), importazione di file Java di prova, schermate nuove (schermate e log salvati nella Release).
4. Quello che nessuna prova automatica può confermare (Minecraft vero, rete reale, dispositivo fisico) viene detto esplicitamente nel resoconto.
