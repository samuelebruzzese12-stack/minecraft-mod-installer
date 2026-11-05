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
| Analisi dei crash di Minecraft Java (crash report e log, anche `.log.gz`) | **Parziale (fase 9a)**: riconosce dai messaggi veri di Fabric/Quilt Loader e di Forge/NeoForge le mod mancanti, le versioni sbagliate (di mod, Minecraft, caricatore), le mod incompatibili o duplicate; dalla JVM il Java sbagliato (versione delle classi) e la memoria finita; gli errori dei mixin (con la mod quando il messaggio la nomina) e i driver grafici; ritrova nei profili del MOD MANAGER i file di quelle mod. Il file si legge soltanto (inizio e fine se enorme). Non riconosce tutti i crash possibili: quando il messaggio non è tra quelli noti lo dice e mostra l'eccezione | test JVM (14 + 2 sui testi della prova) + emulatore (parte «mods», dopo il MOD MANAGER) |
| Download Manager: coda, ripresa (Range), SHA-1/256/512, host consentiti, annullamento | Realizzato (fase 2) | test JVM con server HTTP locale |
| Analisi JAR **senza eseguire codice**: Fabric, Quilt, Forge, NeoForge, mcmod.info, OptiFine, resource pack, shader, datapack | Realizzato (fase 3) | test JVM con JAR sintetici |
| Mod Manager a profili indipendenti: importazione (anche multipla/ZIP), SHA-256, dipendenze mancanti, conflitti, backup e annullamento, attiva/disattiva | **Parziale (fase 3)**: fatti profili indipendenti, importazione multipla e raccolte `.zip`, SHA-256, dipendenze mancanti **segnalate (non scaricate)**, incompatibilità dichiarate, cestino del profilo e annulla, interruttore, verifica del profilo, esportazione in ZIP. Da fare: cartella di esportazione scelta una volta, raccolte `.7z`/`.rar` | test JVM + emulatore (esito nel resoconto di ogni build) |
| Ricerca e installazione da Modrinth (API ufficiale) | **Collegato (fase 3d), verificato solo in parte**: ricerca con filtri (tipo, caricatore, versione di Minecraft), versione adatta al profilo (stabile prima, mai un file senza impronta), dipendenze obbligatorie a catena, scarico con impronta verificata, passaggio al MOD MANAGER. Non aggiorna da sola le mod, non cerca modpack | test JVM con risposte registrate, con un vero server HTTP locale e con un finto Modrinth (`ci/dl_server.py`); emulatore (parte «catalog»); contro il **servizio vero** solo il passaggio «Prova col servizio vero (Modrinth)» della CI. Il server di sviluppo non raggiunge Internet: l'esito di quel passaggio sta nel rapporto di ogni build e non va dato per scontato |
| CurseForge (ricerca/scarico mod) | **Non attivo**: l'API richiede una chiave personale. I modpack CurseForge (`manifest.json`) vengono riconosciuti dall'analisi ma **non installati**: i loro file si scaricano solo attraverso quell'API | dichiarato nell'app |
| Modpack Modrinth `.mrpack`: lettura, struttura, overrides, download con verifica, ripresa, profilo indipendente | **Parziale (fase 4)**: fatto per i `.mrpack` (anteprima prima di scaricare, consenso ai siti, impronta verificata per ogni file, overrides comuni e solo-client, file solo-server esclusi, registrazione nel MOD MANAGER, esito Completo/Incompleto onesto, ripresa che scarica solo ciò che manca, annullamento che non lascia nulla). Non fatto: modpack CurseForge, servizio in primo piano (se Android chiude l'app a metà, COMPLETA riprende) | test JVM (68 + 5 sui modpack della prova su emulatore, con il finto CDN) + emulatore (parte «modpack»: esito nel resoconto di ogni build) |
| Mondi Java/Bedrock: riconoscimento (`level.dat`, `region`, `entities`, `poi`, `DIM-1`, `DIM1`, dimensioni personalizzate, `playerdata`, `datapacks`…), importazione **a flusso** (nessun mondo in RAM), checkpoint e ripresa, copia originale immutabile, avanzamento, pulizia | **Parziale (fase 5)**: fatti riconoscimento senza estrarre (Java e Bedrock, mondi annidati o multipli), importazione dei mondi Java nella cartella `saves` di un profilo un file alla volta con diario e ripresa, controllo di CRC e spazio, pulizia all'annullamento, avviso di versione, esportazione in ZIP, eliminazione; i mondi Bedrock passano all'INSTALLER BEDROCK. Non fatto: lettura delle regioni (chunk), cartelle scelte con il selettore (solo archivi) | test JVM (35 + 4 sui mondi della prova) + emulatore (parte «worlds», con l'app chiusa davvero a metà: esito nel resoconto di ogni build) |
| Conversione mondi Java→Bedrock | **Fase 6, parziale**: analisi di cosa si converte e cosa no, mappa dei blocchi, rapporto. La conversione completa (Anvil → LevelDB Bedrock) è un progetto a sé: Chunker è un programma Java e su Android servirebbe un runtime Java; la scrittura di LevelDB non esiste nel progetto | dichiarato nell'app; nessun «convertito» senza test |
| Conversione mod Java→Bedrock | **Parziale (fase 7, fatta)**: modulo `:mod-converter`. Dal JAR (anche con JAR annidati) senza eseguirlo: oggetti e blocchi dai modelli (cubi, colonne, orientabili, piante a X, lastre, scale, tappeti… e gli elementi dei modelli propri convertiti in geometria Bedrock), texture con trasparenza (opaco/ritagliato/semitrasparente) e animazioni (flipbook), foglie colorate dal bioma, nomi in tutte le lingue della mod, ricette shaped/shapeless/fornace/altoforno/affumicatore/falò/tagliapietre/forgia con i nomi Bedrock degli oggetti di Minecraft (tabella GeyserMC controllata sugli oggetti di Bedrock 1.26.60) e i gruppi comuni (`c:`/`forge:`), bottini dei blocchi, minerali (configured/placed feature → ore feature + feature rule), attrezzi/armi/armature (dai gruppi della mod o dal nome, valori stimati dal materiale), armature indossabili con texture (equipment 1.21.2+ e textures/models/armor), cibo, combustibili (data map NeoForge), creature GeckoLib (geometria e animazioni Bedrock, comportamento generico, uovo). Non convertito e dichiarato: ricette delle macchine, menu, suoni, progressi, strutture, incantesimi, dimensioni, creature disegnate dal codice, blocchi disegnati dal codice (cartelli, casse) | test JVM (18, con una mod finta che copre ogni caso) + mod vere (Farmer's Delight, Mythic Metals, Naturalist in locale; mod scaricate da Modrinth in CI) controllate con gli schemi JSON ufficiali di Mojang (`ci/validate_addon.py`) + emulatore (parte «convert»: rapporto, add-on creato, consegna al finto Minecraft). Il gioco vero non si può provare in CI |
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
Profili Java indipendenti (`files/java/profiles/<id>/`), importazione `.jar`/`.zip`/multipla, riconoscimento di versione Minecraft e loader dai metadati, dipendenze mancanti e conflitti noti, SHA-256, cestino prima di ogni modifica, annulla, attiva/disattiva.

- **3a — `:jar-analyzer`** (fatto): legge `.jar`/`.zip` come archivi, **senza eseguire codice**: Fabric, Quilt, Forge, NeoForge, `mcmod.info`,
  OptiFine, resource/data/shader pack, modpack Modrinth e CurseForge, raccolte di mod; intervalli di versione Maven e SemVer; versione
  di Java dalle classi; stessi limiti di sicurezza dell'analisi Bedrock. 77 test.
- **3b — `:mod-manager`** (fatto): profili, piano di installazione (*Pronto / Serve conferma / Non installabile / Già installato*),
  installazione con copia in temporaneo, verifica SHA-256 e rinomina atomica, cestino per ciò che viene sostituito o rimosso,
  annulla (le ultime 30 operazioni), interruttore `.disabled`, riconciliazione con il disco (il disco è la verità), verifica del
  profilo, esportazione in ZIP. 147 test (+8 sui file della prova su emulatore).
- **3c — schermata MOD MANAGER** (fatto): profili, aggiunta di file dal selettore, da «Apri con», da «Condividi» e dal DOWNLOAD MANAGER;
  stato della sezione **Parziale** (gestisce i file, non avvia il gioco).
- **3d — Modrinth** (fatto; modulo `:catalog`, 86 test): ricerca e dipendenze tramite l'API ufficiale v2 con un client HTTP sostituibile
  nei test (solo `https` verso `api.modrinth.com`, nessuna redirezione seguita, limiti di dimensione, 429 con attesa, 5xx e risposte
  illeggibili spiegati). Il catalogo non scarica da solo: i file passano dal DOWNLOAD MANAGER con l'impronta pubblicata dal
  servizio (se non corrisponde il file non si tiene) e poi dal controllo del MOD MANAGER. Provato contro un **finto Modrinth**; contro il
  servizio vero solo dal passaggio dedicato della CI. CurseForge richiede una chiave personale e resta non attivo.
- *Cosa nessun test può dire:* se un launcher o il gioco carichino davvero i file di un profilo. L'app lo dichiara nella schermata.

### Fase 4 — `:modpack-engine` e MODPACK MANAGER
- **Motore** (modulo `:modpack-engine`, 73 test): `ModrinthPackReader` legge l'indice (`modrinth.index.json`) con gli stessi limiti di
  sicurezza delle altre analisi (percorsi controllati, nessun `..`, impronta obbligatoria, voci non valide scartate e contate);
  `ModpackPlanner` decide per ogni file *da scaricare / già al suo posto / solo server / bloccato* senza toccare nulla;
  `ModpackInstaller` crea il profilo, fa scaricare i file al DOWNLOAD MANAGER con l'impronta del modpack (SHA-512 o SHA-1), li mette
  al loro posto solo dopo averla ricontrollata, applica `overrides/` e `client-overrides/` (non `server-overrides/`) dentro il
  profilo, legge mod e pacchetti senza eseguirli e li registra nel MOD MANAGER con un'unica voce di cronologia. Non dichiara mai
  «completo» se manca anche un solo file. `ModpackRepository` tiene l'elenco nella sezione `modpacks` dell'archivio centrale.
- **Schermata MODPACK MANAGER** (stato **Parziale**): scelta del `.mrpack` (anche da «Apri con», «Condividi» e dal DOWNLOAD MANAGER),
  anteprima, consenso ai siti nuovi, installazione con avanzamento e Annulla, esito, elenco con APRI IL PROFILO, COMPLETA e TOGLI
  DALL'ELENCO. Il profilo e la copia del modpack si salvano **prima** di scaricare: se Android chiude l'app a metà il modpack resta
  nell'elenco come incompleto e COMPLETA scarica solo ciò che manca; se è l'utente ad annullare, sparisce tutto ciò che
  l'installazione aveva creato.
- *Limiti dichiarati:* solo modpack Modrinth; nessun servizio in primo piano; non avvia Minecraft e non può sapere se il modpack parte
  (i file si esportano dal MOD MANAGER in uno ZIP per il launcher).

### Fase 5 — `:world-engine` e WORLD MANAGER
- **Motore** (modulo `:world-engine`): `WorldScanner` riconosce i mondi in un archivio leggendo solo l'elenco dei file e `level.dat`
  (NBT Java big-endian con gzip; Bedrock tramite il lettore già usato dall'INSTALLER BEDROCK), con gli stessi controlli di sicurezza
  degli altri archivi e limiti più larghi (fino a 16 GB estratti). `WorldImporter` copia un mondo Java in `saves/.import-<token>`
  un file alla volta, con un diario dei file completati scritto solo dopo il controllo del CRC; il mondo si sposta in `saves` alla
  fine; la ripresa riusa i file del diario solo se sul disco hanno ancora dimensione e CRC giusti. `WorldExporter` fa lo ZIP di un
  mondo con la sua cartella.
- **Schermata WORLD MANAGER** (stato **Parziale**): scelta dell'archivio (copiato nella memoria dell'app: l'originale non si tocca),
  dettagli, profilo e cartella, avviso di versione, importazione con avanzamento e Annulla, importazioni interrotte (RIPRENDI /
  ANNULLA), mondi nei profili (ESPORTA (ZIP) / ELIMINA); i mondi Bedrock vanno all'INSTALLER BEDROCK (nessun secondo sistema di
  importazione).
- *Limiti dichiarati:* non converte tra le edizioni; non legge i chunk; non apre il gioco; un mondo scelto come cartella (non
  archivio) non è ancora supportato.

### Fasi 6–8 — conversione, analisi mod, runtime
Come da tabella: si fa ciò che è reale e verificabile, il resto è dichiarato.

### Fase 9–10 — diagnostica avanzata, rifinitura
Analisi dei log di crash (**9a, fatta**: in DIAGNOSTICA, «Analisi di un crash (Java)»), controlli prima dell'avvio, README, APK finale.

## Come si verifica ogni fase
1. Test JVM (`:core` e moduli nuovi) — in locale e in CI.
2. Compilazione APK in CI (`assembleDebug`/`assembleRelease`).
3. Prova su emulatore Android, in cinque lavori paralleli («base»: navigazione, importazione Bedrock, download, aggiornamento; «mods»: MOD MANAGER; «catalog»: CERCA ONLINE; «modpack»: MODPACK MANAGER; «worlds»: WORLD MANAGER): schermate e log salvati nella Release (`prova-emulatore-<parte>.zip`). Ogni difetto trovato dall'emulatore si corregge nell'app **o** nello script, dichiarando quale dei due era sbagliato.
4. Quello che nessuna prova automatica può confermare (Minecraft vero, rete reale, dispositivo fisico) viene detto esplicitamente nel resoconto.
