#!/bin/bash
# Fase «MODPACK MANAGER» della prova su emulatore. Non si esegue da sola: la include ci/emulator_smoke.sh e ne usa le funzioni e le
# variabili ($UI, $PKG, $SAMPLES, $OUT, $DL_LOG, fail, note, shot, expect, expect_scroll, only_actions, snap, seen, back, go_dash,
# pick_file, picker_choose, lib_has, pull_lib, dl_*, mods_* (da ci/mods_phase.sh)…).
#
# Parla con il FINTO Modrinth di ci/dl_server.py (--modrinth): i modpack di prova (ci/make_samples.py) scaricano i loro file da
# http://10.0.2.2:8765/modrinth-cdn/... (la build di prova ammette quel server in HTTP; la versione vera solo https). Questa fase NON
# prova siti veri: prova i pulsanti e i risultati dell'app. Le attese sono quelle che il test EmulatorModpackSamplesTest
# (modpack-engine) controlla prima con il lettore, il pianificatore e l'installatore veri sugli STESSI file.
#
# Si prova, scegliendo i file dal selettore di sistema come farebbe l'utente:
#   1. un modpack COMPLETO: anteprima (senza toccare né la rete né i profili), installazione, esito, file nel profilo identici byte per
#      byte (SHA-256), mod solo-server NON scaricata, impostazioni applicate (comuni e solo-client sì, solo-server no), nessun file
#      rimasto tra i download, riga nell'archivio centrale, passaggio al MOD MANAGER e «VERIFICA PROFILO»;
#   2. un modpack INCOMPLETO: un file manca la prima volta (404) e uno è alterato (impronta sbagliata): l'app lo dice, non li mette
#      nel profilo, e «RIPROVA» scarica SOLO ciò che mancava (la mod già arrivata non si riscarica);
#   3. ANNULLAMENTO a metà: sparisce tutto (profilo, copia del modpack, file scaricati);
#   4. un sito non ancora consentito (cdn.modpack-test.invalid, diverso da quello che consente la parte «base»): il consenso
#      (annullato e poi dato) e l'esito quando il file non arriva;
#   5. il pulsante «APRI NEL MODPACK MANAGER» del DOWNLOAD MANAGER, e il nome del profilo che non ripete uno esistente;
#   6. TOGLI DALL'ELENCO (il profilo resta) e i dati ancora presenti dopo la chiusura dell'app.

MP_TXT=/tmp/mmi_modpack_texts.txt
MP_WHAT=""
MP_P1=/modrinth-cdn/data/ModpackPr1/versions/VerPack01
MP_P2=/modrinth-cdn/data/ModpackPr2/versions/VerPack02
MP_PID=""

# ---- l'interfaccia
# Rilegge tutti i testi della pagina, anche quelli sotto lo schermo, in $MP_TXT.
mp_texts() { : > "$MP_TXT"; $UI top > /dev/null 2>&1; $UI collect "$MP_TXT" 8 > /dev/null 2>&1; }
mp_has() { grep -qiF -- "$1" "$MP_TXT"; }   # senza badare alle maiuscole: i titoli di sezione sono in maiuscolo («ANTEPRIMA»)
mp_expect() { local t; for t in "$@"; do mp_has "$t" || fail "MODPACK MANAGER: manca «$t» ($MP_WHAT)"; done; }
mp_absent() { local t; for t in "$@"; do ! mp_has "$t" || fail "MODPACK MANAGER: compare «$t» ma non dovrebbe ($MP_WHAT)"; done; }

# Porta l'app alla pagina MODPACK MANAGER, in cima. Dalla dashboard si tocca la scheda; altrove si torna indietro.
mp_open() {
  local i
  for i in 1 2 3 4 5 6; do
    snap
    if seen "in un'unica app"; then
      $UI tap "MODPACK MANAGER" > /dev/null; sleep 1.5; snap
      if seen "in un'unica app"; then return 1; fi
    fi
    if seen "MODPACK MANAGER" && seen "Indietro" && ! seen "TORNA ALLA DASHBOARD"; then
      $UI top; snap
      if seen "Modpack Modrinth (.mrpack)"; then return 0; fi
    fi
    back
  done
  return 1
}

# Tocca «SCEGLI UN MODPACK» e sceglie il file nel selettore di sistema, come farebbe l'utente.
mp_pick() { # nome-del-file
  local name="$1"
  mp_open || { fail "MODPACK MANAGER: non riesco ad aprire la pagina per scegliere $name"; return 1; }
  $UI tap "SCEGLI UN MODPACK (.mrpack)" || { shot "modpack: pulsante di scelta non trovato"; fail "MODPACK MANAGER: il pulsante «SCEGLI UN MODPACK (.mrpack)» non si trova ($name)"; return 1; }
  sleep 3
  if picker_choose "$name"; then
    sleep 2
    # Con la selezione multipla il selettore può restare aperto e mostrare «Open»: si conferma. Se si è già chiuso, non c'è.
    if $UI has "Open" && ! $UI has "SCEGLI UN MODPACK"; then $UI tap "Open" > /dev/null 2>&1; fi
    return 0
  fi
  shot "selettore senza $name"
  fail "nel selettore di sistema non trovo $name"
  back
  return 1
}

# Aspetta l'anteprima del modpack scelto (la copia e la lettura possono richiedere un po' sull'emulatore lento).
mp_preview() { # etichetta-per-i-messaggi [testo-da-aspettare]
  local want="${2:-Anteprima}"
  if ! $UI wait "$want" 90; then
    shot "modpack: anteprima non comparsa ($1)"
    fail "MODPACK MANAGER: dopo la scelta di $1 non compare l'anteprima (manca «$want»)"
    return 1
  fi
  $UI top; sleep 0.5
  shot "modpack: anteprima $1"
}

