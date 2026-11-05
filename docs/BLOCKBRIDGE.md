# BLOCKBRIDGE — motore di conversione Java → Bedrock

Questo documento descrive il motore di **JAVA TO BEDROCK** dopo la ricostruzione BLOCKBRIDGE: perché il convertitore di
prima perdeva funzioni, come è fatto adesso, cosa è verificato nel gioco vero e cosa manca ancora.

## 1. Perché il convertitore di prima perdeva funzioni

| Problema | Effetto | Correzione |
|---|---|---|
| Non leggeva il codice Java (`.class`) | danni, durata, cibo, durezza, effetti, IA e modelli scritti nel codice erano **stimati** o persi | modulo `:bytecode`: lettore delle classi + interprete simbolico che **non esegue** il codice delle mod |
| Nessun livello Script API | in Bedrock gli attrezzi delle add-on non si consumano scavando, i cibi non danno effetti, i minerali non danno esperienza | `Behaviors.kt`: componenti personalizzati registrati da `scripts/main.js` (Script API 2.0.0) |
| Un solo aspetto per blocco | porte, lastre, scale, botole, cancelli, staccionate, muretti, pulsanti, pedane e colture diventavano blocchi fissi (le porte nemmeno blocchi) | `BlockStates.kt`: il file `blockstates` si legge per intero e si ricostruisce con gli stati e i tratti nativi di Bedrock 1.26.40 |
| Solo creature GeckoLib | Alex's Mobs, Ice and Fire e le creature con modello nel codice sparivano | modelli `LayerDefinition` e **Citadel** letti dal codice, con le animazioni (`KeyframeAnimations`, `walk/swing/flap/bob`) |
| Bottini senza stato | lastre doppie e colture mature davano il bottino sbagliato | `LootConverter` valuta le condizioni `block_state_property` per ogni stato |
| Nessun registro per voce | il rapporto riassumeva, ma una voce poteva sparire senza essere scritta | `Components.kt`: registro dei componenti, **ogni** oggetto, blocco, creatura, gruppo di ricette e file del JAR |
| Solo controlli statici | un add-on poteva superare gli schemi e non caricarsi nel gioco (è successo: versioni dei pacchetti oltre 65535) | prove nel **server Bedrock ufficiale** con un giocatore simulato (`ci/bds_test.py`) |

## 2. Architettura

```
JAR / profilo / modpack
  └─ A. Analisi universale (JavaContent, JarAnalyzer)        file del JAR, descrittori, caricatore, dipendenze
  └─ B. Ricostruzione semantica (:bytecode, code/*)          registrazioni, valori, IA, modelli, animazioni → fatti indipendenti dal caricatore
  └─ C. Compatibilità Bedrock (ModConverter, Recipes, BlockStates, Entities, CodeEntities)
  └─ D. Ricostruzione avanzata (Behaviors: Script API, componenti personalizzati, tratti nativi)
  └─ Registro dei componenti (Components) → rapporto, schermata «Analisi avanzata»
  └─ Scrittura (AddonWriter) → .mcaddon (BP con script, RP)
```

* **Nessuna esecuzione del codice delle mod**: l'interprete simbolico segue il bytecode solo per ricavare valori (come
  un lettore), con limiti di tempo e di passi; le chiamate a librerie e a Minecraft diventano valori simbolici.
* **Nomi**: Fabric (intermediary), Forge 1.17–1.20 (Mojmap + SRG), NeoForge (Mojmap) si riportano agli stessi nomi Mojang
  con una tabella costruita dalle mappe ufficiali.
* **Registrazioni**: `Registry.register`, `DeferredRegister`, Registrate (Create), owo, Architectury, ResourcefulLib,
  GlitchCore e le euristiche generali.

## 3. Cosa si ricostruisce oggi

