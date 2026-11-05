#!/bin/bash
# Fase «CERCA ONLINE» (catalogo Modrinth) della prova su emulatore. Non si esegue da sola: la include ci/emulator_smoke.sh dopo la
# fase MOD MANAGER e ne usa le funzioni e le variabili ($UI, $PKG, $SAMPLES, $OUT, $DL_LOG, fail, note, shot, expect, snap, seen,
# mods_*, dl_*…).
#
# Parla con un FINTO Modrinth: lo stesso server di prova dei download (ci/dl_server.py --modrinth) imita l'API pubblica v2 e il suo
# CDN con pochi progetti inventati. Nella build di prova (debug) l'app legge l'indirizzo del catalogo da files/dev/catalog_base.txt
# (nella versione vera quel file non conta nulla e si usa api.modrinth.com). Questa fase NON prova Modrinth vero: lo fa il passo
# «Prova col servizio vero (Modrinth)» del workflow; e i dati finti sono gli stessi che il test FakeModrinthChainTest controlla prima
# con il catalogo, il motore dei download e il gestore dei profili veri.
#
# Gira in due modi: dopo la fase MOD MANAGER (parte «all»: usa il suo profilo Fabric 1.20.1) oppure da sola, nella parte «catalog»
# del workflow, che in un emulatore tutto suo prepara prima un profilo con quello che serve (catalog_setup). Così le due parti
# lunghe della prova girano in parallelo. I conteggi dei file sono RELATIVI a ciò che il profilo ha all'inizio.
#
# Si prova, sul profilo Fabric 1.20.1: la pagina e il filtro «solo file adatti al profilo», la ricerca
# (testo, ordine, tipo), gli errori del servizio (500 e 429), una mod già nel profilo (non si riscarica), una mod con libreria
# obbligatoria (piano, scarico verificato, passaggio al MOD MANAGER, installazione), un file con impronta sbagliata (scartato) e un
# resource pack. Dal registro del server si controlla anche COSA ha chiesto l'app: User-Agent, filtri, e che non abbia scaricato
# versioni beta, dipendenze facoltative né mod di un altro caricatore.

CAT_TXT=/tmp/mmi_catalog_texts.txt
CAT_WHAT=""

# ---- il registro del server di prova
# Le richieste ricevute con quel prefisso di percorso, una riga ciascuna: «percorso|parametri decodificati|User-Agent».
mr_requests() {
  python3 -I - "$DL_LOG" "$1" <<'PY'
import json, sys, urllib.parse
for line in open(sys.argv[1], errors="replace"):
    try:
        e = json.loads(line)
    except ValueError:
        continue
    if e.get("event") == "start" and (e.get("path") or "").startswith(sys.argv[2]):
        print("%s|%s|%s" % (e.get("path"), urllib.parse.unquote(e.get("query") or ""), e.get("ua") or ""))
PY
}
mr_count() { mr_requests "$1" | grep -cF -- "$2"; }                    # prefisso testo → quante richieste lo contengono
mr_asked() { mr_requests "$1" | grep -qF -- "$2"; }                    # prefisso testo

# ---- l'interfaccia
# Aspetta un testo; se non si vede (pagina scorsa) torna in cima e poi scorre.
cat_wait() { $UI wait "$1" "$2" || { $UI top; $UI wait "$1" 3; } || $UI scan "$1" 4; }
# Rilegge tutti i testi della pagina, anche quelli sotto lo schermo, in $CAT_TXT.
cat_texts() { : > "$CAT_TXT"; $UI collect "$CAT_TXT" 8 > /dev/null 2>&1; }
cat_has() { grep -qF -- "$1" "$CAT_TXT"; }
cat_expect() { local t; for t in "$@"; do cat_has "$t" || fail "CERCA ONLINE: manca «$t» ($CAT_WHAT)"; done; }
cat_absent() { local t; for t in "$@"; do ! cat_has "$t" || fail "CERCA ONLINE: compare «$t» ma non dovrebbe ($CAT_WHAT)"; done; }
# Dentro una finestra non si usa cat_texts (scorrerebbe la pagina dietro): ogni testo si cerca nella finestra, scorrendola se è lunga.
dlg_expect() { local t; for t in "$@"; do $UI scan "$t" 3 > /dev/null || fail "CERCA ONLINE: manca «$t» nella finestra ($CAT_WHAT)"; done; }