# Aspetta l'esito di un'installazione.
mp_outcome() { # secondi
  $UI wait "Esito dell'installazione" "$1" || $UI scan "Esito dell'installazione" 4
}
# Aspetta un testo; se non si vede (la pagina può essere scorsa più in basso, i messaggi stanno in cima) scorre.
mp_wait_text() { $UI wait "$1" "$2" || $UI scan "$1" 4; }

# ---- il telefono
mp_ls_modpacks() { adb shell run-as $PKG ls files/modpacks 2>/dev/null | tr -d '\r' | grep -v '^$'; }
mp_requests() { dl_count "$1"; }
# I modpack nell'archivio centrale: «nome|completo|file-al-loro-posto|file-totali|copia|profilo», uno per riga.
mp_records() {
  pull_lib "$OUT/.lib.tmp" || return 1
  python3 -I - "$OUT/.lib.tmp" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
for r in ((data.get("modpacks") or {}).get("items")) or []:
    print("%s|%s|%s|%s|%s|%s" % (r.get("name"), str(r.get("complete")).lower(), r.get("placedFiles"), r.get("totalFiles"),
                                 r.get("archiveName"), r.get("profileId")))
PY
}
mp_record() { mp_records | awk -F'|' -v n="$1" '$1==n' | head -n 1; }   # nome
mp_no_record() { [ -z "$(mp_record "$1")" ]; }                           # nome
# L'id del profilo di un modpack, preso dall'archivio centrale (non dai file: nella parte «all» le stesse mod stanno anche nei profili
# delle fasi precedenti). Lo mette in $MP_PID se il profilo esiste davvero sul telefono; aspetta fino a 20 secondi.
mp_find_pid() { # nome-del-modpack
  local i pid
  MP_PID=""
  for i in $(seq 1 20); do
    pid=$(mp_record "$1" | cut -d'|' -f6)
    if [ -n "$pid" ] && mods_ids | grep -qxF "$pid"; then MP_PID="$pid"; return 0; fi
    sleep 1
  done
  return 1
}
mp_no_downloads() { [ -z "$(dl_device_ls)" ]; }
# Un file nel profilo è identico all'originale.
mp_same() { # id cartella nome sha descrizione
  local got; got=$(mods_sha "$1" "$2" "$3")
  [ -n "$got" ] && [ "$got" = "$4" ] || fail "MODPACK MANAGER: $5 sul telefono non è identico all'originale (atteso ${4:0:16}…, trovato ${got:0:16}…)"
}
mp_absent_file() { # id cartella nome descrizione
  ! mods_ls "$1" "$2" | grep -qxF "$3" || fail "MODPACK MANAGER: $4 è nel profilo ma non doveva esserci"
}
mp_clean_downloads() { # contesto
  local rest; rest=$(dl_device_ls)
  [ -z "$rest" ] || fail "MODPACK MANAGER: $1: nella cartella dei download restano file: $(echo $rest)"
}

# ======================================================================================================== 1. modpack completo

