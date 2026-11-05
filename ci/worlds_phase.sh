#!/bin/bash
# Fase «WORLD MANAGER» della prova su emulatore. Non si esegue da sola: la include ci/emulator_smoke.sh e ne usa le funzioni e le
# variabili ($UI, $PKG, $SAMPLES, $OUT, fail, note, shot, expect, only_actions, snap, seen, back, go_dash, picker_choose,
# mods_* e zip_member_sha da ci/mods_phase.sh…).
#
# I mondi sono quelli di ci/make_samples.py (Mondo_Java.zip, Mondo_Java_Recente.zip, Mondo_Java_Grande.zip e il .mcworld Bedrock);
# le attese sono quelle che EmulatorWorldSamplesTest (world-engine) controlla prima con il motore vero sugli STESSI file.
# Si prova, scegliendo i file dal selettore di sistema come farebbe l'utente:
#   1. un mondo Java: dettagli letti senza estrarre, importazione nel profilo, file identici byte per byte (SHA-256), session.lock
#      e il file fuori dal mondo non copiati, nessuna cartella di lavoro rimasta, copia dell'archivio cancellata, ESPORTA (ZIP);
#   2. un mondo più recente del profilo: l'avviso prima di importare, e SCARTA che non lascia nulla;
#   3. un mondo grande con l'app CHIUSA A METÀ dell'importazione: la riapertura mostra l'importazione interrotta e RIPRENDI la
#      completa senza ricopiare i file già fatti;
#   4. un mondo Bedrock passato all'INSTALLER BEDROCK;
#   5. ELIMINA di un mondo dal profilo.

WD_TXT=/tmp/mmi_worlds_texts.txt
WD_WHAT=""

# ---- l'interfaccia
wd_texts() { : > "$WD_TXT"; $UI top > /dev/null 2>&1; $UI collect "$WD_TXT" 8 > /dev/null 2>&1; }
wd_has() { grep -qiF -- "$1" "$WD_TXT"; }
wd_expect() { local t; for t in "$@"; do wd_has "$t" || fail "WORLD MANAGER: manca «$t» ($WD_WHAT)"; done; }
wd_absent() { local t; for t in "$@"; do ! wd_has "$t" || fail "WORLD MANAGER: compare «$t» ma non dovrebbe ($WD_WHAT)"; done; }
wd_wait_text() { $UI wait "$1" "$2" || $UI scan "$1" 4; }

# Porta l'app alla pagina WORLD MANAGER, in cima.
wd_open() {
  local i
  for i in 1 2 3 4 5 6; do
    snap
    if seen "in un'unica app"; then
      $UI tap "WORLD MANAGER" > /dev/null; sleep 1.5; snap
      if seen "in un'unica app"; then return 1; fi
    fi
    if seen "WORLD MANAGER" && seen "Indietro" && ! seen "TORNA ALLA DASHBOARD"; then
      $UI top; snap
      if seen "Mondi Java e Bedrock"; then return 0; fi
    fi
    back
  done
  return 1
}

wd_pick() { # nome-del-file
  local name="$1"
  wd_open || { fail "WORLD MANAGER: non riesco ad aprire la pagina per scegliere $name"; return 1; }
  $UI tap "SCEGLI UN MONDO (.zip, .mcworld)" || { shot "mondi: pulsante di scelta non trovato"; fail "WORLD MANAGER: il pulsante «SCEGLI UN MONDO (.zip, .mcworld)» non si trova ($name)"; return 1; }
  sleep 3
  if picker_choose "$name"; then
    sleep 2
    if $UI has "Open" && ! $UI has "SCEGLI UN MONDO"; then $UI tap "Open" > /dev/null 2>&1; fi
    return 0
  fi
  shot "selettore senza $name"
  fail "nel selettore di sistema non trovo $name"
  back
  return 1
}