# Porta l'app alla pagina CERCA ONLINE (dentro il MOD MANAGER), in cima.
cat_open() {
  local i
  for i in 1 2 3; do
    snap
    if seen "Catalogo Modrinth"; then $UI top; return 0; fi
    mods_open || continue
    $UI tap "CERCA ONLINE (MODRINTH)" > /dev/null || continue
    if $UI wait "Catalogo Modrinth" 10; then $UI top; return 0; fi
  done
  return 1
}
# Scrive il testo (il campo è l'unico della pagina) e tocca CERCA.
cat_search() { # testo
  $UI top
  $UI clear-field 0 > /dev/null 2>&1
  if [ -n "$1" ]; then
    $UI fill 0 "$1" || { fail "CERCA ONLINE: il campo del nome non si trova o non accetta «$1»"; return 1; }
  fi
  $UI tap-exact "CERCA" || { fail "CERCA ONLINE: il pulsante CERCA non si trova"; return 1; }
}
# Apre il piano di download del primo (unico) risultato e aspetta che sia pronto.
cat_choose() { # testo-da-aspettare
  $UI tap "VEDI VERSIONE E SCARICA" || { shot "catalogo: nessun risultato da scegliere"; fail "CERCA ONLINE: non trovo «VEDI VERSIONE E SCARICA»"; return 1; }
  $UI wait "$1" 40 || { shot "catalogo: piano non pronto"; fail "CERCA ONLINE: il piano di download non compare (manca «$1»)"; return 1; }
}