mp_case_complete() {
  local fab_sha lib_sha tex_sha cfg_sha pid n0 rec archive got f
  fab_sha=$(sample_sha Mod_Fabric_Esempio.jar); lib_sha=$(sample_sha Libreria_Esempio.jar); tex_sha=$(sample_sha Texture_Java.zip)
  cfg_sha=$(printf '%s\n' '{"modpack": "prova", "valore": 42}' | sha256sum | cut -d' ' -f1)
  n0=$(mods_ids | grep -c .)

  # ---- la pagina vuota
  go_dash
  if ! mp_open; then
    shot "modpack: pagina non aperta"
    fail "MODPACK MANAGER: la pagina non si apre dalla dashboard"
    return 1
  fi
  sleep 0.5; shot "modpack: pagina vuota"
  expect "MODPACK MANAGER" "Modpack Modrinth (.mrpack)" "SCEGLI UN MODPACK (.mrpack)"
  only_actions "Indietro" "Apri il menu" "SCEGLI UN MODPACK (.mrpack)"
  MP_WHAT="pagina vuota"; mp_texts
  mp_expect "Modpack installati" "Nessun modpack installato da qui." "Cosa fa e cosa no" "Quelli di CurseForge no" \
    "Non avvia Minecraft Java" "non può sapere se il modpack parte"
  mp_absent "Esito dell'installazione" "INSTALLA IL MODPACK"

  # ---- scelta del file e anteprima: non deve toccare né la rete né i profili
  mp_pick Modpack_Prova.mrpack || return 1
  mp_preview Modpack_Prova.mrpack || return 1
  MP_WHAT="anteprima del modpack completo"; mp_texts
  mp_expect "Anteprima" "Modpack di Prova" "1.0.0" "Pronto" "Minecraft 1.20.1" "Fabric 0.15.11" \
    "Un modpack inventato per la prova automatica" "File da scaricare" "Solo per server (non scaricati)" \
    "Impostazioni da applicare" "Nome del nuovo profilo" "INSTALLA IL MODPACK" "SCARTA"
  mp_absent "un sito che non hai ancora consentito" "Poco spazio libero" "Non installabile" "Già al loro posto"
  [ "$(mods_ids | grep -c .)" = "$n0" ] || fail "MODPACK MANAGER: la sola anteprima ha già creato un profilo"
  [ "$(mp_requests $MP_P1/Mod_Fabric_Esempio.jar)" = 0 ] || fail "MODPACK MANAGER: la sola anteprima ha già scaricato dei file"
  mp_no_record "Modpack di Prova" || fail "MODPACK MANAGER: la sola anteprima ha già messo il modpack nell'archivio centrale"
  mp_clean_downloads "anteprima"

  # ---- SCARTA toglie l'anteprima e non lascia nulla; poi si sceglie di nuovo
  $UI top
  if $UI tap-exact "SCARTA"; then
    sleep 1; snap
    if seen "INSTALLA IL MODPACK"; then fail "MODPACK MANAGER: dopo SCARTA l'anteprima è ancora in pagina"; fi
  else
    fail "MODPACK MANAGER: il pulsante SCARTA non si trova"
  fi
  mp_pick Modpack_Prova.mrpack || return 1
  mp_preview Modpack_Prova.mrpack || return 1

  # ---- installazione
  $UI tap "INSTALLA IL MODPACK" || { shot "modpack: INSTALLA non trovato"; fail "MODPACK MANAGER: il pulsante «INSTALLA IL MODPACK» non si trova"; return 1; }
  if ! mp_outcome 240; then
    shot "modpack: nessun esito"
    fail "MODPACK MANAGER: dopo INSTALLA IL MODPACK non compare l'esito dell'installazione"
    return 1
  fi
  $UI top; sleep 0.5; shot "modpack: esito completo"
  MP_WHAT="esito del modpack completo"; mp_texts
  mp_expect "Esito dell'installazione" "Modpack di Prova" "Tutti i 4 file del modpack sono nel profilo" \
    "non garantisce che il gioco parta" "Registrati nel MOD MANAGER" "Impostazioni applicate" "Solo per server (non scaricati)" \
    "L'app non avvia Minecraft" "APRI NEL MOD MANAGER" "CHIUDI" \
    "Modpack installati" "4 di 4 file al loro posto" "Profilo: «Modpack di Prova»" "APRI IL PROFILO" "TOGLI DALL'ELENCO"
  mp_absent "il modpack è incompleto" "Nessun modpack installato da qui." "File che non sono arrivati"

  # ---- cosa c'è davvero sul telefono
  mp_find_pid "Modpack di Prova" || { fail "MODPACK MANAGER: nell'archivio centrale non c'è il modpack con un profilo esistente sul telefono"; return 1; }
  pid=$MP_PID
  [ "$(mods_ids | grep -c .)" = $((n0 + 1)) ] || fail "MODPACK MANAGER: dopo l'installazione i profili sul telefono sono $(mods_ids | grep -c .) invece di $((n0 + 1))"
  mp_same "$pid" mods Mod_Fabric_Esempio.jar "$fab_sha" "mods/Mod_Fabric_Esempio.jar"
  mp_same "$pid" mods Libreria_Esempio.jar "$lib_sha" "mods/Libreria_Esempio.jar"
  mp_same "$pid" resourcepacks Texture_Java.zip "$tex_sha" "resourcepacks/Texture_Java.zip"
  mp_same "$pid" config prova-modpack.json "$cfg_sha" "config/prova-modpack.json"
  got=$(adb exec-out run-as $PKG cat "files/java/profiles/$pid/config/impostazioni-comuni.txt" 2>/dev/null | tr -d '\r')
  [ "$got" = "impostazione comune a tutti" ] || fail "MODPACK MANAGER: config/impostazioni-comuni.txt non ha il contenuto del modpack (trovato «$got»)"
  got=$(adb exec-out run-as $PKG cat "files/java/profiles/$pid/options.txt" 2>/dev/null | tr -d '\r')
  [ "$got" = "guiScale:2" ] || fail "MODPACK MANAGER: options.txt (impostazione solo-client) non ha il contenuto del modpack (trovato «$got»)"
  mp_absent_file "$pid" mods Solo_Server.jar "mods/Solo_Server.jar (solo per server)"
  mp_absent_file "$pid" . server.properties "server.properties (impostazione solo-server)"
  [ "$(mods_count "$pid" mods)" = 2 ] || fail "MODPACK MANAGER: nella cartella mods ci sono $(mods_count "$pid" mods) file invece di 2"
  mp_clean_downloads "dopo il modpack completo"
  # Il server ha visto UNA richiesta per ogni file e nessuna per la mod solo-server.
  for f in Mod_Fabric_Esempio.jar Libreria_Esempio.jar Texture_Java.zip Config_Modpack.json; do
    [ "$(mp_requests $MP_P1/$f)" = 1 ] || fail "MODPACK MANAGER: il server ha ricevuto $(mp_requests $MP_P1/$f) richieste per $f invece di una"
  done
  [ "$(mp_requests $MP_P1/Solo_Server.jar)" = 0 ] || fail "MODPACK MANAGER: l'app ha scaricato la mod solo-server (non serve su un telefono)"
  # L'archivio centrale e la copia conservata del modpack.
  rec=$(mp_record "Modpack di Prova")
  [ "$(echo "$rec" | cut -d'|' -f2-4)" = "true|4|4" ] || fail "MODPACK MANAGER: l'archivio dice «$(echo "$rec" | cut -d'|' -f2-4)» invece di completo, 4 file su 4"
  archive=$(echo "$rec" | cut -d'|' -f5)
  got=$(adb exec-out run-as $PKG cat "files/modpacks/$archive" 2>/dev/null | sha256sum | cut -d' ' -f1)
  [ "$got" = "$(sample_sha Modpack_Prova.mrpack)" ] || fail "MODPACK MANAGER: la copia conservata del modpack ($archive) non è identica al file scelto"

  # ---- passaggio al MOD MANAGER e verifica del profilo
  if $UI tap "APRI NEL MOD MANAGER"; then
    if mods_wait "Java richiesto dal gioco" 25; then
      $UI top; sleep 0.5; shot "modpack: profilo nel MOD MANAGER"
      expect "MOD MANAGER" "Modpack di Prova" "Fabric 0.15.11" "Minecraft 1.20.1"
      if $UI tap "VERIFICA PROFILO"; then
        if mods_wait "Nessun problema trovato" 25; then
          $UI scan "Controllati 3 file attivi" 6 || fail "MODPACK MANAGER: la verifica del profilo doveva controllare 3 file attivi"
          shot "modpack: verifica del profilo"
        else
          shot "modpack: verifica con problemi"
          fail "MODPACK MANAGER: «VERIFICA PROFILO» sul profilo del modpack non dice «Nessun problema trovato»"
        fi
      else
        fail "MODPACK MANAGER: nel MOD MANAGER manca il pulsante VERIFICA PROFILO"
      fi
    else
      shot "modpack: MOD MANAGER non aperto"
      fail "MODPACK MANAGER: «APRI NEL MOD MANAGER» non porta al profilo del modpack"
    fi
  else
    fail "MODPACK MANAGER: il pulsante «APRI NEL MOD MANAGER» non si trova"
  fi

  # ---- ritorno: l'esito resta finché non lo si chiude
  mp_open || { fail "MODPACK MANAGER: non riesco a tornare alla pagina"; return 1; }
  if $UI scan "Esito dell'installazione" 6; then
    $UI top
    $UI tap-exact "CHIUDI" > /dev/null || fail "MODPACK MANAGER: il pulsante CHIUDI dell'esito non si trova"
    sleep 1; snap
    if seen "Esito dell'installazione"; then fail "MODPACK MANAGER: dopo CHIUDI l'esito è ancora in pagina"; fi
  else
    note "MODPACK MANAGER: tornando alla pagina l'esito non c'è più (di per sé non è un errore)"
  fi
  MP_WHAT="dopo CHIUDI"; mp_texts
  mp_expect "Modpack installati" "4 di 4 file al loro posto" "Profilo: «Modpack di Prova»"
  mp_absent "Esito dell'installazione"
  return 0
}