wd_preview() { # etichetta
  if ! $UI wait "Mondo scelto" 120; then
    shot "mondi: anteprima non comparsa ($1)"
    fail "WORLD MANAGER: dopo la scelta di $1 non compaiono i dettagli del mondo"
    return 1
  fi
  $UI top; sleep 0.5
  shot "mondi: anteprima $1"
}

# ---- il telefono
wd_incoming() { adb shell run-as $PKG ls files/worlds/incoming 2>/dev/null | tr -d '\r' | grep -v '^$'; }
wd_no_incoming() { [ -z "$(wd_incoming)" ]; }
# L'id del profilo che ha nella sua cartella saves una cartella con quel nome.
wd_profile_with() { # nome-cartella
  local id
  for id in $(mods_ids); do
    if mods_ls "$id" saves | grep -qxF "$1"; then echo "$id"; return 0; fi
  done
  return 1
}
# Le cartelle di lavoro delle importazioni (in tutti i profili).
wd_staging() { local id; for id in $(mods_ids); do mods_ls "$id" saves | grep '^\.import-' | sed "s#^#$id/#"; done; }
wd_sha() { # id percorso-dentro-saves (può avere spazi)
  adb exec-out "run-as $PKG cat 'files/java/profiles/$1/saves/$2'" 2>/dev/null | sha256sum | cut -d' ' -f1
}
wd_count_files() { # id cartella
  adb shell "run-as $PKG find 'files/java/profiles/$1/saves/$2' -type f" 2>/dev/null | tr -d '\r' | grep -c .
}
wd_exists() { # id percorso
  adb shell "run-as $PKG ls 'files/java/profiles/$1/saves/$2'" > /dev/null 2>&1
}

# ======================================================================================================== preparazione

# Nella parte «worlds» l'app è appena installata: si crea, come farebbe l'utente, il profilo Fabric 1.20.1 che riceverà i mondi.
wd_setup() {
  go_dash
  mods_add Mod_Fabric_Esempio.jar || { fail "WORLD MANAGER: preparazione: non riesco ad aggiungere Mod_Fabric_Esempio.jar"; return 1; }
  $UI wait "Nuovo profilo" 45 || { shot "mondi: preparazione senza profilo"; fail "WORLD MANAGER: preparazione: l'app non propone il profilo"; return 1; }
  $UI tap "CREA PROFILO" > /dev/null
  mods_wait "Java richiesto dal gioco" 25 || { shot "mondi: profilo non creato"; fail "WORLD MANAGER: preparazione: il profilo non viene creato"; return 1; }
  mods_clear_pending
  log "mondi: profilo di partenza pronto ($(mods_ids | head -n 1))"
}

# ======================================================================================================== 1. mondo Java