catalog_body() {
  local pid="$1" mod_sha lib_sha tex_sha n bad f got want n0_mods n0_rp
  mod_sha=$(sample_sha Mod_Catalogo.jar); lib_sha=$(sample_sha Libreria_Catalogo.jar); tex_sha=$(sample_sha Texture_Catalogo.zip)
  n0_mods=$(mods_count "$pid" mods); n0_rp=$(mods_count "$pid" resourcepacks)   # ciò che il profilo ha prima di questa fase

  # ================================================================== la pagina
  if ! cat_open; then
    shot "catalogo: pagina non aperta"
    fail "CERCA ONLINE: dal MOD MANAGER il pulsante «CERCA ONLINE (MODRINTH)» non apre la pagina"
    return
  fi
  sleep 0.5; shot "catalogo: pagina iniziale"
  CAT_WHAT="pagina iniziale"; cat_texts
  cat_expect "CERCA ONLINE" "Catalogo Modrinth" "api.modrinth.com" "CurseForge non è collegato" "Cosa cercare" \
    "Solo file adatti al profilo" "Profilo attivo: Fabric 1.20.1" "Mod" "Resource pack" "Shader" "Data pack" \
    "Rilevanza" "Più scaricati" "Più recenti" "CERCA"
  cat_absent "Risultati (" "Scarico"
  no_action "CurseForge" || fail "CERCA ONLINE: compare un pulsante CurseForge (non è collegato)"
  no_action "CURSEFORGE" || fail "CERCA ONLINE: compare un pulsante CURSEFORGE (non è collegato)"
  $UI top
  $UI switch-state 0 || fail "CERCA ONLINE: «Solo file adatti al profilo» dovrebbe essere acceso all'inizio"

  # ================================================================== ricerca vuota: i più diffusi, solo per il profilo
  cat_search "" || return
  if cat_wait "Risultati (6 di 6)" 40; then
    sleep 0.5; shot "catalogo: risultati per il profilo"
    CAT_WHAT="ricerca vuota con il filtro del profilo"; cat_texts
    cat_expect "Risultati (6 di 6)" "Mod dal Catalogo" "Libreria dal Catalogo" "Extra Opzionale" "Mod con Impronta Sbagliata" \
      "Mod con Libreria" "Libreria Esempio" "di Autore Uno" "2,5 milioni download" "41 mila download" "Minecraft: 1.20.1, 1.19.2" \
      "VEDI VERSIONE E SCARICA"
    cat_absent "Mod Solo Forge"
  else
    shot "catalogo: nessun risultato"
    fail "CERCA ONLINE: la ricerca vuota non mostra «Risultati (6 di 6)» (il finto servizio ha 6 mod per Fabric 1.20.1)"
    return
  fi
  # I filtri partono come li vuole il profilo, il testo vuoto non entra nella richiesta e l'app si presenta con il suo nome.
  mr_asked /modrinth/v2/search 'facets=[["project_type:mod"],["categories:fabric"],["versions:1.20.1"]]' ||
    fail "CERCA ONLINE: la ricerca non ha inviato i filtri del profilo (tipo mod, caricatore fabric, Minecraft 1.20.1)"
  bad=$(mr_requests /modrinth/v2 | awk -F'|' '$3 !~ /^MinecraftModInstaller\//' | head -n 1)
  [ -z "$bad" ] || fail "CERCA ONLINE: una richiesta all'API non porta lo User-Agent dell'app: $bad"

  # ---- ordine
  $UI top
  if $UI tap-exact "Più scaricati"; then
    cat_wait "Risultati (6 di 6)" 30 > /dev/null; sleep 1
    mr_asked /modrinth/v2/search 'index=downloads' || fail "CERCA ONLINE: «Più scaricati» non ha cambiato l'ordine chiesto al servizio (index=downloads)"
    $UI top; $UI tap-exact "Rilevanza" > /dev/null; cat_wait "Risultati (6 di 6)" 30 > /dev/null
  else
    fail "CERCA ONLINE: il pulsante «Più scaricati» non si trova"
  fi

  # ---- il filtro del profilo: spento, compare anche la mod che esiste solo per Forge
  $UI top
  if $UI tap-switch 0; then
    if cat_wait "Risultati (7 di 7)" 40; then
      sleep 0.5; shot "catalogo: senza filtro del profilo"
      CAT_WHAT="filtro del profilo spento"; cat_texts
      cat_expect "Mod Solo Forge" "Mod dal Catalogo"
      mr_asked /modrinth/v2/search 'facets=[["project_type:mod"]]&' || fail "CERCA ONLINE: a filtro spento la ricerca dovrebbe chiedere solo il tipo (nessun caricatore né versione)"
    else
      shot "catalogo: filtro spento senza effetto"
      fail "CERCA ONLINE: spegnendo «Solo file adatti al profilo» la ricerca non si rifà con 7 risultati (compare anche la mod per Forge)"
    fi
    $UI top; $UI tap-switch 0 > /dev/null
    cat_wait "Risultati (6 di 6)" 40 || fail "CERCA ONLINE: riaccendendo il filtro del profilo la mod per Forge non sparisce"
  else
    shot "catalogo: interruttore non trovato"
    fail "CERCA ONLINE: l'interruttore «Solo file adatti al profilo» non si trova (come nodo selezionabile)"
  fi

  # ================================================================== errori del servizio: un messaggio chiaro, l'app resta aperta
  cat_search "errore500" || return
  if cat_wait "Modrinth ha un problema in questo momento" 30; then
    sleep 0.5; shot "catalogo: errore del servizio"
    expect "codice 500"
    $UI tap-exact "OK" > /dev/null; sleep 1
    $UI has "Modrinth ha un problema in questo momento" && fail "CERCA ONLINE: «OK» non toglie il messaggio d'errore"
  else
    shot "catalogo: errore del servizio non mostrato"
    fail "CERCA ONLINE: a un errore 500 del servizio l'app non mostra «Modrinth ha un problema in questo momento»"
  fi
  cat_search "limite429" || return
  if cat_wait "riprova tra 7 secondi" 30; then
    sleep 0.5; shot "catalogo: troppe richieste"
    $UI tap-exact "OK" > /dev/null; sleep 1
  else
    shot "catalogo: troppe richieste non mostrato"
    fail "CERCA ONLINE: a un errore 429 del servizio l'app non dice «riprova tra 7 secondi» (Retry-After)"
  fi

  # ================================================================== una mod già nel profilo: niente da scaricare
  cat_search "mod-con-libreria" || return
  if cat_wait "Risultati (1 di 1)" 30; then
    if cat_choose "niente da scaricare"; then
      sleep 0.5; shot "catalogo: già nel profilo"
      CAT_WHAT="mod già nel profilo"
      dlg_expect "Mod con Libreria" "Già nel profilo (non si scaricano)" "«Mod con Libreria»" "«Libreria Esempio»" "CHIUDI"
      no_action "SCARICA" || fail "CERCA ONLINE: per una mod già nel profilo compare un pulsante SCARICA"
      $UI tap-exact "CHIUDI" > /dev/null; sleep 1
    fi
  else
    shot "catalogo: mod già presente non trovata"
    fail "CERCA ONLINE: cercando «mod-con-libreria» non compare «Risultati (1 di 1)»"
  fi

  # ================================================================== una mod con libreria obbligatoria: dal catalogo al profilo
  cat_search "mod-dal-catalogo" || return
  if ! cat_wait "Risultati (1 di 1)" 30; then
    shot "catalogo: mod non trovata"
    fail "CERCA ONLINE: cercando «mod-dal-catalogo» non compare «Risultati (1 di 1)»"
    return
  fi
  cat_choose "Verranno scaricati" || return
  sleep 0.5; shot "catalogo: piano con dipendenze"
  CAT_WHAT="piano di «Mod dal Catalogo»"
  dlg_expect "Mod dal Catalogo 1.5.0" "Mod_Catalogo.jar" "Libreria dal Catalogo 1.2.0" "Libreria_Catalogo.jar" \
    "richiesta da «Mod dal Catalogo»" "Totale: 2 file" "Facoltative, non scaricate: Extra Opzionale" \
    "Dichiarata incompatibile con: «Mod Solo Forge»" "verificato con l'impronta" "SCARICA 2 FILE" "ANNULLA"
  $UI has "2.0.0-beta" && fail "CERCA ONLINE: il piano propone la versione beta 2.0.0-beta invece della stabile 1.5.0"
  if ! $UI tap-exact "SCARICA 2 FILE"; then fail "CERCA ONLINE: il pulsante «SCARICA 2 FILE» non si trova"; return; fi
  if cat_wait "Scaricato e verificato" 90; then
    $UI top; sleep 0.5; shot "catalogo: scaricato e verificato"
    CAT_WHAT="scarico completato"; cat_texts
    cat_expect "Scarico" "«Mod dal Catalogo»" "Mod_Catalogo.jar" "Libreria_Catalogo.jar" "impronta verificata" \
      "Scaricato e verificato" "AGGIUNGI AL PROFILO (2 file)" "CHIUDI"
    for f in Mod_Catalogo.jar Libreria_Catalogo.jar; do
      got=$(dl_device_sha "$f"); want=$(sample_sha "$f")
      if [ -n "$got" ] && [ "$got" = "$want" ]; then
        log "scaricato: $f identico al file servito (SHA-256 ${want:0:16}…)"
      else
        fail "CERCA ONLINE: $f sul telefono non è identico al file servito (atteso ${want:0:16}…, trovato ${got:0:16}…)"
      fi
    done
    dl_device_ls | grep -q '\.part$' && fail "CERCA ONLINE: dopo lo scarico resta un file parziale (.part)"
  else
    shot "catalogo: scarico non completato"
    fail "CERCA ONLINE: dopo SCARICA 2 FILE non compare «Scaricato e verificato» entro 90 s"
    return
  fi
  mr_asked /modrinth-cdn/ "Mod_Catalogo.jar" || fail "CERCA ONLINE: il server non ha ricevuto la richiesta del file Mod_Catalogo.jar"
  mr_asked /modrinth-cdn/ "Libreria_Catalogo.jar" || fail "CERCA ONLINE: il server non ha ricevuto la richiesta del file Libreria_Catalogo.jar"
  for f in "Mod_Catalogo-2.0.0-beta.jar" "Mod_Catalogo-1.0.0.jar" "Extra_Opzionale.jar" "Mod_Solo_Forge.jar" "Mod_Con_Dipendenza.jar" "Libreria_Esempio.jar"; do
    mr_asked /modrinth-cdn/ "$f" && fail "CERCA ONLINE: l'app ha scaricato $f, che non andava scaricato (beta, versione per un altro Minecraft, facoltativa, altro caricatore o già nel profilo)"
  done

  # ---- dal DOWNLOAD MANAGER al MOD MANAGER, e l'installazione in ordine: prima la libreria, poi la mod
  $UI top
  if $UI tap "AGGIUNGI AL PROFILO"; then
    if mods_wait "Libreria_Catalogo.jar" 60; then
      $UI scan "Mod_Catalogo.jar" 6 > /dev/null; $UI scan "Da installare (2)" 6 > /dev/null || fail "CERCA ONLINE: nel MOD MANAGER manca «Da installare (2)»"
      $UI top; sleep 0.5; shot "catalogo: file arrivati nel mod manager"
      mods_wait "Pronto" 40 || { shot "catalogo: nessun file pronto"; fail "CERCA ONLINE: nel MOD MANAGER nessuno dei due file risulta «Pronto» (la libreria dovrebbe esserlo)"; return; }
      if $UI has "INSTALLA I 2 FILE PRONTI"; then
        # Se anche la mod risulta già pronta (le dipendenze in attesa contano), si installano insieme.
        $UI tap "INSTALLA I 2 FILE PRONTI" > /dev/null
      else
        $UI top
        $UI tap-exact "INSTALLA" > /dev/null || { fail "CERCA ONLINE: il pulsante INSTALLA della libreria non si trova"; return; }
        mods_until 40 mods_has mods "$lib_sha" || { shot "catalogo: libreria non installata"; fail "CERCA ONLINE: la libreria scaricata non è finita nel profilo"; return; }
        mods_wait "Installato nel profilo" 20 > /dev/null
        # Con la libreria nel profilo la mod passa da «Serve conferma» a «Pronto».
        if mods_wait "Pronto" 30; then
          $UI top
          $UI tap-exact "INSTALLA" > /dev/null
        else
          shot "catalogo: la mod non diventa pronta"
          fail "CERCA ONLINE: dopo aver installato la libreria la mod non risulta «Pronta»"
          return
        fi
      fi
      mods_until 60 mods_has mods "$mod_sha" || { shot "catalogo: mod non installata"; fail "CERCA ONLINE: la mod scaricata non è finita nel profilo"; return; }
      mods_has mods "$lib_sha" || fail "CERCA ONLINE: la libreria scaricata non è nel profilo"
      log "dal catalogo al profilo: mod e libreria installate, identiche ai file serviti (SHA-256 ${mod_sha:0:16}… e ${lib_sha:0:16}…)"
      lib_has '"source" *: *"modrinth"' || fail "CERCA ONLINE: nell'archivio centrale i file installati non portano l'origine «modrinth»"
      mods_clear_pending
      n=$(mods_count "$pid" mods)
      [ "$n" = $((n0_mods + 2)) ] || fail "CERCA ONLINE: il profilo dovrebbe avere $((n0_mods + 2)) mod ($n0_mods + 2 dal catalogo), ne ha $n"
      mods_ls "$pid" mods | grep -q '\.part$' && fail "CERCA ONLINE: nella cartella mods resta un file parziale (.part)"
      shot "catalogo: installate"
    else
      shot "catalogo: i file non arrivano nel mod manager"
      fail "CERCA ONLINE: «AGGIUNGI AL PROFILO» non porta le due schede nel MOD MANAGER"
      return
    fi
  else
    fail "CERCA ONLINE: il pulsante «AGGIUNGI AL PROFILO (2 file)» non si trova"
    return
  fi

  # ================================================================== un file con l'impronta sbagliata: scartato, e il resto resta com'era
  cat_open || { fail "CERCA ONLINE: non riesco a riaprire la pagina"; return; }
  cat_search "impronta-sbagliata" || return
  if cat_wait "Risultati (1 di 1)" 30 && cat_choose "Verranno scaricati"; then
    CAT_WHAT="piano di «Mod con Impronta Sbagliata»"
    dlg_expect "Mod con Impronta Sbagliata" "Mod_Impronta_Sbagliata.jar" "SCARICA 1 FILE"
    $UI tap-exact "SCARICA 1 FILE" > /dev/null
    if cat_wait "Non tutti i file sono arrivati" 90; then
      $UI top; sleep 0.5; shot "catalogo: file alterato scartato"
      CAT_WHAT="file con impronta sbagliata"; cat_texts
      cat_expect "Non riuscito" "non corrisponde"
      cat_absent "AGGIUNGI AL PROFILO" "Scaricato e verificato"
      dl_device_ls | grep -q 'Mod_Impronta_Sbagliata' && fail "CERCA ONLINE: il file con l'impronta sbagliata è rimasto sul telefono ($(dl_device_ls | tr '\n' ' '))"
      mr_asked /modrinth-cdn/ "Mod_Impronta_Sbagliata.jar" || fail "CERCA ONLINE: il server non ha ricevuto la richiesta del file con l'impronta sbagliata"
      mods_has mods "$(sample_sha Mod_Fabric_Esempio.jar)" || note "CERCA ONLINE: il profilo non ha più Mod_Fabric_Esempio.jar (controllo di coerenza)"
      $UI tap-exact "CHIUDI" > /dev/null; sleep 1
    else
      shot "catalogo: file alterato non scartato"
      fail "CERCA ONLINE: un file la cui impronta non corrisponde a quella pubblicata non porta a «Non tutti i file sono arrivati»"
    fi
  else
    shot "catalogo: mod con impronta sbagliata non trovata"
    fail "CERCA ONLINE: il piano per «impronta-sbagliata» non compare"
  fi

  # ================================================================== un resource pack: niente caricatore, va nella sua cartella
  cat_open || { fail "CERCA ONLINE: non riesco a riaprire la pagina"; return; }
  $UI top
  if $UI tap-exact "Resource pack"; then
    cat_search "texture-dal-catalogo" || return
    if cat_wait "Risultati (1 di 1)" 30 && cat_choose "Verranno scaricati"; then
      sleep 0.5; shot "catalogo: piano del resource pack"
      CAT_WHAT="piano di «Texture dal Catalogo»"
      dlg_expect "Texture dal Catalogo 1.0.0" "Texture_Catalogo.zip" "Totale: 1 file" "SCARICA 1 FILE"
      mr_asked /modrinth/v2/search 'facets=[["project_type:resourcepack"],["versions:1.20.1"]]' ||
        fail "CERCA ONLINE: per un resource pack la ricerca non dovrebbe chiedere il caricatore (solo tipo e versione di Minecraft)"
      $UI tap-exact "SCARICA 1 FILE" > /dev/null
      if cat_wait "Scaricato e verificato" 90; then
        $UI top
        if $UI tap "AGGIUNGI AL PROFILO"; then
          if mods_wait "Texture_Catalogo.zip" 60 && mods_wait "Pronto" 30; then
            $UI top
            $UI tap-exact "INSTALLA" > /dev/null
            if mods_until 40 mods_has resourcepacks "$tex_sha"; then
              mods_clear_pending
              n=$(mods_count "$pid" resourcepacks)
              [ "$n" = $((n0_rp + 1)) ] || fail "CERCA ONLINE: il profilo dovrebbe avere $((n0_rp + 1)) resource pack ($n0_rp + 1 dal catalogo), ne ha $n"
              shot "catalogo: resource pack installato"
            else
              shot "catalogo: resource pack non installato"
              fail "CERCA ONLINE: il resource pack scaricato non è nella cartella resourcepacks del profilo"
            fi
          else
            shot "catalogo: resource pack non pronto"
            fail "CERCA ONLINE: il resource pack scaricato non risulta «Pronto» nel MOD MANAGER"
          fi
        else
          fail "CERCA ONLINE: dopo lo scarico del resource pack manca «AGGIUNGI AL PROFILO»"
        fi
      else
        shot "catalogo: resource pack non scaricato"
        fail "CERCA ONLINE: il resource pack non risulta «Scaricato e verificato» entro 90 s"
      fi
    else
      shot "catalogo: resource pack non trovato"
      fail "CERCA ONLINE: il piano per il resource pack «texture-dal-catalogo» non compare"
    fi
  else
    fail "CERCA ONLINE: il pulsante «Resource pack» non si trova"
  fi

  # ================================================================== il registro del server: l'app si è presentata sempre, e solo con richieste di lettura
  bad=$(mr_requests /modrinth/v2 | awk -F'|' '$3 !~ /^MinecraftModInstaller\//' | head -n 1)
  [ -z "$bad" ] || fail "CERCA ONLINE: una richiesta all'API non porta lo User-Agent dell'app: $bad"
  n=$(mr_requests /modrinth/v2 | wc -l)
  log "il finto Modrinth ha ricevuto $n richieste all'API e $(mr_requests /modrinth-cdn/ | wc -l) ai file"
  [ "$n" -ge 10 ] || fail "CERCA ONLINE: il server ha ricevuto solo $n richieste all'API (ne attendevo almeno 10)"

  # ================================================================== pulizia: le schede dei download di questa fase
  for n in 1 2 3 4 5 6; do dl_remove_card > /dev/null 2>&1 || break; done
  f=$(dl_device_ls | grep -v '^$' | tr '\n' ' ')
  [ -z "$f" ] || fail "CERCA ONLINE: dopo aver tolto le schede dei download restano file nella cartella dei download: $f"
}