# ======================================================================================================== 2. modpack incompleto e ripresa

mp_case_incomplete() {
  local cat_sha libcat_sha pid rec
  cat_sha=$(sample_sha Mod_Catalogo.jar); libcat_sha=$(sample_sha Libreria_Catalogo.jar)

  mp_pick Modpack_Incompleto.mrpack || return 1
  mp_preview Modpack_Incompleto.mrpack || return 1
  MP_WHAT="anteprima del modpack incompleto"; mp_texts
  # Dal solo indice non si può sapere che un file mancherà o è alterato: per l'app è «Pronto».
  mp_expect "Anteprima" "Modpack Incompleto" "0.1.0" "Pronto" "Nome del nuovo profilo" "INSTALLA IL MODPACK"
  $UI tap "INSTALLA IL MODPACK" || { fail "MODPACK MANAGER: il pulsante «INSTALLA IL MODPACK» non si trova (modpack incompleto)"; return 1; }
  if ! mp_outcome 240; then
    shot "modpack: nessun esito (incompleto)"
    fail "MODPACK MANAGER: il modpack incompleto non mostra alcun esito"
    return 1
  fi
  $UI top; sleep 0.5; shot "modpack: esito incompleto"
  MP_WHAT="esito del modpack incompleto"; mp_texts
  mp_expect "Esito dell'installazione" "Modpack Incompleto" "1 file su 3 sono al loro posto: il modpack è incompleto" \
    "File che non sono arrivati (2)" "mods/Libreria_Catalogo.jar" "mods/Mod_Impronta_Sbagliata.jar" \
    "RIPROVA I FILE MANCANTI" "APRI NEL MOD MANAGER" "1 di 3 file al loro posto"
  mp_absent "Tutti i 3 file del modpack sono nel profilo"

  mp_find_pid "Modpack Incompleto" || { fail "MODPACK MANAGER: nell'archivio centrale non c'è il modpack incompleto con un profilo esistente"; return 1; }
  pid=$MP_PID
  mp_same "$pid" mods Mod_Catalogo.jar "$cat_sha" "mods/Mod_Catalogo.jar (modpack incompleto)"
  mp_absent_file "$pid" mods Libreria_Catalogo.jar "mods/Libreria_Catalogo.jar (non era ancora arrivata)"
  mp_absent_file "$pid" mods Mod_Impronta_Sbagliata.jar "mods/Mod_Impronta_Sbagliata.jar (impronta sbagliata: va scartato)"
  [ "$(mods_count "$pid" mods)" = 1 ] || fail "MODPACK MANAGER: nel profilo del modpack incompleto ci sono $(mods_count "$pid" mods) mod invece di una"
  mp_clean_downloads "dopo il modpack incompleto"
  rec=$(mp_record "Modpack Incompleto")
  [ "$(echo "$rec" | cut -d'|' -f2-4)" = "false|1|3" ] || fail "MODPACK MANAGER: l'archivio dice «$(echo "$rec" | cut -d'|' -f2-4)» invece di incompleto, 1 file su 3"
  [ "$(mp_requests $MP_P2/Mod_Catalogo.jar)" = 1 ] || fail "MODPACK MANAGER: Mod_Catalogo.jar richiesto $(mp_requests $MP_P2/Mod_Catalogo.jar) volte invece di una"
  [ "$(mp_requests $MP_P2/Libreria_Catalogo.jar)" = 1 ] || fail "MODPACK MANAGER: Libreria_Catalogo.jar richiesta $(mp_requests $MP_P2/Libreria_Catalogo.jar) volte invece di una (prima richiesta: 404)"

  # ---- ripresa dall'esito: l'anteprima dice cosa c'è già e cosa manca
  if ! $UI tap "RIPROVA I FILE MANCANTI"; then
    fail "MODPACK MANAGER: il pulsante «RIPROVA I FILE MANCANTI» non si trova"
    return 1
  fi
  mp_preview "il completamento del modpack incompleto" "Da completare" || return 1
  MP_WHAT="anteprima del completamento"; mp_texts
  mp_expect "Da completare" "Modpack Incompleto" "Già al loro posto" "File da scaricare" "Si completa il profilo" \
    "COMPLETA L'INSTALLAZIONE" "SCARTA"
  mp_absent "Nome del nuovo profilo" "Esito dell'installazione"
  $UI tap "COMPLETA L'INSTALLAZIONE" || { fail "MODPACK MANAGER: il pulsante «COMPLETA L'INSTALLAZIONE» non si trova"; return 1; }
  if ! mp_outcome 240; then
    shot "modpack: nessun esito (ripresa)"
    fail "MODPACK MANAGER: il completamento non mostra alcun esito"
    return 1
  fi
  $UI top; sleep 0.5; shot "modpack: esito dopo la ripresa"
  MP_WHAT="esito dopo la ripresa"; mp_texts
  mp_expect "Esito dell'installazione" "2 file su 3 sono al loro posto: il modpack è incompleto" "File che non sono arrivati (1)" \
    "mods/Mod_Impronta_Sbagliata.jar" "2 di 3 file al loro posto"
  mp_absent "mods/Libreria_Catalogo.jar:" "File che non sono arrivati (2)"
  mp_same "$pid" mods Mod_Catalogo.jar "$cat_sha" "mods/Mod_Catalogo.jar (dopo la ripresa)"
  mp_same "$pid" mods Libreria_Catalogo.jar "$libcat_sha" "mods/Libreria_Catalogo.jar (arrivata alla ripresa)"
  mp_absent_file "$pid" mods Mod_Impronta_Sbagliata.jar "mods/Mod_Impronta_Sbagliata.jar (ancora alterato: va scartato)"
  [ "$(mods_ids | grep -c "^$pid\$")" = 1 ] || fail "MODPACK MANAGER: la ripresa ha cambiato i profili invece di completare quello esistente"
  mp_clean_downloads "dopo la ripresa"
  # La ripresa scarica SOLO ciò che mancava: la mod già al suo posto non si riscarica; la libreria è stata chiesta la seconda volta.
  [ "$(mp_requests $MP_P2/Mod_Catalogo.jar)" = 1 ] || fail "MODPACK MANAGER: la ripresa ha riscaricato Mod_Catalogo.jar (richieste: $(mp_requests $MP_P2/Mod_Catalogo.jar), attesa: 1)"
  [ "$(mp_requests $MP_P2/Libreria_Catalogo.jar)" = 2 ] || fail "MODPACK MANAGER: Libreria_Catalogo.jar richiesta $(mp_requests $MP_P2/Libreria_Catalogo.jar) volte invece di due"
  rec=$(mp_record "Modpack Incompleto")
  [ "$(echo "$rec" | cut -d'|' -f2-4)" = "false|2|3" ] || fail "MODPACK MANAGER: dopo la ripresa l'archivio dice «$(echo "$rec" | cut -d'|' -f2-4)» invece di incompleto, 2 file su 3"

  # ---- il pulsante COMPLETA dell'elenco porta alla stessa anteprima (dopo CHIUDI, l'esito non c'è più)
  $UI top
  $UI tap-exact "CHIUDI" > /dev/null
  sleep 1
  if $UI scan "Modpack Incompleto" 6 > /dev/null && $UI tap-exact "COMPLETA"; then
    mp_preview "COMPLETA dall'elenco" "Da completare" || return 1
    MP_WHAT="anteprima da COMPLETA"; mp_texts
    mp_expect "Da completare" "Modpack Incompleto" "Già al loro posto" "COMPLETA L'INSTALLAZIONE"
    $UI top
    $UI tap-exact "SCARTA" > /dev/null || fail "MODPACK MANAGER: il pulsante SCARTA non si trova (completamento dall'elenco)"
    sleep 1
  else
    shot "modpack: COMPLETA non trovato"
    fail "MODPACK MANAGER: nell'elenco il modpack incompleto non ha il pulsante COMPLETA"
  fi
  return 0
}

