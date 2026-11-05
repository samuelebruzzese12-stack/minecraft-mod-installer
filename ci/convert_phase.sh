#!/bin/bash
# Fase «JAVA TO BEDROCK» della prova su emulatore. Non si esegue da sola: la include ci/emulator_smoke.sh e ne usa le funzioni e
# le variabili ($UI, $PKG, $FAKE_PKG, $FAKE_APK, $SAMPLES, $OUT, fail, note, shot, expect, snap, seen, back, go_dash,
# picker_choose, mods_add, mods_wait, mods_clear_pending, mods_ids…).
#
# La mod di prova è Mod_Da_Convertire.jar di ci/make_samples.py; le attese sono quelle che EmulatorConvertSamplesTest
# (mod-converter) controlla prima con il codice vero sugli STESSI file. Si prova, scegliendo i file come farebbe l'utente:
#   1. pagina vuota: testi onesti su cosa si converte e cosa no;
#   2. una mod di contenuto: rapporto (oggetti, blocchi, creatura, ricette 3 su 4, minerale, nomi italiani), CREA L'ADD-ON,
#      file .mcaddon sul telefono (controllato con gli schemi ufficiali di Mojang da ci/validate_addon.py), copie cancellate;
#   3. INSTALLA IN MINECRAFT: l'INSTALLER BEDROCK lo controlla e lo consegna al finto Minecraft, che lo riceve intero;
#   4. una mod di solo codice: «Niente da convertire», nessun pulsante per creare l'add-on;
#   5. CONVERTI LE MOD DI UN PROFILO: senza profili lo dice; con un profilo legge le sue mod;
#   6. SALVA ed ELIMINA dall'elenco degli add-on creati.

CV_TXT=/tmp/mmi_convert_texts.txt
CV_WHAT=""
CV_NAME="Rubini e Pomodori"

cv_texts() { : > "$CV_TXT"; $UI top > /dev/null 2>&1; $UI collect "$CV_TXT" 12 > /dev/null 2>&1; }
cv_has() { grep -qiF -- "$1" "$CV_TXT"; }
cv_expect() { local t; for t in "$@"; do cv_has "$t" || fail "JAVA TO BEDROCK: manca «$t» ($CV_WHAT)"; done; }
cv_absent() { local t; for t in "$@"; do ! cv_has "$t" || fail "JAVA TO BEDROCK: compare «$t» ma non dovrebbe ($CV_WHAT)"; done; }
cv_wait() { $UI wait "$1" "$2" || $UI scan "$1" 6; }
cv_addons() { adb shell run-as $PKG ls files/convert/addons 2>/dev/null | tr -d '\r' | grep -v '^$'; }
cv_incoming() { adb shell run-as $PKG ls files/convert/incoming 2>/dev/null | tr -d '\r' | grep -v '^$'; }

# Porta l'app alla pagina JAVA TO BEDROCK, in cima.
cv_open() {
  local i
  for i in 1 2 3 4 5 6; do
    snap
    if seen "in un'unica app"; then
      $UI tap "JAVA TO BEDROCK" > /dev/null; sleep 1.5; snap
      if seen "in un'unica app"; then $UI scan "JAVA TO BEDROCK" 4 > /dev/null && $UI tap "JAVA TO BEDROCK" > /dev/null; sleep 1.5; snap; fi
    fi
    if seen "JAVA TO BEDROCK" && seen "Indietro" && ! seen "TORNA ALLA DASHBOARD"; then
      $UI top; snap
      if seen "Mod Java in Minecraft Bedrock"; then return 0; fi
    fi
    back
  done
  return 1
}

cv_pick() { # nome-del-file
  local name="$1"
  cv_open || { fail "JAVA TO BEDROCK: non riesco ad aprire la pagina per scegliere $name"; return 1; }
  $UI tap "SCEGLI MOD JAVA (.jar)" || { shot "conversione: pulsante di scelta non trovato"; fail "JAVA TO BEDROCK: il pulsante «SCEGLI MOD JAVA (.jar)» non si trova"; return 1; }
  sleep 3
  if picker_choose "$name"; then
    sleep 2
    if $UI has "Open" && ! $UI has "SCEGLI MOD JAVA"; then $UI tap "Open" > /dev/null 2>&1; fi
    if cv_wait "Mod lette" 120; then return 0; fi
    shot "conversione: nessun rapporto per $name"; fail "JAVA TO BEDROCK: scelto $name non compare il rapporto («Mod lette»)"
    return 1
  fi
  shot "selettore senza $name"; fail "nel selettore di sistema non trovo $name"; back
  return 1
}