# Quando la fase gira da sola l'app è appena installata: si prepara, con gli stessi passi dell'utente (selettore di sistema, «CREA
# PROFILO», «INSTALLA»), il profilo Fabric 1.20.1 con le tre cose che al catalogo servono già dentro: una mod Fabric (da cui nasce il
# profilo), la libreria e la mod che la richiede (così «mod-con-libreria» risulta già nel profilo). I passi sono gli stessi che la
# parte «mods» prova a fondo; qui non si ripetono i controlli, ci si ferma al primo intoppo.
catalog_setup() {
  local fab_sha lib_sha dep_sha
  fab_sha=$(sample_sha Mod_Fabric_Esempio.jar); lib_sha=$(sample_sha Libreria_Esempio.jar); dep_sha=$(sample_sha Mod_Con_Dipendenza.jar)
  go_dash
  mods_add Mod_Fabric_Esempio.jar || { fail "CERCA ONLINE: preparazione: non riesco ad aggiungere Mod_Fabric_Esempio.jar"; return 1; }
  $UI wait "Nuovo profilo" 45 || { shot "catalogo: preparazione senza profilo"; fail "CERCA ONLINE: preparazione: l'app non propone il profilo"; return 1; }
  $UI tap "CREA PROFILO" > /dev/null
  mods_wait "Java richiesto dal gioco" 25 || { shot "catalogo: preparazione, profilo non creato"; fail "CERCA ONLINE: preparazione: il profilo non viene creato"; return 1; }
  mods_wait "Pronto" 25 || { shot "catalogo: preparazione, mod non pronta"; fail "CERCA ONLINE: preparazione: la mod Fabric non risulta «Pronta»"; return 1; }
  $UI tap "INSTALLA" > /dev/null
  mods_until 40 mods_has mods "$fab_sha" || { fail "CERCA ONLINE: preparazione: la mod Fabric non è stata installata"; return 1; }
  mods_clear_pending
  mods_add Libreria_Esempio.jar || { fail "CERCA ONLINE: preparazione: non riesco ad aggiungere Libreria_Esempio.jar"; return 1; }
  mods_wait "Pronto" 45 || { shot "catalogo: preparazione, libreria non pronta"; fail "CERCA ONLINE: preparazione: la libreria non risulta «Pronta»"; return 1; }
  $UI tap "INSTALLA" > /dev/null
  mods_until 40 mods_has mods "$lib_sha" || { fail "CERCA ONLINE: preparazione: la libreria non è stata installata"; return 1; }
  mods_clear_pending
  mods_add Mod_Con_Dipendenza.jar || { fail "CERCA ONLINE: preparazione: non riesco ad aggiungere Mod_Con_Dipendenza.jar"; return 1; }
  mods_wait "Pronto" 45 || { shot "catalogo: preparazione, mod non pronta (2)"; fail "CERCA ONLINE: preparazione: la mod con libreria non risulta «Pronta» (la libreria c'è)"; return 1; }
  $UI tap "INSTALLA" > /dev/null
  mods_until 40 mods_has mods "$dep_sha" || { fail "CERCA ONLINE: preparazione: la mod con libreria non è stata installata"; return 1; }
  mods_clear_pending
  log "catalogo: profilo di partenza pronto ($(mods_count "$(mods_ids | head -n 1)" mods) mod)"
}