wd_case_java() {
  local pid lvl r1 out
  lvl=$(zip_member_sha Mondo_Java.zip "Mondo Java di Prova/level.dat")
  r1=$(zip_member_sha Mondo_Java.zip "Mondo Java di Prova/region/r.1.0.mca")

  go_dash
  if ! wd_open; then
    shot "mondi: pagina non aperta"; fail "WORLD MANAGER: la pagina non si apre dalla dashboard"; return 1
  fi
  sleep 0.5; shot "mondi: pagina vuota"
  expect "WORLD MANAGER" "Mondi Java e Bedrock" "SCEGLI UN MONDO (.zip, .mcworld)"
  only_actions "Indietro" "Apri il menu" "SCEGLI UN MONDO (.zip, .mcworld)"
  WD_WHAT="pagina vuota"; wd_texts
  wd_expect "Mondi nei profili" "Nessun mondo nei profili." "Cosa fa e cosa no" "Non converte i mondi tra Java e Bedrock"

  wd_pick Mondo_Java.zip || return 1
  wd_preview Mondo_Java.zip || return 1
  WD_WHAT="dettagli del mondo Java"; wd_texts
  wd_expect "Mondo scelto" "Mondo_Java.zip" "Mondo Java di Prova" "Java Edition · Minecraft 1.20.1 · Sopravvivenza" \
    "Mondo principale" "2 regioni" "Nether" "1 regione" "End" "Giocatori salvati" "Data pack" "1 file fuori dal mondo" \
    "session.lock non viene copiato" "Profilo" "Nome della cartella in saves" "IMPORTA NEL PROFILO" "SCARTA"
  wd_absent "più recente del profilo" "aggiornerà il mondo"
  [ "$(wd_incoming | grep -c .)" = 1 ] || fail "WORLD MANAGER: la copia dell'archivio nella memoria dell'app dovrebbe essere una (sono $(wd_incoming | grep -c .))"
  [ -z "$(wd_profile_with "Mondo Java di Prova")" ] || fail "WORLD MANAGER: la sola lettura ha già messo il mondo in un profilo"

  $UI tap "IMPORTA NEL PROFILO" || { fail "WORLD MANAGER: il pulsante «IMPORTA NEL PROFILO» non si trova"; return 1; }
  if ! $UI wait "Esito dell'importazione" 180 && ! $UI scan "Esito dell'importazione" 4; then
    shot "mondi: nessun esito"; fail "WORLD MANAGER: dopo IMPORTA NEL PROFILO non compare l'esito"; return 1
  fi
  $UI top; sleep 0.5; shot "mondi: importato"
  WD_WHAT="esito dell'importazione"; wd_texts
  wd_expect "Importato" "Il mondo è nella cartella saves/Mondo Java di Prova" "8 file" "Mondi nei profili" "saves/Mondo Java di Prova" \
    "ESPORTA (ZIP)" "ELIMINA"
  wd_absent "Non finito" "Nessun mondo nei profili."

  pid=$(wd_profile_with "Mondo Java di Prova") || { fail "WORLD MANAGER: il mondo non è nella cartella saves di nessun profilo"; return 1; }
  [ "$(wd_sha "$pid" "Mondo Java di Prova/level.dat")" = "$lvl" ] || fail "WORLD MANAGER: level.dat nel profilo non è identico a quello dell'archivio"
  [ "$(wd_sha "$pid" "Mondo Java di Prova/region/r.1.0.mca")" = "$r1" ] || fail "WORLD MANAGER: region/r.1.0.mca nel profilo non è identico a quello dell'archivio"
  [ "$(wd_count_files "$pid" "Mondo Java di Prova")" = 8 ] || fail "WORLD MANAGER: nel mondo importato ci sono $(wd_count_files "$pid" "Mondo Java di Prova") file invece di 8"
  wd_exists "$pid" "Mondo Java di Prova/session.lock" && fail "WORLD MANAGER: session.lock è stato copiato (lo ricrea il gioco)"
  wd_exists "$pid" "LEGGIMI.txt" && fail "WORLD MANAGER: il file fuori dal mondo è finito in saves"
  [ -z "$(wd_staging)" ] || fail "WORLD MANAGER: dopo l'importazione resta una cartella di lavoro: $(wd_staging | tr '\n' ' ')"
  mods_until 15 wd_no_incoming || fail "WORLD MANAGER: dopo l'importazione la copia dell'archivio non è stata cancellata"

  # ---- ESPORTA (ZIP): lo stesso mondo, nella sua cartella, nel punto scelto (Download)
  adb shell "rm -f '/sdcard/Download/Mondo Java di Prova.zip'" > /dev/null 2>&1
  if $UI tap "ESPORTA (ZIP)"; then
    sleep 3; shot "mondi: salva lo ZIP"
    if $UI tap "Save" || $UI tap "Salva"; then
      if wd_wait_text "Esportato «Mondo Java di Prova»" 90; then
        WD_WHAT="dopo l'esportazione"; wd_texts
        wd_expect "Esportato «Mondo Java di Prova»: 8 file"
        out=$(adb shell "ls /sdcard/Download/ | grep '^Mondo Java di Prova.*zip\$' | head -n 1" | tr -d '\r')
        if [ -n "$out" ]; then
          adb pull "/sdcard/Download/$out" "$OUT/mondo-esportato.zip" > /dev/null 2>&1
          if python3 -I - "$OUT/mondo-esportato.zip" "$SAMPLES/Mondo_Java.zip" > "$OUT/mondo-esportato.txt" 2>&1 <<'PY'
import hashlib, sys, zipfile
got = zipfile.ZipFile(sys.argv[1])
src = zipfile.ZipFile(sys.argv[2])
want = {n: hashlib.sha256(src.read(n)).hexdigest() for n in src.namelist()
        if n.startswith("Mondo Java di Prova/") and not n.endswith("/") and not n.endswith("session.lock")}
have = {n: hashlib.sha256(got.read(n)).hexdigest() for n in got.namelist() if not n.endswith("/")}
print("attesi %d, nello ZIP %d" % (len(want), len(have)))
sys.exit(0 if want == have else 1)
PY
          then
            log "mondo esportato identico: $(cat "$OUT/mondo-esportato.txt")"
          else
            fail "WORLD MANAGER: lo ZIP esportato non è identico al mondo: $(tr '\n' '|' < "$OUT/mondo-esportato.txt" | cut -c1-300)"
          fi
        else
          fail "WORLD MANAGER: l'app dice di aver esportato, ma in Download non c'è «Mondo Java di Prova*.zip»"
        fi
      else
        shot "mondi: esportazione senza esito"; fail "WORLD MANAGER: dopo «Save» l'app non mostra l'esito dell'esportazione"
      fi
    else
      shot "mondi: selettore di salvataggio"; note "WORLD MANAGER: nel selettore di salvataggio non trovo «Save»: esportazione non provata"
      back; sleep 1
    fi
  else
    fail "WORLD MANAGER: il pulsante «ESPORTA (ZIP)» non si trova"
  fi
  return 0
}