# ======================================================================================================== 3. annullamento a metà

mp_case_cancel() {
  local n0 m0
  n0=$(mods_ids | grep -c .); m0=$(mp_ls_modpacks | grep -c .)
  mp_pick Modpack_Lento.mrpack || return 1
  mp_preview Modpack_Lento.mrpack || return 1
  MP_WHAT="anteprima del modpack lento"; mp_texts
  mp_expect "Modpack Lento" "Pronto" "INSTALLA IL MODPACK"
  $UI tap "INSTALLA IL MODPACK" || { fail "MODPACK MANAGER: il pulsante «INSTALLA IL MODPACK» non si trova (modpack lento)"; return 1; }
  # Il file «incagliato» tiene l'installazione in corso: si aspetta lo scarico e si annulla dalla schermata di avanzamento.
  if ! $UI wait "Scarico i file" 120; then
    shot "modpack: avanzamento non comparso"
    fail "MODPACK MANAGER: durante l'installazione non compare l'avanzamento («Scarico i file»)"
    return 1
  fi
  sleep 3; shot "modpack: avanzamento dello scarico"
  expect "Installo «Modpack Lento»" "Annulla"
  # Il tasto Indietro non lascia la schermata durante l'operazione (si interrompe solo con Annulla).
  back; snap
  seen "Scarico i file" || fail "MODPACK MANAGER: il tasto Indietro ha interrotto o lasciato l'installazione in corso"
  $UI tap "Annulla" || { fail "MODPACK MANAGER: il pulsante Annulla non si trova durante l'installazione"; return 1; }
  if ! mp_wait_text "Installazione annullata" 60; then
    shot "modpack: annullamento senza messaggio"
    fail "MODPACK MANAGER: dopo Annulla non compare «Installazione annullata»"
    return 1
  fi
  sleep 1; shot "modpack: annullato"
  # L'anteprima resta (si può riprovare o scartare); nell'elenco dei modpack installati non c'è.
  MP_WHAT="dopo l'annullamento"; mp_texts
  mp_expect "Installazione annullata: non è cambiato nulla." "Modpack Lento" "INSTALLA IL MODPACK"
  mp_absent "Esito dell'installazione" "0 di 2 file al loro posto" "1 di 2 file al loro posto"
  # Sul telefono non resta nulla: né il profilo, né la copia del modpack, né i file (nemmeno parziali) tra i download.
  [ "$(mods_ids | grep -c .)" = "$n0" ] || fail "MODPACK MANAGER: dopo l'annullamento i profili sul telefono sono $(mods_ids | grep -c .) invece di $n0"
  [ "$(mp_ls_modpacks | grep -c .)" = "$m0" ] || fail "MODPACK MANAGER: dopo l'annullamento nelle copie dei modpack ci sono $(mp_ls_modpacks | grep -c .) file invece di $m0"
  mp_no_record "Modpack Lento" || fail "MODPACK MANAGER: dopo l'annullamento il modpack è ancora nell'archivio centrale"
  # Con la rete lenta il file parziale può metterci un attimo a sparire: si aspetta.
  mods_until 20 mp_no_downloads || mp_clean_downloads "dopo l'annullamento"
  # SCARTA toglie l'anteprima.
  $UI top
  $UI tap-exact "SCARTA" > /dev/null || fail "MODPACK MANAGER: il pulsante SCARTA non si trova dopo l'annullamento"
  sleep 1
  MP_WHAT="dopo SCARTA (modpack lento)"; mp_texts
  mp_absent "Modpack Lento"
  return 0
}