catalog_tests() {
  phase "CERCA ONLINE (catalogo Modrinth)"
  local pid
  if [ "$PART" = catalog ]; then
    catalog_setup || return
  fi
  pid=$(mods_ids | head -n 1)
  if [ -z "$pid" ]; then
    note "CERCA ONLINE non provato: sul telefono non c'è nessun profilo (la fase MOD MANAGER non l'ha lasciato)"
    return
  fi
  if [ "$(mods_ids | grep -c .)" != 1 ]; then
    note "CERCA ONLINE: sul telefono ci sono $(mods_ids | grep -c .) profili invece di uno: la prova usa il profilo attivo"
  fi
  if ! dl_start_server --modrinth "$SAMPLES"; then
    fail "CERCA ONLINE: il server di prova (finto Modrinth) non parte"
    return
  fi
  # Solo nella build di prova: l'indirizzo del catalogo viene da questo file (si legge alla prima ricerca).
  adb shell "run-as $PKG sh -c 'mkdir -p files/dev && printf %s http://10.0.2.2:$DL_PORT/modrinth/v2 > files/dev/catalog_base.txt'" > /dev/null 2>&1
  if [ "$(adb exec-out run-as $PKG cat files/dev/catalog_base.txt 2>/dev/null | tr -d '\r')" != "http://10.0.2.2:$DL_PORT/modrinth/v2" ]; then
    fail "CERCA ONLINE: non riesco a scrivere sul telefono l'indirizzo del finto Modrinth (files/dev/catalog_base.txt)"
  else
    catalog_body "$pid"
  fi
  kill $DL_PID 2>/dev/null; wait $DL_PID 2>/dev/null; DL_PID=""
  adb shell "run-as $PKG rm -f files/dev/catalog_base.txt" > /dev/null 2>&1
  go_dash
}
