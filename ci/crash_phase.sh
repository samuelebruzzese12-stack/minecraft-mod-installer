#!/bin/bash
# Fase «Analisi di un crash» (DIAGNOSTICA) della prova su emulatore. Non si esegue da sola: la include ci/emulator_smoke.sh e
# si esegue nella parte «mods», DOPO mods_tests, perché usa il profilo Fabric che quella fase lascia (con Esempio Fabric,
# Con Dipendenza e Libreria Esempio). I testi sono quelli di ci/make_samples.py (Crash_Fabric.txt, Log_Memoria.log.gz); le
# attese sono quelle che EmulatorCrashSamplesTest (mod-manager) controlla prima con il codice vero sugli STESSI file.
# Si prova, scegliendo i file dal selettore di sistema come farebbe l'utente:
#   1. un crash report Fabric: versione, caricatore, le due cause con le mod giuste, le mod sospettate, le mod ritrovate nel
#      profilo, APRI NEL MOD MANAGER che porta al profilo, CHIUDI L'ANALISI;
#   2. un log compresso (.log.gz) con la memoria finita: letto e riconosciuto, nessuna mod da cercare nei profili;
#   3. i file scelti non vengono copiati nell'app (l'analisi li legge soltanto).

CR_TXT=/tmp/mmi_crash_texts.txt
CR_WHAT=""

cr_texts() { : > "$CR_TXT"; $UI top > /dev/null 2>&1; $UI collect "$CR_TXT" 10 > /dev/null 2>&1; }
cr_has() { grep -qiF -- "$1" "$CR_TXT"; }
cr_expect() { local t; for t in "$@"; do cr_has "$t" || fail "ANALISI DEI CRASH: manca «$t» ($CR_WHAT)"; done; }
cr_absent() { local t; for t in "$@"; do ! cr_has "$t" || fail "ANALISI DEI CRASH: compare «$t» ma non dovrebbe ($CR_WHAT)"; done; }

# Apre DIAGNOSTICA e sceglie il file; torna 0 quando compare l'esito.
cr_pick() { # nome-del-file
  local name="$1"
  go_dash && open_from_menu "DIAGNOSTICA" && $UI wait "Controllo dei file" 12 || { fail "ANALISI DEI CRASH: DIAGNOSTICA non si apre ($name)"; return 1; }
  $UI scan "SCEGLI UN CRASH REPORT O UN LOG" 8 > /dev/null
  $UI tap "SCEGLI UN CRASH REPORT O UN LOG" || { shot "crash: pulsante non trovato"; fail "ANALISI DEI CRASH: il pulsante «SCEGLI UN CRASH REPORT O UN LOG» non si trova"; return 1; }
  sleep 3
  if ! picker_choose "$name"; then
    shot "selettore senza $name"; fail "nel selettore di sistema non trovo $name"; back; return 1
  fi
  sleep 2
  if $UI has "Open" && ! $UI has "DIAGNOSTICA"; then $UI tap "Open" > /dev/null 2>&1; fi
  if $UI wait "Esito dell'analisi" 20 || $UI scan "Esito dell'analisi" 6; then return 0; fi
  shot "crash: nessun esito per $name"; fail "ANALISI DEI CRASH: scelto $name non compare «Esito dell'analisi»"
  return 1
}

crash_tests() {
  phase "DIAGNOSTICA: analisi di un crash"

  # ================================================================== 1) crash report Fabric con mod del profilo
  CR_WHAT="crash report Fabric"
  if cr_pick "Crash_Fabric.txt"; then
    sleep 0.5; shot "crash: esito del crash report"
    cr_texts
    cr_expect "Crash_Fabric.txt" "Crash report di Minecraft" "1.20.1" "Fabric" "17.0.8" \
      "Versione sbagliata" "«Con Dipendenza» (condipendenza) vuole «Libreria Esempio» version 3.0.0 or later, ma c'è la versione 2.1.0." \
      "Manca una mod richiesta" "«Esempio Fabric» (esempiofabric) ha bisogno di «sodium» (any version), che non c'è." \
      "Mod sospettate dal gioco" "Esempio Fabric (esempiofabric)" \
      "Nei tuoi profili" "«esempiofabric»: Esempio Fabric" "«condipendenza»: Con Dipendenza" "«libesempio»: Libreria Esempio" \
      "non certezze" "CONDIVIDI L'ANALISI" "CHIUDI L'ANALISI"
    cr_absent "Causa non riconosciuta" "Memoria insufficiente"
    # APRI NEL MOD MANAGER porta al profilo con quelle mod
    $UI scan "APRI NEL MOD MANAGER" 8 > /dev/null
    if $UI tap "APRI NEL MOD MANAGER"; then
      if mods_wait "Java richiesto dal gioco" 20 > /dev/null; then
        sleep 0.5; shot "crash: aperto il mod manager"
        $UI has "Fabric 1.20.1" || $UI scan "Fabric 1.20.1" 4 || fail "ANALISI DEI CRASH: APRI NEL MOD MANAGER non mostra il profilo Fabric"
      else
        shot "crash: mod manager non aperto"; fail "ANALISI DEI CRASH: APRI NEL MOD MANAGER non apre il MOD MANAGER"
      fi
    else
      fail "ANALISI DEI CRASH: pulsante «APRI NEL MOD MANAGER» non trovato"
    fi
    # L'esito resta finché non si chiude; CHIUDI L'ANALISI lo toglie
    if go_dash && open_from_menu "DIAGNOSTICA" && $UI wait "Controllo dei file" 12; then
      if $UI scan "CHIUDI L'ANALISI" 10 && $UI tap "CHIUDI L'ANALISI"; then
        sleep 1.5; cr_texts
        cr_absent "Esito dell'analisi" "Nei tuoi profili"
        cr_expect "SCEGLI UN CRASH REPORT O UN LOG"
      else
        shot "crash: esito sparito"; fail "ANALISI DEI CRASH: tornando in DIAGNOSTICA l'esito non c'è più o manca CHIUDI L'ANALISI"
      fi
    fi
  fi

  # ================================================================== 2) log compresso con la memoria finita
  CR_WHAT="log .gz"
  if cr_pick "Log_Memoria.log.gz"; then
    sleep 0.5; shot "crash: esito del log compresso"
    cr_texts
    cr_expect "Log_Memoria.log.gz" "Log o altro testo" "Fabric" "Memoria insufficiente" "OutOfMemoryError"
    cr_absent "Nei tuoi profili" "Manca una mod richiesta" "Crash report di Minecraft"
    $UI scan "CHIUDI L'ANALISI" 10 > /dev/null && $UI tap "CHIUDI L'ANALISI" > /dev/null
  fi

  # ================================================================== 3) niente copiato nell'app
  adb shell run-as $PKG find files -type f 2>/dev/null | tr -d '\r' | grep -iE "Crash_Fabric|Log_Memoria" \
    && fail "ANALISI DEI CRASH: il file scelto è stato copiato nell'app (doveva solo leggerlo)"
  go_dash
}