# ======================================================================================================== 4. sito non ancora consentito

mp_case_consent() {
  local rec
  mp_pick Modpack_Altro_Sito.mrpack || return 1
  mp_preview Modpack_Altro_Sito.mrpack || return 1
  MP_WHAT="anteprima con un sito da consentire"; mp_texts
  mp_expect "Modpack Altro Sito" "Incompleto" "1 file si scarica da «cdn.modpack-test.invalid», un sito che non hai ancora consentito." \
    "CONSENTI «cdn.modpack-test.invalid»" "Senza questi file il modpack resta incompleto"
  if ! $UI tap "CONSENTI «cdn.modpack-test.invalid»"; then
    shot "modpack: pulsante di consenso non trovato"
    fail "MODPACK MANAGER: il pulsante «CONSENTI «cdn.modpack-test.invalid»» non si trova"
    return 1
  fi
  if ! $UI wait "Consentire i download da" 15; then
    shot "modpack: finestra di consenso non comparsa"
    fail "MODPACK MANAGER: toccando CONSENTI non compare la finestra «Consentire i download da…»"
    return 1
  fi
  sleep 0.5; shot "modpack: finestra di consenso"
  expect "Consentire i download da «cdn.modpack-test.invalid»?" "CONSENTI E CONTINUA" "ANNULLA"
  # ANNULLA: il sito non viene consentito.
  $UI tap-last "ANNULLA" > /dev/null; sleep 1.5; snap
  if seen "Consentire i download da"; then fail "MODPACK MANAGER: ANNULLA non chiude la finestra di consenso"; fi
  lib_hosts | grep -qF "cdn.modpack-test.invalid" && fail "MODPACK MANAGER: dopo ANNULLA il sito risulta comunque consentito"
  # Di nuovo, e questa volta si consente.
  $UI tap "CONSENTI «cdn.modpack-test.invalid»" > /dev/null || { fail "MODPACK MANAGER: dopo ANNULLA il pulsante di consenso non c'è più"; return 1; }
  $UI wait "Consentire i download da" 15 > /dev/null
  $UI tap "CONSENTI E CONTINUA" > /dev/null || { fail "MODPACK MANAGER: il pulsante «CONSENTI E CONTINUA» non si trova"; return 1; }
  sleep 2; $UI top; snap
  mods_until 15 lib_hosts_has cdn.modpack-test.invalid || fail "MODPACK MANAGER: dopo CONSENTI E CONTINUA il sito non è tra quelli consentiti nell'archivio"
  shot "modpack: dopo il consenso"
  MP_WHAT="anteprima dopo il consenso"; mp_texts
  mp_expect "Modpack Altro Sito" "Pronto" "INSTALLA IL MODPACK"
  mp_absent "un sito che non hai ancora consentito" "CONSENTI «cdn.modpack-test.invalid»"
  # Il sito non esiste (.invalid): il file non arriva e l'app lo dice invece di fingere un successo.
  $UI tap "INSTALLA IL MODPACK" || { fail "MODPACK MANAGER: il pulsante «INSTALLA IL MODPACK» non si trova (sito consentito)"; return 1; }
  if ! mp_outcome 240; then
    shot "modpack: nessun esito (sito)"
    fail "MODPACK MANAGER: il modpack dal sito consentito ma irraggiungibile non mostra alcun esito"
    return 1
  fi
  $UI top; sleep 0.5; shot "modpack: esito sito irraggiungibile"
  MP_WHAT="esito con il sito irraggiungibile"; mp_texts
  mp_expect "Esito dell'installazione" "Modpack Altro Sito" "0 file su 1 sono al loro posto: il modpack è incompleto" \
    "File che non sono arrivati (1)" "mods/Mod_Altro_Sito.jar" "0 di 1 file al loro posto"
  mp_absent "Tutti i 1 file del modpack sono nel profilo"
  rec=$(mp_record "Modpack Altro Sito")
  [ "$(echo "$rec" | cut -d'|' -f2-4)" = "false|0|1" ] || fail "MODPACK MANAGER: l'archivio dice «$(echo "$rec" | cut -d'|' -f2-4)» invece di incompleto, 0 file su 1"
  mp_clean_downloads "dopo il sito irraggiungibile"
  return 0
}
lib_hosts_has() { lib_hosts | tr ' ' '\n' | grep -qxF "$1"; }