# ======================================================================================================== 2. mondo più recente del profilo

wd_case_recent() {
  wd_pick Mondo_Java_Recente.zip || return 1
  wd_preview Mondo_Java_Recente.zip || return 1
  WD_WHAT="mondo più recente del profilo"; wd_texts
  wd_expect "Mondo Recente" "Minecraft 1.21.1" "più recente del profilo" "rovinarlo" "IMPORTA NEL PROFILO"
  $UI top
  $UI tap-exact "SCARTA" > /dev/null || fail "WORLD MANAGER: il pulsante SCARTA non si trova"
  sleep 1
  WD_WHAT="dopo SCARTA"; wd_texts
  wd_absent "Mondo Recente" "Mondo scelto"
  mods_until 15 wd_no_incoming || fail "WORLD MANAGER: dopo SCARTA la copia dell'archivio non è stata cancellata"
  [ -z "$(wd_profile_with "Mondo Recente")" ] || fail "WORLD MANAGER: dopo SCARTA il mondo è comunque in un profilo"
  return 0
}

# ======================================================================================================== 3. app chiusa a metà e RIPRENDI

wd_case_interrupt() {
  local pid r159 n
  r159=$(zip_member_sha Mondo_Java_Grande.zip "Mondo Grande/region/r.159.0.mca")
  wd_pick Mondo_Java_Grande.zip || return 1
  wd_preview Mondo_Java_Grande.zip || return 1
  WD_WHAT="mondo grande"; wd_texts
  wd_expect "Mondo Grande" "160 regioni" "IMPORTA NEL PROFILO"
  # Sull'emulatore della CI 20 MB si copiano in un attimo (prova 16: l'importazione finì prima che lo script la vedesse): solo nella
  # build di prova, una pausa di 150 ms dopo ogni file la fa durare circa 25 secondi.
  adb shell "run-as $PKG sh -c 'mkdir -p files/dev && printf 150 > files/dev/world_import_pause_ms.txt'" > /dev/null 2>&1
  $UI tap "IMPORTA NEL PROFILO" || { fail "WORLD MANAGER: il pulsante «IMPORTA NEL PROFILO» non si trova (mondo grande)"; return 1; }
  if ! $UI wait "Copio i file del mondo" 120; then
    shot "mondi: avanzamento non comparso"; fail "WORLD MANAGER: durante l'importazione non compare l'avanzamento («Copio i file del mondo»)"; return 1
  fi
  sleep 4; shot "mondi: importazione in corso"
  # Android chiude l'app a metà (come quando la si toglie dalle recenti o manca memoria).
  adb shell am force-stop $PKG; sleep 2
  if [ -n "$(wd_profile_with "Mondo Grande")" ]; then
    note "WORLD MANAGER: l'importazione del mondo grande è finita prima della chiusura dell'app: la ripresa non è stata provata"
    return 0
  fi
  [ -n "$(wd_staging)" ] || { fail "WORLD MANAGER: dopo la chiusura a metà non c'è la cartella di lavoro dell'importazione"; return 1; }
  [ -n "$(wd_incoming)" ] || fail "WORLD MANAGER: dopo la chiusura a metà la copia dell'archivio non c'è più (la ripresa non sarebbe possibile)"

  # La ripresa va alla velocità normale.
  adb shell "run-as $PKG rm -f files/dev/world_import_pause_ms.txt" > /dev/null 2>&1
  adb shell am start -W -n $PKG/.MainActivity > /dev/null 2>&1; sleep 4
  go_dash
  wd_open || { fail "WORLD MANAGER: dopo la riapertura dell'app la pagina non si apre"; return 1; }
  sleep 1; shot "mondi: importazione interrotta"
  WD_WHAT="importazione interrotta"; wd_texts
  wd_expect "Importazioni interrotte" "Mondo Grande" "file già copiati" "RIPRENDI" "ANNULLA"
  $UI tap-exact "RIPRENDI" || $UI tap "RIPRENDI" || { fail "WORLD MANAGER: il pulsante RIPRENDI non si trova"; return 1; }
  if ! $UI wait "Esito dell'importazione" 300 && ! $UI scan "Esito dell'importazione" 4; then
    shot "mondi: ripresa senza esito"; fail "WORLD MANAGER: RIPRENDI non porta a termine l'importazione"; return 1
  fi
  $UI top; sleep 0.5; shot "mondi: ripresa finita"
  WD_WHAT="dopo RIPRENDI"; wd_texts
  wd_expect "Importato" "saves/Mondo Grande" "166 file"
  wd_has "già copiati prima dell'interruzione" ||
    note "WORLD MANAGER: la ripresa non dice di aver riusato file già copiati (la chiusura era forse arrivata prima del primo file)"
  wd_absent "Importazioni interrotte"
  pid=$(wd_profile_with "Mondo Grande") || { fail "WORLD MANAGER: dopo RIPRENDI il mondo grande non è in saves"; return 1; }
  n=$(wd_count_files "$pid" "Mondo Grande")
  [ "$n" = 166 ] || fail "WORLD MANAGER: nel mondo grande ripreso ci sono $n file invece di 166"
  [ "$(wd_sha "$pid" "Mondo Grande/region/r.159.0.mca")" = "$r159" ] || fail "WORLD MANAGER: l'ultima regione del mondo ripreso non è identica a quella dell'archivio"
  [ -z "$(wd_staging)" ] || fail "WORLD MANAGER: dopo la ripresa resta una cartella di lavoro"
  mods_until 15 wd_no_incoming || fail "WORLD MANAGER: dopo la ripresa la copia dell'archivio non è stata cancellata"
  return 0
}

