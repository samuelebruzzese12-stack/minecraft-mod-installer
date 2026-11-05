# Minecraft Mod Installer

App Android (Kotlin, Jetpack Compose, Material 3) che sostituisce il giro manuale con ZArchiver: scegli un file
scaricato (`.mcpack`, `.mcaddon`, `.mcworld`, `.mctemplate`, `.zip`, `.rar`, `.7z`, `.json`), l'app lo **apre davvero e lo
controlla**, prepara un pacchetto pulito e lo consegna a **Minecraft Bedrock** con l'apertura ufficiale di Android.
Minecraft si apre e avvia da solo l'importazione.

> **Onestà prima di tutto.** Minecraft non dice all'app se l'importazione è riuscita. Per questo l'app **non scrive mai
> «installato» da sola**: dopo l'invio ti chiede cosa hai visto nel gioco e registra *la tua* risposta
> («Inviato – da confermare» → «Importazione confermata da te» / «Non importato»).

## Una sola app, undici sezioni

L'app si apre su una **dashboard** (Minecraft trovato, contenuti gestiti, memoria e spazio liberi, errori registrati) e su un
**menu laterale** con le sezioni del progetto «Minecraft Universal Bridge». Ogni sezione dichiara il suo **stato reale**:

| Sezione | Stato | Cosa c'è davvero |
|---|---|---|
| HOME | Pronto | Dashboard con dati reali del telefono e dell'app |
| INSTALLER BEDROCK | Pronto | Tutto ciò che è descritto sotto: analisi, rapporto, consegna a Minecraft, elenchi, backup |
| JAVA EDITION | Parziale | Requisiti del telefono, memoria consigliata per profilo, versione di Java richiesta da ogni Minecraft. **Il gioco Java non si avvia da qui**: nessun runtime Java è integrato |
| MOD MANAGER | Parziale | Profili Java indipendenti (Fabric, Quilt, Forge, NeoForge): aggiungi `.jar` o raccolte `.zip`, l'app li legge **senza eseguirli**, dice se vanno bene per il profilo, li installa con verifica dell'impronta, li attiva/disattiva, li rimuove, **annulla** l'ultima operazione, verifica il profilo ed **esporta i file attivi in uno ZIP**. **CERCA ONLINE** cerca su **Modrinth** (API ufficiale), sceglie la versione adatta al profilo, calcola le dipendenze obbligatorie, scarica con verifica dell'impronta e passa i file al profilo. **CurseForge non è collegato** (l'API richiede una chiave personale). Non avvia Minecraft Java. Dettagli sotto |
| MODPACK MANAGER | Parziale | Installa un modpack **Modrinth** (`.mrpack`) in un profilo nuovo del MOD MANAGER: anteprima prima di scaricare, consenso ai siti nuovi, **impronta verificata per ogni file**, impostazioni del modpack applicate, esito onesto (*Completo* / *Incompleto* con l'elenco di ciò che manca), ripresa che scarica solo i file mancanti. **I modpack CurseForge non sono supportati**. Non avvia Minecraft Java. Dettagli sotto |
| WORLD MANAGER, WORLD CONVERTER, JAVA TO BEDROCK | In arrivo | Come sopra. La conversione completa di mondi e mod non sarà promessa senza una verifica reale |
| DOWNLOAD MANAGER | Pronto | Scarica un file da un indirizzo `https://` di un sito consentito (Modrinth, GitHub, CDN di CurseForge, o un sito che approvi tu): coda, pausa, ripresa da dove era arrivato, verifica dell'impronta (SHA-1/256/512), apertura nell'INSTALLER BEDROCK. Dettagli e limiti sotto |
| DIAGNOSTICA | Pronto | Telefono (RAM, CPU, spazio, temperatura), controllo dei file preparati, ultimi errori (anche gli arresti inattesi dell'app), rapporto da condividere solo se premi il pulsante |
| IMPOSTAZIONI | Pronto | Tema (Automatico / Chiaro / Scuro), profilo prestazioni (Risparmio / Bilanciato / Prestazioni / Personalizzato), svuota file temporanei |

Impostazioni, elenchi, cronologia e registro errori stanno in **un solo archivio** (`files/library/library.json`): aggiornare l'app
non cancella nulla di ciò che c'era. Il piano per le sezioni ancora da costruire è in
[`docs/PIANO_DI_INTEGRAZIONE.md`](docs/PIANO_DI_INTEGRAZIONE.md); lo stato di partenza è in
[`docs/ANALISI_PROGETTO.md`](docs/ANALISI_PROGETTO.md).

## Come ottenere l'APK

Il progetto si compila su GitHub (nessun PC necessario):

1. Metti **tutto il contenuto di questa cartella** nel repository (compresa la cartella nascosta `.github`).
2. Vai su **Actions → «Compila APK»**: parte da sola a ogni push (oppure «Run workflow»). Dura circa 5–10 minuti.
3. A fine esecuzione trovi l'APK in **Releases** (comodo da scaricare dal telefono) e in **Actions → esecuzione → Artifacts**.
4. Sul telefono apri `MinecraftModInstaller.apk` e consenti l'installazione dalla fonte che stai usando (browser o file manager).

Le build successive sono firmate con la stessa chiave e il numero di versione cresce a ogni esecuzione, quindi si installano
**sopra** la precedente senza disinstallare nulla.

Compilare in locale: apri la cartella con Android Studio (Ladybug o più recente, JDK 17) oppure `./gradlew :app:assembleDebug`.

## Come si usa

0. Dalla dashboard apri **INSTALLER BEDROCK** (o dal menu ☰). «Indietro» dalla home dell'installer torna alla dashboard;
   dalla dashboard esce dall'app.
1. **+ SELEZIONA FILE** (o uno dei quattro pulsanti IMPORTA…): scegli uno o più file. Puoi anche usare «Apri con» / «Condividi»
   da browser e gestore di file.
2. L'app mostra un **rapporto**: nome, anteprima, tipo, dimensione, autore, versione, dipendenze, compatibilità con la tua
   versione di Minecraft, avvisi ed errori spiegati in italiano.
3. Spunta cosa installare e premi **INSTALLA IN MINECRAFT**. L'app prepara il pacchetto, apre Minecraft e gli consegna il file.
4. Tornando nell'app rispondi alla domanda «Minecraft ha importato il contenuto?».

### DOWNLOAD MANAGER

Incolla l'indirizzo di un file (facoltativamente la sua impronta SHA) e tocca **AGGIUNGI ALLA CODA**.

- Solo `https://` sulla porta standard, senza nome utente e password, senza indirizzi numerici (IP) né nomi della rete locale.
- Solo **siti consentiti**: l'elenco di quelli noti (`cdn.modrinth.com`, `api.modrinth.com`, `github.com`, `*.githubusercontent.com`,
  i CDN di CurseForge) più quelli che approvi tu, uno per uno, con una domanda esplicita. Vale anche per i **reindirizzamenti**: se
  il server ti manda su un sito non consentito, il download si ferma prima di contattarlo e aspetta la tua risposta.
- **Pausa e ripresa**: il file parziale resta e si riprende con `Range` + `If-Range`, **solo se** il server ha dato un ETag forte o
  una data di modifica (altrimenti si ricomincia da capo: riprendere un file diverso lo rovinerebbe). Se il server ignora la
  richiesta o il file è cambiato, si riparte da zero. Dopo una caduta di rete l'app riprova da sola (1 s, 3 s, 8 s).
- **Impronta**: l'SHA-256 viene sempre calcolato; se ne indichi una (SHA-1, SHA-256 o SHA-512) e non corrisponde, il file **non
  viene tenuto** e il download risulta «Non riuscito».
- Limite di 2 GiB per file e controllo dello spazio libero; massimo 2 download in parallelo.
- Un download interrotto dalla chiusura dell'app **non riparte da solo**: resta «In pausa» e lo riprendi tu.
- Scarica **finché l'app resta in memoria**: non c'è (ancora) un servizio in primo piano, quindi se Android chiude l'app il
  download si ferma e si riprende con RIPRENDI.
- I file scaricati stanno nella memoria privata dell'app. «APRI NELL'INSTALLER BEDROCK» li passa allo **stesso** sistema di analisi
  e importazione di sempre; «SALVA UNA COPIA» li copia dove scegli. Un `.jar` (o uno `.zip`, che può essere per Bedrock o per Java) si passa al
  **MOD MANAGER** con «AGGIUNGI AL MOD MANAGER»; un modpack `.mrpack` si apre nel **MODPACK MANAGER** con «APRI NEL MODPACK MANAGER».
- L'app fa solo richieste per **ricevere** file: non invia nulla del tuo telefono.

### MOD MANAGER (Java Edition)

Un **profilo** è una raccolta indipendente di file per una versione di Minecraft e un caricatore (Fabric, Quilt, Forge, NeoForge):
ha le sue cartelle (`mods/`, `resourcepacks/`, `shaderpacks/`, `datapacks/`) nella memoria privata dell'app, e quello che metti in
un profilo non tocca gli altri.

1. **AGGIUNGI FILE (.jar, .zip)**: scegli uno o più file (anche una raccolta di mod in un solo `.zip`: ogni `.jar` diventa un file
   da controllare per conto suo). Puoi anche usare «Apri con» / «Condividi», oppure «AGGIUNGI AL MOD MANAGER» dal DOWNLOAD MANAGER.
2. L'app legge l'elenco dei file e le descrizioni dentro la mod (`fabric.mod.json`, `quilt.mod.json`, `mods.toml`,
   `neoforge.mods.toml`, `mcmod.info`, `pack.mcmeta`…) e l'intestazione delle classi per sapere che Java serve. **Non esegue mai il
   codice della mod.** Se non hai ancora un profilo propone di crearne uno con i valori letti nel file.
3. Il controllo dice, **prima di toccare qualsiasi file**: *Pronto*, *Serve conferma*, *Non installabile* o *Già installato*.
   Si blocca ciò che non può funzionare (caricatore sbagliato, incompatibilità dichiarata dalla mod); chiede conferma per ciò che
   probabilmente non funziona (versione di Minecraft o di Java diversa da quella dichiarata, librerie richieste e assenti dal
   profilo). Le librerie mancanti vengono **nominate, non scaricate**.
4. **INSTALLA**: il file viene copiato in un temporaneo, verificato (SHA-256 uguale a quello letto in analisi) e solo allora messo al
   suo posto; quello che sostituisce va da parte nel cestino del profilo. **ANNULLA** rimette tutto com'era (le ultime 30 operazioni
   sono registrate). L'interruttore disattiva una mod aggiungendo `.disabled` al nome del file, come fanno i gestori di mod.
5. **VERIFICA PROFILO** ricontrolla i file attivi tra loro (dipendenze, incompatibilità, duplicati). **ESPORTA I FILE ATTIVI (ZIP)**
   salva dove scegli uno ZIP con le cartelle di Minecraft: estrailo nella cartella di gioco del tuo programma di avvio.

#### CERCA ONLINE (Modrinth)

Dal MOD MANAGER, con un profilo attivo, **CERCA ONLINE (MODRINTH)** apre il catalogo. Usa l'API pubblica ufficiale di Modrinth
(`api.modrinth.com`), con un'identificazione dell'app (`MinecraftModInstaller/<versione>`). Le richieste contengono solo ciò che
cerchi e, se lasci attivo il filtro, il caricatore e la versione di Minecraft del profilo; nient'altro di tuo.

1. Scegli cosa cercare (*Mod*, *Resource pack*, *Shader*, *Data pack*), l'ordine (*Rilevanza*, *Più scaricati*, *Più recenti*) e se
   vuoi **«Solo file adatti al profilo»** (caricatore e versione di Minecraft del profilo attivo; un profilo Quilt accetta anche le
   mod Fabric). Scrivi il nome e tocca **CERCA**; **MOSTRA ALTRI RISULTATI** carica la pagina dopo.
2. **VEDI VERSIONE E SCARICA** sceglie la versione per il profilo: la più recente **stabile** (una beta o alpha solo se non c'è altro),
   e mai un file senza impronta, perché non si potrebbe verificare. Mostra il piano **prima** di scaricare: i file con la
   dimensione totale, le **dipendenze obbligatorie** (comprese quelle delle dipendenze), quelle già nel profilo (non si riscaricano),
   le facoltative (elencate, **non scaricate**) e le incompatibilità dichiarate dal servizio.
3. **SCARICA N FILE** li mette nel DOWNLOAD MANAGER, ciascuno con l'impronta pubblicata da Modrinth: se non corrisponde, il file
   **non viene tenuto**. Se anche un solo file non arriva, l'app lo dice e non passa il resto al profilo come se fosse completo.
4. **AGGIUNGI AL PROFILO** consegna i file scaricati allo **stesso** controllo di sempre (lettura senza esecuzione, piano
   *Pronto / Serve conferma / Non installabile*): l'installazione resta un tuo tocco. Una mod che richiede una libreria risulta
   «Serve conferma» finché la libreria non è nel profilo: installa prima la libreria, poi la mod diventa «Pronto».

Cosa non fa: non scarica le dipendenze facoltative né quelle che il servizio non pubblica (le segnala: «va cercato a mano»); non
segue più di 6 livelli di dipendenze né più di 30 file per scelta; non aggiorna da sola le mod già installate; non cerca modpack
(un `.mrpack` scaricato si installa dal MODPACK MANAGER). Se Modrinth non risponde o limita le richieste, l'app lo scrive e non
inventa risultati.
**CurseForge** non è collegato: la sua API richiede una chiave personale.

Limiti dichiarati: il MOD MANAGER **non avvia Minecraft Java** e non sa se il gioco caricherà davvero i file (i controlli si basano su
ciò che i file dichiarano di sé, che può essere incompleto o sbagliato); **CurseForge non è collegato** e le dipendenze che non
passano da Modrinth vanno aggiunte a mano; gli archivi `.7z`/`.rar` non vengono aperti come raccolte; un modpack `.mrpack` aggiunto
qui viene riconosciuto e rimandato al MODPACK MANAGER; i modpack CurseForge vengono riconosciuti ma non installati.

Come è verificato il catalogo: i test del modulo `catalog` provano la lettura delle risposte, la scelta della versione e il calcolo
delle dipendenze, e la **catena completa** (ricerca → piano → scarico verificato → analisi del `.jar` → piano del profilo) contro
un finto Modrinth locale (`ci/dl_server.py`) che imita le risposte dell'API; la prova su emulatore (`ci/catalog_phase.sh`) percorre
la stessa strada **dalla schermata**, contro lo stesso finto server. Il finto server dimostra che l'app legge e usa bene *le risposte
che immaginiamo*: non dimostra che Modrinth risponda proprio così. Per questo la CI ha un passaggio a parte, «Prova col servizio vero
(Modrinth)», che interroga il servizio reale: il suo esito è nel rapporto di ogni build, e questo documento non lo dà per scontato.

### MODPACK MANAGER (modpack Modrinth)

1. **SCEGLI UN MODPACK (.mrpack)** (oppure «Apri con» / «Condividi» da un'altra app, o «APRI NEL MODPACK MANAGER» dal DOWNLOAD
   MANAGER). L'app copia il file nella sua memoria e legge l'indice del modpack (`modrinth.index.json`) **senza eseguire nulla e
   senza scaricare nulla**.
2. L'**anteprima** dice: per quale Minecraft e caricatore è fatto, quanti file scaricherà e quanto pesano (dimensioni dichiarate dal
   modpack), quali file il modpack dichiara «solo server» (non si scaricano), quante impostazioni applicherà, le voci dell'indice
   scartate perché non valide e i **siti da consentire** (un pulsante «CONSENTI «sito»» per ciascuno: l'app scarica solo da siti
   `https` consentiti). Proponi tu il nome del profilo.
3. **INSTALLA IL MODPACK**: l'app crea il profilo e salva una copia del modpack, poi fa scaricare i file al DOWNLOAD MANAGER con
   l'impronta scritta nel modpack (SHA-512 o SHA-1): un file con l'impronta sbagliata **non entra nel profilo**. Le impostazioni di
   `overrides/` e `client-overrides/` vanno nel profilo, quelle di `server-overrides/` no. Mod e pacchetti vengono letti (senza
   eseguirli) e registrati nel MOD MANAGER: da lì si attivano, si disattivano, si verificano e si esportano in uno ZIP (le
   impostazioni del modpack sono incluse nello ZIP, nelle loro cartelle).
4. L'**esito** è *Completo* solo se ogni file è al suo posto con l'impronta giusta; altrimenti è *Incompleto* ed elenca cosa manca e
   perché. **RIPROVA I FILE MANCANTI** (o **COMPLETA** nell'elenco) scarica **solo** ciò che manca: i file già al loro posto non si
   riscaricano, e una mod che hai disattivato non torna attiva.
5. **Annulla** durante l'installazione cancella tutto quello che l'installazione aveva creato (profilo, copia del modpack, file
   scaricati). Se invece è Android a chiudere l'app a metà (non c'è un servizio in primo piano), il modpack resta nell'elenco come
   incompleto e COMPLETA riprende. **TOGLI DALL'ELENCO** cancella solo la riga e la copia del `.mrpack`: il profilo resta.

Cosa non fa: i **modpack CurseForge** non sono supportati (servono una chiave personale e la loro API); non avvia Minecraft Java e
non può sapere se il modpack, con i suoi file al loro posto, parte davvero; non installa il caricatore (Fabric, Forge…) né il gioco.

Come è verificato: i test del modulo `modpack-engine` (73) usano il motore dei download vero e un sito HTTP locale (file giusti,
manomessi, mancanti, siti non consentiti, annullamento, ripresa, file disattivati dall'utente); `EmulatorModpackSamplesTest` prova i
modpack della prova su emulatore contro il finto Modrinth di `ci/dl_server.py`; la parte «modpack» della prova su emulatore
(`ci/modpack_phase.sh`) percorre gli stessi casi **dalla schermata**. Con i **modpack veri** lavora solo il passaggio «Prova col
servizio vero (Modrinth)» della CI (`OnlineModpackTest`): scarica i `.mrpack` dei modpack Fabric più scaricati con l'impronta del
servizio, li legge e ne prepara il piano, e installa per intero il più piccolo (fino a 60 file e 120 MB) controllando ogni file sul
disco. Il suo esito sta in `modpack-online-report.txt` di ogni build e non va dato per scontato.

Altre schermate dell'installer: **FILE RECENTI**, **MOD INSTALLATI** (rinvia, segna l'esito, elimina), **CRONOLOGIA**, **BACKUP**
(prima di ogni eliminazione si salva una copia; c'è anche ANNULLA subito dopo), **Informazioni**.

## Cosa controlla l'analisi

- Archivio leggibile e integro (CRC di ogni file), non protetto da password, non una pagina web scaricata per errore.
- `manifest.json`: formato, UUID, versioni, moduli, dipendenze (incluse nell'archivio, già importate, integrate in Minecraft,
  moduli script o **mancanti**), versione minima del motore confrontata con quella installata.
- Mondi: `level.dat` letto (NBT), mondo incompleto, mondo Java rispetto a Bedrock, versione più nuova di Minecraft.
- Duplicati nello stesso file e rispetto a ciò che hai già importato (stesso UUID e versione = Minecraft lo rifiuta).
- Struttura: cartella di troppo dentro lo ZIP, add-on con più pacchetti, archivi annidati, file inutili.
- **ZIP, RAR (RAR4) e 7z**: non basta rinominare. Il contenuto viene portato alla radice, gli add-on separati in `_BP`/`_RP`,
  poi il risultato viene **riletto e verificato** prima della consegna. Se l'archivio è già perfetto viene solo copiato.

## Limiti di Android e di Minecraft (spiegati, non nascosti)

- Android vieta di scrivere nelle cartelle private di altre app (`Android/data/com.mojang…`): l'app non ci prova. Usa
  l'importazione ufficiale (Intent + URI di contenuto tramite FileProvider, con permesso temporaneo di lettura).
- L'app prova in ordine: componente esplicito di Minecraft → apertura mirata al pacchetto → selettore di Android.
- Il risultato dell'importazione compare **dentro Minecraft**, non nell'app: per questo la conferma è tua.
- **RAR5** e archivi **con password** non si possono leggere (la libreria Java non supporta RAR5): converti in ZIP o 7z.
- Contenuti per **Java Edition** vengono riconosciuti dall'INSTALLER BEDROCK ma non installati lì (Minecraft Bedrock li rifiuterebbe):
  `.jar` e pack Java hanno il loro posto nel MOD MANAGER.
- Se Minecraft non è installato l'app lo dice e propone il Play Store; l'analisi dei file funziona comunque.

## Sicurezza e privacy

- L'unico permesso dichiarato è **INTERNET**, usato soltanto dal DOWNLOAD MANAGER per ricevere file da siti consentiti (nessun
  permesso sulla memoria, sui contatti o altro; nel manifest compare anche un permesso interno di AndroidX, a livello «firma»,
  che altre app non possono usare). L'INSTALLER BEDROCK funziona senza Internet e **nessun file dell'utente viene mai inviato**.
- La versione normale dell'APK non ammette traffico non cifrato (la CI controlla il manifest). Solo la versione di prova (debug)
  ammette `http://` verso `10.0.2.2:8765`, l'indirizzo con cui l'emulatore raggiunge il server di prova della CI.
- Protezione da ZIP Slip / path traversal (percorsi con `..`, assoluti, con lettera di unità o con caratteri nulli: l'archivio
  viene rifiutato); i file non vengono mai estratti con il nome che hanno nell'archivio, ma riscritti in un nuovo pacchetto.
  Limiti su dimensione dell'archivio, dei singoli file, numero di voci e rapporto di compressione (zip bomb), tutti misurati sui
  byte **realmente letti** e non sulle intestazioni.
- Nessun codice contenuto negli archivi viene eseguito; i file eseguibili o nativi (`.exe`, `.dll`, `.so`, `.sh`, `.jar`…)
  non vengono copiati nei pacchetti Bedrock. Il MOD MANAGER legge i `.jar` come archivi (elenco, descrizioni, intestazione delle
  classi) con gli stessi limiti di dimensione e di compressione, e non carica mai le loro classi.
- Verifica dello spazio libero prima di copiare/estrarre; i file temporanei vengono cancellati.

## Struttura

```
core/   Modulo Kotlin puro (si testa sulla JVM): lettura archivi, sicurezza, manifest, NBT, analisi, preparazione pacchetti,
        backup, archivio centrale (LibraryStore / DocumentStore), impostazioni, registro errori, controlli di integrità e
        diagnostica, piano di lancio verso Minecraft. Compilato a Java 8 per girare su Android 8+.
download-engine/   Modulo Kotlin puro (si testa sulla JVM): coda dei download, regole sugli indirizzi, ripresa, verifica
        dell'impronta, limiti. Usa l'archivio centrale di `core` per salvare l'elenco.
jar-analyzer/   Modulo Kotlin puro (JVM): legge `.jar`, `.zip` e pack Java **senza eseguire codice**: Fabric, Quilt, Forge, NeoForge,
        `mcmod.info`, OptiFine, resource/data/shader pack, modpack Modrinth e CurseForge, raccolte di mod; versioni e intervalli di
        versione (Maven e SemVer), versione di Java dalle classi.
mod-manager/    Modulo Kotlin puro (JVM): profili Java, piano di installazione (pronto / conferma / bloccato), installazione con
        verifica e rollback, interruttore, rimozione, annulla, verifica del profilo, esportazione in ZIP. Usa l'archivio centrale
        (sezione `java_profiles`).
catalog/        Modulo Kotlin puro (JVM): catalogo online di **Modrinth** (API ufficiale v2): ricerca con filtri, progetti e versioni,
        scelta della versione adatta al profilo (stabile prima, solo file con impronta), piano delle dipendenze obbligatorie
        (ricorsivo, con limiti), errori spiegati (rete, 429 con attesa, 5xx, risposta illeggibile). Il client HTTP è sostituibile
        nei test; non scarica nulla da solo: i file passano dal motore di `download-engine`.
app/    App Android: Compose (ui/: dashboard, sezioni, installer, diagnostica, impostazioni, catalogo), ViewModel (MainViewModel
        per l'installer, BridgeViewModel per le altre sezioni, CatalogViewModel per CERCA ONLINE), AppContainer (oggetti
        condivisi), accesso ai file (SAF), FileProvider, lettori 7z/RAR, ponte verso Minecraft.
ci/     Prova su emulatore Android (script, lettore delle schermate, finto Minecraft che controlla il file consegnato,
        server di prova per il DOWNLOAD MANAGER e finto Modrinth per CERCA ONLINE).
docs/   Analisi del progetto e piano di integrazione.
.github/workflows/build-apk.yml   Compila, testa, prova su emulatore e pubblica l'APK.
signing/release.keystore          Chiave di firma (vedi sotto).
```

## Test

`./gradlew :core:test :download-engine:test :jar-analyzer:test :mod-manager:test :catalog:test :app:testDebugUnitTest` — 590 test (168
nel modulo `core`, 62 in `download-engine`, 77 in `jar-analyzer`, 155 in `mod-manager`, 86 in `catalog`, 42 nell'app): archivi (ZIP, CRC, zip bomb, percorsi
pericolosi), manifest e versioni, analisi di add-on/mondi/skin/dipendenze/duplicati, preparazione e riverifica dei pacchetti (anche
più pacchetti uniti in un solo `.mcaddon`, con il conteggio di ogni file letto), backup, elenchi persistenti (anche file
danneggiati), piano di lancio, messaggi d'errore e testi dell'interfaccia, e il percorso completo di un file `.7z`
(lettura → analisi → `.mcpack`/`.mcaddon` → rilettura, più archivi alterati o troncati), archivio centrale (sezioni
sconosciute conservate, compatibilità con l'archivio della versione precedente), impostazioni, piano della memoria,
registro errori, rapporto di un arresto inatteso, controllo di integrità, diagnostica, struttura delle 11 sezioni e il momento
in cui chiedere la conferma dopo l'invio a Minecraft. Il motore dei download è provato contro un **vero server HTTP locale**:
regole sugli indirizzi, reindirizzamenti, ripresa con `Range`/`If-Range`, server che ignora l'intervallo o cambia file, 416,
impronta giusta e sbagliata, limite di dimensione, spazio insufficiente, pausa/annulla in ogni momento, riavvio, due download
insieme. L'analizzatore dei `.jar` è provato con archivi costruiti al volo (descrizioni di ogni caricatore, intervalli di versione,
percorsi pericolosi, zip bomb, classi troncate). Il gestore dei profili è provato su file veri in cartelle temporanee: installazione
con verifica, interruzioni a metà, archivio centrale che non si salva (rollback), annulla in ogni caso, file cambiati fuori
dall'app, esportazione in ZIP con file mancanti o modificati, e gli 8 test `EmulatorSamplesTest` che controllano con l'analizzatore
vero i file Java usati dalla prova su emulatore (si eseguono quando `MMI_SAMPLES` indica la cartella creata da
`python3 ci/make_samples.py`; in CI sempre). Il catalogo è provato su tre livelli: risposte registrate dell'API di Modrinth e
casi limite (versioni, dipendenze a catena, cicli, file senza impronta, 429, 5xx, risposte illeggibili o enormi); un vero server HTTP
locale con la libreria di rete vera; e la catena completa contro il finto Modrinth (9 test, con `MMI_FAKE_MODRINTH`): ricerca,
piano, scarico con impronta verificata, **file alterato rifiutato**, analisi del `.jar` e piano del profilo. Altri 4 test
(`OnlineModrinthTest`) interrogano il **servizio vero** e girano solo nel passaggio dedicato della CI (`MMI_ONLINE=1`); altrove
risultano saltati, non riusciti.

A ogni push GitHub prova anche l'APK su un **emulatore Android** (`ci/emulator_smoke.sh`). Il computer di GitHub ha solo 2 core e
l'emulatore è lento (ogni passaggio dura decine di secondi), quindi la prova gira in **quattro lavori paralleli**, ciascuno con il suo
emulatore (immagine di base di Android; solo se risulta inutilizzabile si ripete con quella dei servizi di Google):

- **base**: apre dashboard, menu e ogni sezione, controlla che quelle non costruite non abbiano pulsanti finti, cambia tema e profilo
  e verifica che restino dopo il riavvio, importa i file di prova dal selettore di sistema, li consegna a un **finto Minecraft** che
  confronta i byte ricevuti con l'originale, provoca di proposito un arresto dell'app per vedere che finisca in Diagnostica, e
  infine installa l'APK nuovo **sopra la versione precedente** controllando che file recenti, contenuti gestiti e cronologia siano
  rimasti identici. Usa un piccolo server HTTP (`ci/dl_server.py`) sul computer di prova per **scaricare davvero** dal DOWNLOAD
  MANAGER: file con impronta giusta e sbagliata, errore 404, reindirizzamento, pausa e ripresa (controlla che l'app chieda proprio
  `Range` dal punto raggiunto), rete che cade a metà, sito non consentito con domanda di consenso, annullamento di un file
  «incagliato» (quasi fermo, così lo stato non cambia sotto le mani dello script), chiusura dell'app a metà.
- **mods**: il MOD MANAGER (profilo proposto dal primo file, installazione con impronta uguale all'originale, interruttore, annulla,
  rimozione, caricatore sbagliato, due profili indipendenti, dipendenza mancante, raccolta `.zip`, resource pack, esportazione in ZIP,
  passaggio dai download, dati dopo la chiusura dell'app, eliminazione di un profilo).
- **catalog**: **CERCA ONLINE** contro il finto Modrinth dello stesso server (ricerca, filtri, errori 500 e 429, piano con
  dipendenze, scarico verificato, file alterato scartato, passaggio al profilo, installazione in ordine). Parte da un profilo
  Fabric 1.20.1 che si prepara da sé con gli stessi passaggi dell'utente (una mod, la sua libreria, una mod che la richiede).
- **modpack**: il **MODPACK MANAGER** con quattro modpack di prova serviti dallo stesso server: uno completo (anteprima che non
  tocca né rete né profili, installazione, file nel profilo identici all'originale, mod solo-server non scaricata, impostazioni
  applicate, MOD MANAGER e VERIFICA PROFILO), uno incompleto (un file che manca la prima volta e uno alterato: la ripresa scarica
  solo ciò che manca), uno lento da annullare a metà (non deve restare nulla), uno da un sito da consentire; poi l'apertura di un
  `.mrpack` dal DOWNLOAD MANAGER, TOGLI DALL'ELENCO e i dati dopo la chiusura dell'app.

Lanciando il workflow a mano («Run workflow») il campo **parts** sceglie quali parti eseguire (es. `mods,modpack`; vuoto = tutte).

Se un passaggio si pianta, lo script si ferma da solo dopo 85 minuti, salva schermata e log dell'ultimo punto raggiunto e lo scrive
tra i problemi. Schermate, dump dell'interfaccia e log di ogni parte sono allegati alla Release (`prova-emulatore-base.zip`,
`prova-emulatore-mods.zip`, `prova-emulatore-catalog.zip` e `prova-emulatore-modpack.zip`), insieme ai resoconti della prova col
servizio vero di Modrinth: `catalog-online-report.txt` (mod) e `modpack-online-report.txt` (modpack).

Cosa **non** si può provare in CI: Internet vero dall'emulatore (i test usano solo il server locale; l'unico contatto con Modrinth
vero è il passaggio «Prova col servizio vero (Modrinth)» dei test su JVM), **Minecraft vero** (il finto Minecraft non importa
niente: dice solo che l'app ha consegnato il file giusto con lo stesso Intent; se il gioco lo importi o no non si sa) e se un
programma di avvio o il gioco carichino i file di un profilo Java.

## Chiave di firma

`signing/release.keystore` è inclusa **volutamente** così ogni build è firmata uguale e gli aggiornamenti si installano sopra la
versione precedente. È una chiave per uso personale: **tieni il repository privato**. Per pubblicare l'app su un negozio genera
una chiave tua e cambia `signingConfigs` in `app/build.gradle.kts`.