# ======================================================================================================== 5. dal DOWNLOAD MANAGER

mp_case_download() {
  local n0
  n0=$(mods_ids | grep -c .)
  # Il modpack scaricato come un file qualsiasi: nel DOWNLOAD MANAGER compare il pulsante per aprirlo nel MODPACK MANAGER.
  dl_add "$DL_BASE/modrinth-cdn/data/ModpackFile/Modpack_Prova.mrpack" || return 1
  if ! dl_wait "Completato" 90; then
    shot "modpack: download del modpack non completato"
    fail "MODPACK MANAGER: il download del file .mrpack nel DOWNLOAD MANAGER non si completa"
    return 1
  fi
  sleep 0.5; shot "modpack: modpack scaricato"
  expect "Modpack_Prova.mrpack"
  no_action "AGGIUNGI AL MOD MANAGER" || fail "DOWNLOAD MANAGER: un .mrpack non dovrebbe avere il pulsante AGGIUNGI AL MOD MANAGER"
  no_action "APRI NELL'INSTALLER BEDROCK" || fail "DOWNLOAD MANAGER: un .mrpack non dovrebbe avere il pulsante APRI NELL'INSTALLER BEDROCK"
  if ! $UI tap "APRI NEL MODPACK MANAGER"; then
    fail "DOWNLOAD MANAGER: un .mrpack scaricato non ha il pulsante «APRI NEL MODPACK MANAGER»"
    return 1
  fi
  mp_preview "il modpack aperto dal DOWNLOAD MANAGER" || return 1
  MP_WHAT="anteprima dal DOWNLOAD MANAGER"; mp_texts
  expect "MODPACK MANAGER"
  # «Modpack di Prova» esiste già come profilo: il nome proposto per quello nuovo è diverso.
  mp_expect "Anteprima" "Modpack di Prova" "Modpack di Prova 2" "INSTALLA IL MODPACK"
  [ "$(mods_ids | grep -c .)" = "$n0" ] || fail "MODPACK MANAGER: aprire il modpack dal DOWNLOAD MANAGER ha già creato un profilo"
  $UI top
  $UI tap-exact "SCARTA" > /dev/null || fail "MODPACK MANAGER: il pulsante SCARTA non si trova (modpack dal DOWNLOAD MANAGER)"
  sleep 1
  # Il DOWNLOAD MANAGER non conserva nulla di quanto hanno scaricato i modpack: c'è solo il file scaricato a mano.
  dl_open || return 1
  dl_remove_card || fail "DOWNLOAD MANAGER: non riesco a eliminare il .mrpack scaricato"
  if [ "$PART" = modpack ]; then
    dl_open && $UI scan "Nessun download" 4 > /dev/null ||
      fail "DOWNLOAD MANAGER: dopo i modpack l'elenco non è vuoto (un'installazione di modpack ha lasciato dei download)"
  fi
  mp_find_pid "Modpack di Prova" || fail "MODPACK MANAGER: il profilo del modpack completo non c'è più"
  return 0
}

# ======================================================================================================== 6. togli dall'elenco e dati dopo la chiusura