# ======================================================================================================== 4. mondo Bedrock

wd_case_bedrock() {
  wd_pick Mondo_di_Prova.mcworld || return 1
  wd_preview Mondo_di_Prova.mcworld || return 1
  WD_WHAT="mondo Bedrock"; wd_texts
  wd_expect "Bedrock Edition" "Mondo di Prova" "si importa in Minecraft Bedrock dall'INSTALLER BEDROCK" "IMPORTA IN MINECRAFT (INSTALLER BEDROCK)"
  wd_absent "IMPORTA NEL PROFILO" "Nome della cartella in saves"
  $UI tap "IMPORTA IN MINECRAFT (INSTALLER BEDROCK)" || { fail "WORLD MANAGER: il pulsante «IMPORTA IN MINECRAFT (INSTALLER BEDROCK)» non si trova"; return 1; }
  if $UI wait "Rapporto di analisi" 60; then
    sleep 1; shot "mondi: rapporto dell'installer"
    expect "Rapporto di analisi" "Mondo di Prova"
  else
    shot "mondi: installer non aperto"; fail "WORLD MANAGER: il mondo Bedrock non arriva all'INSTALLER BEDROCK"
  fi
  mods_until 15 wd_no_incoming || fail "WORLD MANAGER: dopo il passaggio all'INSTALLER BEDROCK la copia dell'archivio non è stata cancellata"
  go_dash
  return 0
}