| Area | Come | Stato |
|---|---|---|
| Oggetti, attrezzi, armature, cibo | valori dal codice (danni, durata, tier, difesa, cibo, pila) | Convertito |
| Usura degli attrezzi | `mmi:tool_wear` (come Java: spade 2 a blocco e 1 a colpo, attrezzi 1 e 2, Indistruttibilità) | Ricostruito |
| Effetti dei cibi, contenitori | `mmi:food_effects`, `using_converts_to` (ciotola, bottiglia) | Ricostruito |
| Esperienza dei minerali | `mmi:xp_on_break` (non in creativa, non con il tocco di velluto) | Ricostruito |
| Porte | tratto `minecraft:multi_block` (due parti), apertura con la mano e con la pietrarossa | Approssimato (cerniera sempre a sinistra) |
| Lastre | `minecraft:vertical_half` + lastra doppia con la Script API, bottino doppio | Ricostruito |
| Scale | `minecraft:corner_and_cardinal_direction` (angoli), supporto `stair` | Ricostruito |
| Botole, cancelli | verso, metà, apertura (mano, pietrarossa), cancello che si apre dal lato giusto | Ricostruito |
| Staccionate, muretti, pannelli | tratto `minecraft:connection`, collisioni alte 1,5 blocchi | Approssimato (muretti sempre bassi) |
| Pulsanti, pedane | `minecraft:redstone_producer` nello stato premuto | Ricostruito (pedane a peso: segnale pieno) |
| Colture | età come stato, crescita, farina d'ossa, solo sulla terra arata, semi che le piantano, bottino per età | Ricostruito |
| Creature | modelli GeckoLib, `LayerDefinition`, Citadel e Tabula (draghi di Ice and Fire, con le pose di camminata, volo e nuoto); animazioni; obiettivi Java → obiettivi Bedrock | Approssimato |
| Suoni | `sounds.json` → `sound_definitions` con i file .ogg; versi delle creature dal codice (ambiente, ferita, morte) | Convertito |
| Ricette | banco, forno, affumicatore, altoforno, falò, tagliapietre, forgia | Convertito (quelle delle macchine no) |

Versione di destinazione: la conversione segue il Minecraft installato sul telefono. Dalla **1.26.40** i blocchi con
più stati, dalla **1.21.90** la Script API; con un Minecraft più vecchio l'add-on resta compatibile e il rapporto dice
cosa non include.

## 4. Verifica

* **Test JVM** (`:mod-converter`, `:bytecode`): mod di prova scritta nei test + 22 mod vere.
* **Schemi ufficiali di Mojang** (`ci/validate_addon.py`).
* **Server Bedrock ufficiale** (`ci/bds_test.py`, anche in CI nel job «Prove nel server Bedrock ufficiale»): per ogni
  add-on un mondo nuovo e prove GameTest dentro il gioco vero:
  registrazione di ogni blocco/oggetto/creatura; ogni blocco si piazza; pila, durata e cibo uguali a quelli scritti;
  ogni creatura nasce, è viva dopo un secondo e ha la vita prevista; registro dei contenuti senza errori; con un
  **giocatore simulato**: attrezzi che si consumano, cibi che danno l'effetto, minerali che danno esperienza, porta che
  mette la metà di sopra, si apre e si rompe tutta, lastra doppia, botole e cancelli, collegamenti, pulsante con la
  pietrarossa, pedana sotto una creatura, coltura con la farina d'ossa.
* Sul telefono l'app fa solo i controlli statici e **lo dice**: non scrive mai «testato» per quello che non ha provato.

## 5. Cosa manca ancora (registro: «Dati conservati»)

Strutture (`.nbt` → `.mcstructure`), progressi, bottini di casse e pesca, particelle, biomi e dimensioni, macchine con
inventario (entità di blocco + Script API), modelli Zeta (Quark), suoni dei blocchi, anteprima 3D di modelli e animazioni
nell'app, esportazione del progetto sorgente, ripresa delle conversioni lunghe a metà. **Esecuzione Java**: l'app non contiene un runtime Java; prepara i profili per
un launcher Java per Android (MOD MANAGER → esporta).

## 6. Regole

Nessuna perdita silenziosa (ogni voce è nel registro), nessun dato simulato nei rapporti, nessuna dichiarazione di
compatibilità universale, nessun codice delle mod eseguito, licenze delle mod rispettate (l'add-on è per uso personale e
cita le mod d'origine).