mp_case_remove_and_restart() {
  local rec archive n_prof
  rec=$(mp_record "Modpack Altro Sito"); archive=$(echo "$rec" | cut -d'|' -f5)
  n_prof=$(mods_ids | grep -c .)
  mp_open || { fail "MODPACK MANAGER: non riesco a tornare alla pagina"; return 1; }
  # Chiude l'eventuale esito aperto, poi toglie dall'elenco «Modpack Altro Sito».
  $UI top; $UI tap-exact "CHIUDI" > /dev/null 2>&1; sleep 1
  MP_WHAT="prima di TOGLI DALL'ELENCO"; mp_texts
  mp_expect "Modpack di Prova" "Modpack Incompleto" "Modpack Altro Sito" "0 di 1 file al loro posto"
  mp_absent "Modpack Lento"
  # I pulsanti TOGLI DALL'ELENCO sono uno per modpack, nello stesso ordine dell'elenco (il più recente per primo): quello di
  # «Modpack Altro Sito» è il primo.
  if $UI scan "Modpack Altro Sito" 6 > /dev/null && $UI tap "TOGLI DALL'ELENCO"; then
    if $UI wait "Togliere il modpack dall'elenco?" 15; then
      sleep 0.5; shot "modpack: conferma per togliere"
      if ! $UI has-in "$LAST_UI" "«Modpack Altro Sito» esce da questo elenco"; then
        # Il pulsante toccato era di un altro modpack: non si toglie nulla.
        $UI tap-last "ANNULLA" > /dev/null
        fail "MODPACK MANAGER: il primo TOGLI DALL'ELENCO non era quello di «Modpack Altro Sito» (l'elenco non parte dal più recente?)"
        return 1
      fi
      expect "Togliere il modpack dall'elenco?" "Il profilo e i suoi file restano nel MOD MANAGER" "TOGLI DALL'ELENCO" "ANNULLA"
      # ANNULLA non toglie nulla.
      $UI tap-last "ANNULLA" > /dev/null; sleep 1.5
      [ -n "$(mp_record "Modpack Altro Sito")" ] || fail "MODPACK MANAGER: ANNULLA nella finestra ha tolto comunque il modpack dall'elenco"
      $UI tap "TOGLI DALL'ELENCO" > /dev/null
      $UI wait "Togliere il modpack dall'elenco?" 15 > /dev/null
      $UI tap-last "TOGLI DALL'ELENCO" > /dev/null
      if mp_wait_text "tolto dall'elenco" 20; then
        shot "modpack: tolto dall'elenco"
        MP_WHAT="dopo TOGLI DALL'ELENCO"; mp_texts
        mp_expect "«Modpack Altro Sito» tolto dall'elenco. Il profilo e i suoi file restano nel MOD MANAGER."
        mp_absent "0 di 1 file al loro posto"
      else
        shot "modpack: nessun messaggio dopo TOGLI"
        fail "MODPACK MANAGER: dopo la conferma non compare il messaggio «tolto dall'elenco»"
      fi
      mods_until 20 mp_no_record "Modpack Altro Sito" || fail "MODPACK MANAGER: il modpack è ancora nell'archivio centrale dopo TOGLI DALL'ELENCO"
      if [ -n "$archive" ] && mp_ls_modpacks | grep -qxF "$archive"; then
        fail "MODPACK MANAGER: la copia conservata del modpack ($archive) non è stata cancellata"
      fi
      [ "$(mods_ids | grep -c .)" = "$n_prof" ] || fail "MODPACK MANAGER: TOGLI DALL'ELENCO ha cambiato i profili sul telefono ($(mods_ids | grep -c .) invece di $n_prof)"
    else
      shot "modpack: finestra TOGLI non comparsa"
      fail "MODPACK MANAGER: TOGLI DALL'ELENCO non apre la finestra di conferma"
    fi
  else
    shot "modpack: TOGLI non trovato"
    fail "MODPACK MANAGER: nell'elenco non trovo il pulsante TOGLI DALL'ELENCO di «Modpack Altro Sito»"
  fi

  # ---- chiusura improvvisa dell'app: i modpack e i profili restano
  adb shell am force-stop $PKG; sleep 2
  adb shell am start -W -n $PKG/.MainActivity > /dev/null 2>&1; sleep 4
  go_dash
  if mp_open; then
    sleep 1; MP_WHAT="dopo la chiusura dell'app"; mp_texts; shot "modpack: dopo la chiusura dell'app"
    mp_expect "Modpack di Prova" "4 di 4 file al loro posto" "Modpack Incompleto" "2 di 3 file al loro posto"
    mp_absent "Modpack Altro Sito" "Modpack Lento"
  else
    fail "MODPACK MANAGER: dopo la chiusura dell'app la pagina non si apre"
  fi
  mp_find_pid "Modpack Incompleto" || fail "MODPACK MANAGER: dopo la chiusura dell'app il profilo del modpack incompleto non c'è più"
  mp_same "$MP_PID" mods Libreria_Catalogo.jar "$(sample_sha Libreria_Catalogo.jar)" "mods/Libreria_Catalogo.jar (dopo la chiusura dell'app)"
  return 0
}

# ======================================================================================================== la fase

# I gesti di scorrimento partono sopra la tastiera a schermo (vedi ui.py, MMI_SWIPE_SAFE) per tutta la durata di questa fase.
modpack_tests() {
  local r
  export MMI_SWIPE_SAFE=1
  modpack_tests_inner; r=$?
  unset MMI_SWIPE_SAFE
  return $r
}

modpack_tests_inner() {
  phase "MODPACK MANAGER"
  if ! dl_start_server --modrinth "$SAMPLES"; then
    fail "MODPACK MANAGER: il server di prova (finto Modrinth) non parte"
    return
  fi
  go_dash
  mp_case_complete || { go_dash; }
  mp_case_incomplete || { go_dash; }
  mp_case_cancel || { go_dash; }
  mp_case_consent || { go_dash; }
  mp_case_download || { go_dash; }
  mp_case_remove_and_restart || { go_dash; }
  kill $DL_PID 2>/dev/null; wait $DL_PID 2>/dev/null; DL_PID=""
  go_dash
}