# ======================================================================================================== 5. ELIMINA

wd_case_delete() {
  local which pid
  wd_open || { fail "WORLD MANAGER: non riesco ad aprire la pagina"; return 1; }
  $UI top; $UI tap-exact "CHIUDI" > /dev/null 2>&1; sleep 1
  $UI scan "ESPORTA (ZIP)" 6 > /dev/null
  if ! $UI tap-exact "ELIMINA"; then
    shot "mondi: ELIMINA non trovato"; fail "WORLD MANAGER: nell'elenco dei mondi non trovo ELIMINA"; return 1
  fi
  if ! $UI wait "Eliminare il mondo?" 15; then
    shot "mondi: conferma non comparsa"; fail "WORLD MANAGER: ELIMINA non chiede conferma"; return 1
  fi
  sleep 0.5; shot "mondi: conferma eliminazione"
  if $UI has-in "$LAST_UI" "«Mondo Grande»"; then which="Mondo Grande"; else which="Mondo Java di Prova"; fi
  expect "Eliminare il mondo?" "non si può annullare" "ELIMINA IL MONDO" "ANNULLA"
  pid=$(wd_profile_with "$which")
  $UI tap "ELIMINA IL MONDO" || { fail "WORLD MANAGER: il pulsante «ELIMINA IL MONDO» non si trova"; return 1; }
  if wd_wait_text "eliminato dal profilo" 60; then
    shot "mondi: eliminato"
  else
    shot "mondi: nessun messaggio"; fail "WORLD MANAGER: dopo ELIMINA IL MONDO non compare il messaggio"
  fi
  [ -z "$(wd_profile_with "$which")" ] || fail "WORLD MANAGER: «$which» è ancora nella cartella saves dopo ELIMINA"
  [ -n "$pid" ] && [ "$(mods_ids | grep -cxF "$pid")" = 1 ] || fail "WORLD MANAGER: eliminando un mondo è sparito anche il profilo"
  return 0
}

# ======================================================================================================== la fase

worlds_tests() {
  local r
  export MMI_SWIPE_SAFE=1
  worlds_tests_inner; r=$?
  unset MMI_SWIPE_SAFE
  return $r
}

worlds_tests_inner() {
  phase "WORLD MANAGER"
  if [ "$PART" = worlds ]; then
    wd_setup || return
  fi
  if [ -z "$(mods_ids)" ]; then
    note "WORLD MANAGER non provato: sul telefono non c'è nessun profilo Java"
    return
  fi
  wd_case_java || go_dash
  wd_case_recent || go_dash
  wd_case_interrupt || go_dash
  wd_case_bedrock || go_dash
  wd_case_delete || go_dash
  go_dash
}