# Quante consegne di .mcaddon ha visto il finto Minecraft finora.
cv_received_count() { grep -c "FAKE_MC.*RECEIVED name=.*\.mcaddon " "$OUT/logcat.txt" 2>/dev/null || true; }

convert_tests() {
  phase "JAVA TO BEDROCK"
  local before after n file got
  go_dash

  # ================================================================== 1) pagina vuota
  if ! cv_open; then
    shot "conversione: pagina non aperta"; fail "JAVA TO BEDROCK: la pagina non si apre dalla dashboard"; return
  fi
  sleep 0.5; shot "conversione: pagina vuota"
  CV_WHAT="pagina vuota"; cv_texts
  cv_expect "Mod Java in Minecraft Bedrock" "SCEGLI MOD JAVA (.jar)" "CONVERTI LE MOD DI UN PROFILO" "Bedrock non esegue codice Java" \
    "Add-on creati" "Nessun add-on creato finora." "Cosa fa e cosa no" "provalo in un mondo di prova"
  cv_absent "In arrivo" "Non è ancora stato costruito"

  # ================================================================== 5a) profilo: senza profili lo dice
  $UI top; $UI tap "CONVERTI LE MOD DI UN PROFILO" > /dev/null
  if cv_wait "nessun profilo nel MOD MANAGER" 15; then shot "conversione: nessun profilo"; $UI tap-exact "OK" > /dev/null 2>&1
  else shot "conversione: profilo senza messaggio"; fail "JAVA TO BEDROCK: senza profili «CONVERTI LE MOD DI UN PROFILO» non lo dice"; fi

  # ================================================================== 2) mod di contenuto
  if cv_pick Mod_Da_Convertire.jar; then
    sleep 1; shot "conversione: rapporto"
    CV_WHAT="rapporto della mod di contenuto"; cv_texts
    cv_expect "Da: file scelti" "1 mod letta, 1 con contenuto da portare in Bedrock" "Rubini e Pomodori 1.0.0" "Fabric" \
      "Convertibile in parte" "Oggetti: 4" "Spada di rubino (spada)" "Elmo di rubino (elmo)" "Pomodoro (cibo)" "Rubino (oggetto)" \
      "Blocchi: 2" "Blocco di rubino" "Minerale di rubino" "Creature: 1" "Coccinella (modello e animazioni, cammina)" \
      "Ricette: 3 su 4" "ricetta di una macchina o di un sistema della mod" "Cosa lasciano i blocchi: 1" \
      "Minerali generati nel mondo: 1" "Armature con il disegno sul corpo: 1" "stimat" "Nome dell'add-on" "CREA L'ADD-ON BEDROCK" \
      "CONDIVIDI IL RAPPORTO" "SCARTA"
    [ "$(cv_incoming | grep -c .)" = 1 ] || fail "JAVA TO BEDROCK: la copia della mod nella memoria dell'app dovrebbe essere una (sono $(cv_incoming | grep -c .))"
    [ -z "$(cv_addons)" ] || fail "JAVA TO BEDROCK: la sola lettura ha già scritto un add-on"
    $UI scan "CREA L'ADD-ON BEDROCK" 8 > /dev/null
    $UI tap "CREA L'ADD-ON BEDROCK" || { fail "JAVA TO BEDROCK: il pulsante «CREA L'ADD-ON BEDROCK» non si trova"; return; }
    if cv_wait "Add-on Bedrock pronto" 120; then
      sleep 0.5; shot "conversione: add-on creato"
      CV_WHAT="add-on creato"; cv_texts
      cv_expect "Add-on creato" "$CV_NAME" "Creato" "4 oggetti, 2 blocchi, 1 creature, 3 ricette, 1 minerali" "INSTALLA IN MINECRAFT" \
        "SALVA UNA COPIA" "RAPPORTO" "CHIUDI" "Il tuo Minecraft è la 1.21.50" "serve la 1.21.80 o successiva"
      cv_absent "Mod lette"
    else
      shot "conversione: nessun esito"; fail "JAVA TO BEDROCK: dopo CREA L'ADD-ON BEDROCK non compare l'esito"; return
    fi
    file="$CV_NAME.mcaddon"
    if cv_addons | grep -qxF "$file"; then
      adb exec-out run-as $PKG cat "files/convert/addons/$file" > "$OUT/convertito.mcaddon" 2>/dev/null
      if [ -s "$OUT/convertito.mcaddon" ]; then
        if [ -d "${BEDROCK_SAMPLES:-}" ]; then
          python3 -I ci/validate_addon.py "$BEDROCK_SAMPLES" "$OUT/convertito.mcaddon" > "$OUT/convertito-controllo.txt" 2>&1 \
            || fail "JAVA TO BEDROCK: l'add-on creato non passa il controllo con gli schemi di Minecraft: $(head -c 400 "$OUT/convertito-controllo.txt" | tr '\n' '|')"
        else
          note "JAVA TO BEDROCK: schemi di Mojang non disponibili, l'add-on non è stato controllato con ci/validate_addon.py"
        fi
        n=$(python3 -I -c "import sys,zipfile; print(len(zipfile.ZipFile(sys.argv[1]).namelist()))" "$OUT/convertito.mcaddon" 2>/dev/null)
        [ "$n" = 36 ] || fail "JAVA TO BEDROCK: l'add-on dovrebbe avere 36 file (come nella prova sul computer), ne ha $n"
      else
        fail "JAVA TO BEDROCK: non riesco a leggere l'add-on creato dal telefono"
      fi
    else
      fail "JAVA TO BEDROCK: sul telefono non c'è files/convert/addons/$file (ci sono: $(cv_addons | tr '\n' ' '))"
    fi
    mods_until 15 eval '[ -z "$(cv_incoming)" ]' || fail "JAVA TO BEDROCK: dopo la conversione la copia della mod non è stata cancellata"

    # ================================================================== 3) INSTALLA IN MINECRAFT → INSTALLER BEDROCK → finto Minecraft
    before=$(cv_received_count); before=${before:-0}
    $UI top; $UI scan "INSTALLA IN MINECRAFT" 6 > /dev/null
    $UI tap-exact "INSTALLA IN MINECRAFT" > /dev/null 2>&1 || $UI tap "INSTALLA IN MINECRAFT" > /dev/null
    if $UI wait "Rapporto di analisi" 90; then
      sleep 1; shot "conversione: rapporto dell'installer"
      expect "Rapporto di analisi" "$CV_NAME"
      # Il finto Minecraft è la 1.21.50: l'add-on chiede la 1.21.80, quindi l'installer lo segnala e chiede di forzare (giusto).
      # Anche la schermata di JAVA TO BEDROCK l'aveva detto (controllato sopra).
      if $UI scan "Forza l'installazione, a mio rischio" 6; then
        # Il testo non si tocca: è l'interruttore accanto (l'unico della pagina).
        $UI tap-switch 0 > /dev/null; sleep 1.5
        $UI top; snap
        seen "serve forzare l'installazione" && fail "JAVA TO BEDROCK: l'interruttore «Forza l'installazione» non seleziona l'add-on"
      else
        note "JAVA TO BEDROCK: l'installer non ha chiesto di forzare l'add-on (la versione del finto Minecraft è cambiata?)"
      fi
      $UI top
      if $UI wait "INSTALLA IN MINECRAFT" 30 && $UI tap "INSTALLA IN MINECRAFT"; then
        for n in $(seq 1 60); do
          after=$(cv_received_count); after=${after:-0}
          [ "$after" -gt "$before" ] && break
          sleep 1
        done
        if [ "$after" -gt "$before" ]; then
          got=$(grep "FAKE_MC.*RECEIVED name=.*\.mcaddon " "$OUT/logcat.txt" | tail -n 1 | sed 's/.*RECEIVED name=\([^ ]*\) .*/\1/')
          log "il finto Minecraft ha ricevuto $got"
          if pull_received "$got"; then
            python3 -I - "$OUT/received/$got" "$OUT/convertito.mcaddon" <<'PY' || fail "JAVA TO BEDROCK: il file arrivato a Minecraft non contiene l'add-on creato (vedi $OUT/received)"
import sys, zipfile
got = zipfile.ZipFile(sys.argv[1]); made = zipfile.ZipFile(sys.argv[2])
def strip(n):
    return n.split("/", 1)[1] if "/" in n else n
a = {strip(n): got.read(n) for n in got.namelist() if not n.endswith("/")}
b = {strip(n): made.read(n) for n in made.namelist() if not n.endswith("/")}
missing = [n for n in b if n not in a]
diff = [n for n in b if n in a and a[n] != b[n]]
if missing or diff:
    print("mancano:", missing[:10], "diversi:", diff[:10])
    sys.exit(1)
PY
          else
            fail "JAVA TO BEDROCK: non riesco a leggere il file ricevuto dal finto Minecraft ($got)"
          fi
          app_front
          if $UI wait "Minecraft ha importato il contenuto?" 20; then $UI tap "Sì, Minecraft l'ha importato" > /dev/null
          elif $UI has "Sì, importato"; then $UI tap "Sì, importato" > /dev/null; fi
        else
          shot "conversione: niente a Minecraft"; fail "JAVA TO BEDROCK: l'add-on non arriva al finto Minecraft"
        fi
      else
        fail "JAVA TO BEDROCK: nel rapporto dell'installer manca «INSTALLA IN MINECRAFT»"
      fi
    else
      shot "conversione: installer non aperto"; fail "JAVA TO BEDROCK: INSTALLA IN MINECRAFT non apre l'INSTALLER BEDROCK"
    fi
    go_dash
  fi

  # ================================================================== 4) mod di solo codice
  if cv_pick Mod_Fabric_Esempio.jar; then
    sleep 1; shot "conversione: mod di solo codice"
    CV_WHAT="mod di solo codice"; cv_texts
    cv_expect "Esempio Fabric" "Solo codice" "Niente da convertire" "non c'è niente che Bedrock possa usare" "SCARTA"
    cv_absent "CREA L'ADD-ON BEDROCK" "Nome dell'add-on"
    $UI scan "SCARTA" 6 > /dev/null; $UI tap-exact "SCARTA" > /dev/null 2>&1 || $UI tap "SCARTA" > /dev/null
    sleep 1.5
    mods_until 15 eval '[ -z "$(cv_incoming)" ]' || fail "JAVA TO BEDROCK: SCARTA non cancella la copia della mod"
  fi

  # ================================================================== 5b) profilo vuoto, poi con una mod installata
  go_dash
  if mods_add Mod_Fabric_Esempio.jar && $UI wait "Nuovo profilo" 45; then
    $UI tap "CREA PROFILO" > /dev/null
    if mods_wait "Java richiesto dal gioco" 25; then
      mods_clear_pending
      # Profilo senza mod: lo dice
      go_dash
      if cv_open && $UI tap "CONVERTI LE MOD DI UN PROFILO" && $UI wait "Quale profilo?" 15; then
        $UI tap "Fabric 1.20.1 ·" > /dev/null || $UI tap "Fabric" > /dev/null
        if cv_wait "non ci sono mod attive da convertire" 30; then shot "conversione: profilo vuoto"; $UI tap-exact "OK" > /dev/null 2>&1
        else shot "conversione: profilo vuoto senza messaggio"; fail "JAVA TO BEDROCK: un profilo senza mod non lo dice"; fi
      fi
      # Ora la mod si installa davvero nel profilo
      mods_add Mod_Fabric_Esempio.jar
      if mods_wait "Pronto" 30 && $UI tap-exact "INSTALLA"; then
        mods_until 40 eval '[ -n "$(mods_ids)" ] && adb shell run-as $PKG ls "files/java/profiles/$(mods_ids | head -n 1)/mods" 2>/dev/null | grep -q .' \
          || fail "JAVA TO BEDROCK: preparazione: la mod non si installa nel profilo"
      else
        fail "JAVA TO BEDROCK: preparazione: la mod non è pronta per il profilo"
      fi
      go_dash
      if cv_open && $UI tap "CONVERTI LE MOD DI UN PROFILO" && $UI wait "Quale profilo?" 15; then
        shot "conversione: scelta del profilo"
        $UI tap "Fabric 1.20.1 ·" > /dev/null || $UI tap "Fabric" > /dev/null
        if cv_wait "Mod lette" 60; then
          CV_WHAT="mod del profilo"; cv_texts
          cv_expect "Da: profilo «Fabric 1.20.1»" "1 mod letta" "Esempio Fabric" "Niente da convertire"
          $UI scan "SCARTA" 6 > /dev/null; $UI tap-exact "SCARTA" > /dev/null 2>&1
          [ -n "$(mods_ids)" ] && adb shell run-as $PKG ls "files/java/profiles/$(mods_ids | head -n 1)/mods" 2>/dev/null | grep -q . \
            || fail "JAVA TO BEDROCK: leggere le mod del profilo le ha tolte dal profilo"
        else
          shot "conversione: profilo non letto"; fail "JAVA TO BEDROCK: scelto il profilo non compare il rapporto"
        fi
      else
        shot "conversione: niente scelta del profilo"; fail "JAVA TO BEDROCK: «CONVERTI LE MOD DI UN PROFILO» non mostra i profili"
      fi
    else
      fail "JAVA TO BEDROCK: preparazione: il profilo non viene creato"
    fi
  else
    fail "JAVA TO BEDROCK: preparazione: non riesco a creare il profilo"
  fi

  # ================================================================== 6) elenco: SALVA ed ELIMINA
  go_dash
  if cv_open; then
    $UI tap-exact "CHIUDI" > /dev/null 2>&1; sleep 1
    CV_WHAT="elenco degli add-on"; cv_texts
    cv_expect "Add-on creati" "$CV_NAME" "INSTALLA" "SALVA" "ELIMINA"
    $UI scan "ELIMINA" 8 > /dev/null
    if $UI tap-exact "SALVA"; then
      sleep 3; shot "conversione: salva una copia"
      if $UI tap "Save" || $UI tap "Salva"; then
        if cv_wait "Copia salvata" 40; then
          got=$(adb shell "ls /sdcard/Download/ | grep '^Rubini.*mcaddon$' | head -n 1" | tr -d '\r')
          if [ -n "$got" ]; then
            adb pull "/sdcard/Download/$got" "$OUT/salvato.mcaddon" > /dev/null 2>&1
            cmp -s "$OUT/salvato.mcaddon" "$OUT/convertito.mcaddon" || fail "JAVA TO BEDROCK: la copia salvata in Download è diversa dall'add-on creato"
          else
            fail "JAVA TO BEDROCK: l'app dice di aver salvato la copia, ma in Download non c'è"
          fi
        else
          shot "conversione: salvataggio senza esito"; fail "JAVA TO BEDROCK: dopo «Save» l'app non dice che la copia è salvata"
        fi
      else
        shot "conversione: selettore di salvataggio"; back
        note "JAVA TO BEDROCK: nel selettore di salvataggio non trovo «Save»: SALVA non provato (dipende dalla versione del selettore di sistema)"
      fi
      cv_open > /dev/null; $UI scan "ELIMINA" 8 > /dev/null
    else
      fail "JAVA TO BEDROCK: nell'elenco degli add-on non trovo SALVA"
    fi
    if $UI tap-exact "ELIMINA" && $UI wait "Eliminare l'add-on?" 15; then
      shot "conversione: conferma eliminazione"
      expect "Eliminare l'add-on?" "$CV_NAME" "in Minecraft resta"
      $UI tap-last "ELIMINA" > /dev/null
      if mods_until 20 eval '[ -z "$(cv_addons)" ]'; then
        cv_wait "eliminato dall'app" 10 || fail "JAVA TO BEDROCK: dopo ELIMINA non compare il messaggio"
        shot "conversione: add-on eliminato"
      else
        fail "JAVA TO BEDROCK: ELIMINA non toglie l'add-on dal telefono"
      fi
    else
      shot "conversione: ELIMINA senza conferma"; fail "JAVA TO BEDROCK: ELIMINA non chiede conferma"
    fi
  else
    fail "JAVA TO BEDROCK: la pagina non si riapre"
  fi
  go_dash
}
