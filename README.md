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
| MOD MANAGER, MODPACK MANAGER | In arrivo | Pagina che spiega cosa farà e cosa manca; nessun pulsante finto |
| WORLD MANAGER, WORLD CONVERTER, JAVA TO BEDROCK | In arrivo | Come sopra. La conversione completa di mondi e mod non sarà promessa senza una verifica reale |
| DOWNLOAD MANAGER | In arrivo | Oggi l'app non ha il permesso Internet: lo chiederà solo quando questa sezione esisterà |
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

Altre schermate: **FILE RECENTI**, **MOD INSTALLATI** (rinvia, segna l'esito, elimina), **CRONOLOGIA**, **BACKUP**
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
- Contenuti per **Java Edition** vengono riconosciuti ma non installati (Minecraft Bedrock li rifiuterebbe).
- Se Minecraft non è installato l'app lo dice e propone il Play Store; l'analisi dei file funziona comunque.

## Sicurezza e privacy

- **Nessun permesso** richiesto all'utente (nel manifest compare solo un permesso interno di AndroidX, a livello «firma», che altre app non possono usare) e **nessun accesso a Internet**: nessun file lascia il telefono.
- Protezione da ZIP Slip / path traversal (percorsi con `..`, assoluti, con lettera di unità o con caratteri nulli: l'archivio
  viene rifiutato); i file non vengono mai estratti con il nome che hanno nell'archivio, ma riscritti in un nuovo pacchetto.
  Limiti su dimensione dell'archivio, dei singoli file, numero di voci e rapporto di compressione (zip bomb), tutti misurati sui
  byte **realmente letti** e non sulle intestazioni.
- Nessun codice contenuto negli archivi viene eseguito; i file eseguibili o nativi (`.exe`, `.dll`, `.so`, `.sh`, `.jar`…)
  non vengono copiati nel pacchetto.
- Verifica dello spazio libero prima di copiare/estrarre; i file temporanei vengono cancellati.

## Struttura

```
core/   Modulo Kotlin puro (si testa sulla JVM): lettura archivi, sicurezza, manifest, NBT, analisi, preparazione pacchetti,
        backup, archivio centrale (LibraryStore / DocumentStore), impostazioni, registro errori, controlli di integrità e
        diagnostica, piano di lancio verso Minecraft. Compilato a Java 8 per girare su Android 8+.
app/    App Android: Compose (ui/: dashboard, sezioni, installer, diagnostica, impostazioni), ViewModel (MainViewModel per
        l'installer, BridgeViewModel per le altre sezioni), AppContainer (oggetti condivisi), accesso ai file (SAF),
        FileProvider, lettori 7z/RAR, ponte verso Minecraft.
ci/     Prova su emulatore Android (script, lettore delle schermate, finto Minecraft che controlla il file consegnato).
docs/   Analisi del progetto e piano di integrazione.
.github/workflows/build-apk.yml   Compila, testa, prova su emulatore e pubblica l'APK.
signing/release.keystore          Chiave di firma (vedi sotto).
```

## Test

`./gradlew :core:test :app:testDebugUnitTest` — 199 test (160 nel modulo `core`, 39 nell'app): archivi (ZIP, CRC, zip bomb, percorsi pericolosi), manifest e versioni,
analisi di add-on/mondi/skin/dipendenze/duplicati, preparazione e riverifica dei pacchetti (anche più pacchetti uniti in un
solo `.mcaddon`, con il conteggio di ogni file letto), backup, elenchi persistenti
(anche file danneggiati), piano di lancio, messaggi d'errore e testi dell'interfaccia, e il percorso completo di un file `.7z`
(lettura → analisi → `.mcpack`/`.mcaddon` → rilettura, più archivi alterati o troncati), archivio centrale (sezioni
sconosciute conservate, compatibilità con l'archivio della versione precedente), impostazioni, piano della memoria,
registro errori, rapporto di un arresto inatteso, controllo di integrità, diagnostica e struttura delle 11 sezioni.

A ogni push GitHub prova anche l'APK su un **emulatore Android** (`ci/emulator_smoke.sh`): apre dashboard, menu e ogni
sezione, controlla che quelle non costruite non abbiano pulsanti finti, cambia tema e profilo e verifica che restino dopo il
riavvio, importa i file di prova dal selettore di sistema, li consegna a un **finto Minecraft** che confronta i byte ricevuti
con l'originale, provoca di proposito un arresto dell'app per vedere che finisca in Diagnostica, e infine installa l'APK nuovo
**sopra la versione precedente** controllando che file recenti, contenuti gestiti e cronologia siano rimasti identici. Schermate
e log di ogni prova sono allegati alla Release (`prova-emulatore.zip`). Il finto Minecraft non è Minecraft: se il gioco vero
importi o no il file non si può provare in CI.

## Chiave di firma

`signing/release.keystore` è inclusa **volutamente** così ogni build è firmata uguale e gli aggiornamenti si installano sopra la
versione precedente. È una chiave per uso personale: **tieni il repository privato**. Per pubblicare l'app su un negozio genera
una chiave tua e cambia `signingConfigs` in `app/build.gradle.kts`.
