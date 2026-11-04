#!/bin/bash
# Prova su emulatore Android: installa l'APK, avvia l'app, apre file di prova dal selettore di sistema (come farebbe
# l'utente) e tocca i pulsanti. Con il FINTO Minecraft (ci/fakeminecraft) controlla anche che il file consegnato dall'app
# contenga tutto, byte per byte. Salva schermate, dump dell'interfaccia e log in $OUT. Non si ferma al primo problema.
#
# Il finto Minecraft non importa niente: verifica solo che l'app consegni il file giusto con lo stesso Intent.
# Se Minecraft vero importi o no il file non si può provare qui.
PKG=com.mmi.modinstaller
FAKE_PKG=com.mojang.minecraftpe
APK=${APK:-apk/MinecraftModInstaller-debug.apk}
FAKE_APK=$(ls fake/*.apk 2>/dev/null | head -n 1)
OUT=${OUT:-ci-out}
SAMPLES=${SAMPLES:-samples}
UI="python3 ci/ui.py"
mkdir -p "$OUT/shots" "$OUT/ui" "$OUT/received"
PROBLEMS="$OUT/problemi.txt"; : > "$PROBLEMS"
STEP=0; LAST_UI=""

log()  { echo "[$(date +%H:%M:%S)] $*"; }
fail() { log "PROBLEMA: $*"; echo "$*" >> "$PROBLEMS"; }
shot() {
  STEP=$((STEP+1))
  local n; n=$(printf "%02d-%s" "$STEP" "$(echo "$1" | tr ' /' '__')")
  adb exec-out screencap -p > "$OUT/shots/$n.png" 2>/dev/null
  if $UI dump "$OUT/ui/$n.xml" >/dev/null 2>&1; then
    LAST_UI="$OUT/ui/$n.xml"
    $UI texts "$LAST_UI" > "$OUT/ui/$n.txt" 2>/dev/null
    log "schermata $n: $(tr '\n' '|' < "$OUT/ui/$n.txt" | cut -c1-500)"
  else
    LAST_UI=""
    fail "non riesco a leggere l'interfaccia ($n)"
  fi
}
expect() {
  [ -z "$LAST_UI" ] && return
  for t in "$@"; do $UI has-in "$LAST_UI" "$t" || fail "manca «$t» nella schermata $(basename "$LAST_UI" .xml)"; done
}
back() { adb shell input keyevent KEYCODE_BACK; sleep 1.2; }
go_home() {
  for _ in 1 2 3 4 5; do
    $UI has "SELEZIONA FILE" && return 0
    back
  done
  return 1
}
app_front() { adb shell am start -n $PKG/.MainActivity > /dev/null 2>&1; sleep 2; }

# Apre un file di prova dal selettore di sistema, come farebbe l'utente.
open_sample() {
  local name="$1"
  go_home || { fail "non torno alla Home prima di aprire $name"; return 1; }
  $UI tap "SELEZIONA FILE" || { fail "SELEZIONA FILE non toccabile ($name)"; return 1; }
  sleep 3
  if ! $UI wait "$name" 5; then
    $UI tap "Show roots" || $UI tap "Mostra radici" || true
    sleep 1.5
    ($UI tap "Downloads" || $UI tap "Download") || true
    sleep 2.5
  fi
  if $UI tap "$name"; then
    sleep 1.5
    $UI tap "Open" || $UI tap "Apri" || true
    return 0
  fi
  shot "selettore senza $name"
  fail "nel selettore di sistema non trovo $name"
  back
  return 1
}

# Aspetta che il finto Minecraft scriva nel log di aver ricevuto un file; stampa la riga.
wait_received() {
  local name="$1" i
  for i in $(seq 1 60); do
    if grep -q "FAKE_MC.*RECEIVED name=$name " "$OUT/logcat.txt" 2>/dev/null; then
      grep "FAKE_MC.*RECEIVED name=$name " "$OUT/logcat.txt" | tail -n 1
      return 0
    fi
    sleep 1
  done
  return 1
}

pull_received() {
  local name="$1"
  adb exec-out run-as $FAKE_PKG cat "files/received/$name" > "$OUT/received/$name" 2>/dev/null
  [ -s "$OUT/received/$name" ]
}

log "Dispositivo: $(adb shell getprop ro.product.model | tr -d '\r'), Android API $(adb shell getprop ro.build.version.sdk | tr -d '\r'), schermo $(adb shell wm size | tr -d '\r')"
adb logcat -c
adb logcat -v threadtime > "$OUT/logcat.txt" 2>&1 &
LOGCAT_PID=$!

log "Installo l'APK: $APK"
adb install -r -t "$APK" 2>&1 | tee "$OUT/install.txt"
if ! grep -q Success "$OUT/install.txt"; then
  fail "installazione dell'APK non riuscita"
  kill $LOGCAT_PID 2>/dev/null; exit 1
fi

# File di prova: nella cartella Download, dove li trova il selettore di sistema.
adb push "$SAMPLES"/. /sdcard/Download/ > "$OUT/push.txt" 2>&1
adb shell ls -l /sdcard/Download > "$OUT/download-dir.txt" 2>&1

# ---------- Home ----------
adb shell am start -W -n $PKG/.MainActivity 2>&1 | tee "$OUT/start.txt"
sleep 3
shot "home"
expect "SELEZIONA FILE" "IMPORTA MOD" "IMPORTA MAPPA" "IMPORTA TEXTURE" "IMPORTA ADD-ON" "FILE RECENTI" "MOD INSTALLATI" "CRONOLOGIA" "BACKUP" "Minecraft non trovato"

# ---------- Elenchi ----------
for label in "FILE RECENTI" "MOD INSTALLATI" "CRONOLOGIA" "BACKUP"; do
  if $UI tap "$label"; then
    sleep 1.5; shot "elenco $label"
    back
    $UI wait "SELEZIONA FILE" 8 || fail "dopo «$label» il tasto Indietro non riporta alla Home"
  else
    fail "pulsante «$label» non trovato nella Home"
  fi
done

# ---------- Informazioni ----------
if $UI tap "Informazioni e limiti"; then
  sleep 1.5; shot "informazioni"; back
  $UI wait "SELEZIONA FILE" 8 || fail "dopo «Informazioni» il tasto Indietro non riporta alla Home"
else
  fail "icona Informazioni non trovata"
fi

# ---------- Selettore di sistema dai riquadri ----------
for tile in "IMPORTA MAPPA" "IMPORTA TEXTURE"; do
  if $UI tap "$tile"; then
    sleep 2.5; shot "selettore da $tile"
    back; sleep 1
    go_home || fail "dopo il selettore («$tile») non torno alla Home"
  else
    fail "riquadro «$tile» non trovato"
  fi
done
[ -z "$(adb shell pidof $PKG | tr -d '\r')" ] && fail "l'app non è più in esecuzione dopo la prova dei riquadri"

# ---------- Rapporti dei file di prova (Minecraft NON installato) ----------
# 1) texture pack valido: rapporto + pulsante INSTALLA con Minecraft assente
if open_sample "Texture_Verde.mcpack"; then
  if $UI wait "INSTALLA IN MINECRAFT" 40; then
    sleep 1; shot "rapporto texture"; expect "Texture Verde HD" "Resource Pack" "Nel file c'è 1 contenuto"
    if $UI tap "INSTALLA IN MINECRAFT"; then
      if $UI wait "Minecraft non è installato" 15; then
        shot "avviso Minecraft assente"; expect "Apri il Play Store" "Chiudi"
        $UI tap "Chiudi"; sleep 1; shot "dopo Chiudi"
      else
        shot "dopo INSTALLA (nessun avviso?)"; fail "toccando INSTALLA senza Minecraft non compare l'avviso previsto"
      fi
    else
      fail "pulsante INSTALLA IN MINECRAFT non toccabile"
    fi
  else
    shot "rapporto texture mancante"; fail "il rapporto del texture pack non compare entro 40 s"
  fi
fi

# 2) gli altri file: add-on, mondo, mod Java, archivio rotto, dipendenza mancante, file non Minecraft
check_sample() { # file  testo-atteso  nome-schermata
  if open_sample "$1"; then
    if $UI wait "$2" 40; then sleep 1; shot "$3"; else shot "$3 (attesa scaduta)"; fail "$1: non compare «$2»"; fi
  fi
}
check_sample "Super_Addon.mcaddon" "Super Addon" "rapporto addon"
check_sample "Mondo_di_Prova.mcworld" "Mondo di Prova" "rapporto mondo"
check_sample "Mod_Java_Fabric.zip" "Java" "rapporto mod java"
check_sample "Pacchetto_Rotto.mcpack" "danneggiato" "rapporto archivio rotto"
check_sample "Behavior_Dipendenza_Mancante.mcpack" "Dipendenza" "rapporto dipendenza mancante"
check_sample "Appunti.txt" "riconosciuto" "rapporto file non minecraft"

# 3) la «mod» con due .mcpack separati: dev'essere un unico invio, e la dipendenza tra i due non è «mancante»
if open_sample "Gun_Mod.zip"; then
  if $UI wait "INSTALLA IN MINECRAFT" 40; then
    sleep 1; shot "rapporto gun mod"
    expect "Nel file ci sono 2 contenuti" "Gun Mod BP" "Gun Mod RP" "importali insieme" "INSTALLA IN MINECRAFT (2)" "2 di 2 selezionati"
    if $UI has "Dipendenza non trovata"; then fail "Gun_Mod: la dipendenza tra i due pacchetti risulta «non trovata»"; fi
  else
    shot "rapporto gun mod mancante"; fail "il rapporto di Gun_Mod.zip non compare entro 40 s"
  fi
fi

# ---------- Con il finto Minecraft: consegna vera, file ricevuto controllato ----------
if [ -z "$FAKE_APK" ]; then
  fail "finto Minecraft non disponibile: la consegna del file non è stata provata"
else
  go_home
  log "Installo il finto Minecraft: $FAKE_APK"
  adb install -r -t "$FAKE_APK" 2>&1 | tee "$OUT/install-fake.txt"
  grep -q Success "$OUT/install-fake.txt" || fail "installazione del finto Minecraft non riuscita"
  app_front
  go_home
  shot "home con finto minecraft"
  if $UI has "Minecraft non trovato"; then fail "con il finto Minecraft installato la Home dice ancora «Minecraft non trovato»"; fi

  # A) Gun Mod: due pacchetti → un solo .mcaddon con tutti i file
  if open_sample "Gun_Mod.zip" && $UI wait "INSTALLA IN MINECRAFT (2)" 40; then
    sleep 1; shot "rapporto gun mod con minecraft"
    if $UI tap "INSTALLA IN MINECRAFT (2)"; then
      if line=$(wait_received "Gun_Mod.mcaddon"); then
        log "ricevuto: $line"
        shot "finto minecraft ha ricevuto gun mod"
        if pull_received "Gun_Mod.mcaddon"; then
          python3 -I ci/verify_bundle.py "$OUT/received/Gun_Mod.mcaddon" "$SAMPLES/Gun_Mod.zip" | tee "$OUT/verifica-gun-mod.txt"
          [ "${PIPESTATUS[0]}" = 0 ] || fail "Gun_Mod: il file ricevuto dal finto Minecraft non contiene tutto"
        else
          fail "Gun_Mod: non riesco a leggere il file ricevuto dal finto Minecraft"
        fi
      else
        shot "gun mod non ricevuto"; fail "Gun_Mod: il finto Minecraft non ha ricevuto nulla entro 60 s"
      fi
      app_front
      if $UI wait "Minecraft ha importato il contenuto?" 20; then
        shot "domanda di conferma gun mod"
        expect "Gun Mod" "Gun Mod BP" "Gun Mod RP" "File inclusi: 16"
        if $UI tap "Sì, Minecraft l'ha importato"; then
          if $UI wait "Installazione terminata" 12; then shot "fine installazione gun mod"; else shot "dopo conferma gun mod"; fail "Gun_Mod: manca il riepilogo finale dopo la conferma"; fi
        else
          fail "Gun_Mod: pulsante «Sì, Minecraft l'ha importato» non trovato"
        fi
      else
        shot "domanda di conferma mancante"; fail "Gun_Mod: tornando nell'app non compare la domanda di conferma"
      fi
    else
      fail "Gun_Mod: pulsante INSTALLA IN MINECRAFT (2) non toccabile"
    fi
  else
    shot "gun mod con minecraft: rapporto mancante"; fail "Gun_Mod: il rapporto non compare con il finto Minecraft installato"
  fi
  go_home

  # B) elenco dei contenuti gestiti dopo la consegna
  if $UI tap "MOD INSTALLATI"; then
    sleep 1.5; shot "contenuti gestiti dopo gun mod"; expect "Gun Mod"
    if $UI tap "Gun Mod"; then
      sleep 1.5; shot "dettagli gun mod"; expect "Contiene 2 pacchetti" "Gun Mod BP" "Gun Mod RP"
      $UI tap "Chiudi"; sleep 1
    fi
    back; go_home
  else
    fail "MOD INSTALLATI non toccabile dopo la consegna"
  fi

  # C) texture pack già corretto: dev'essere consegnato identico all'originale
  if open_sample "Texture_Verde.mcpack" && $UI wait "INSTALLA IN MINECRAFT" 40; then
    sleep 1
    if $UI tap "INSTALLA IN MINECRAFT"; then
      if wait_received "Texture_Verde.mcpack" > /dev/null; then
        if pull_received "Texture_Verde.mcpack"; then
          a=$(sha256sum "$OUT/received/Texture_Verde.mcpack" | cut -d' ' -f1)
          b=$(sha256sum "$SAMPLES/Texture_Verde.mcpack" | cut -d' ' -f1)
          log "Texture_Verde: ricevuto $a, originale $b"
          [ "$a" = "$b" ] || fail "Texture_Verde: il file ricevuto non è identico all'originale (atteso: copia senza modifiche)"
        else
          fail "Texture_Verde: non riesco a leggere il file ricevuto"
        fi
      else
        fail "Texture_Verde: il finto Minecraft non ha ricevuto nulla"
      fi
      app_front
      if $UI wait "Minecraft ha importato il contenuto?" 20; then
        shot "domanda di conferma texture"
        $UI tap "No, ha dato errore" || fail "Texture_Verde: pulsante «No, ha dato errore» non trovato"
        sleep 2; shot "dopo risposta no"
      else
        shot "conferma texture mancante"; fail "Texture_Verde: non compare la domanda di conferma"
      fi
    fi
  fi
  go_home
  if $UI tap "MOD INSTALLATI"; then
    sleep 1.5; shot "contenuti gestiti dopo texture"; expect "Gun Mod" "Texture Verde HD"
    back; go_home
  fi
fi

# ---------- Elenchi dopo l'uso ----------
for label in "FILE RECENTI" "CRONOLOGIA"; do
  if $UI tap "$label"; then sleep 1.5; shot "elenco dopo uso $label"; back; go_home; fi
done

# ---------- Verifica finale ----------
sleep 2
kill $LOGCAT_PID 2>/dev/null
[ -z "$(adb shell pidof $PKG | tr -d '\r')" ] && fail "l'app non è in esecuzione alla fine della prova"
if grep -qE "FATAL EXCEPTION|ANR in $PKG" "$OUT/logcat.txt"; then
  fail "nel log compaiono crash o blocchi dell'app"
  grep -nE -A12 "FATAL EXCEPTION|ANR in $PKG" "$OUT/logcat.txt" | head -n 80 > "$OUT/crash.txt"
fi
grep -E "modinstaller|MMI|AndroidRuntime|FAKE_MC" "$OUT/logcat.txt" | head -n 400 > "$OUT/logcat-app.txt"

log "=== RIEPILOGO ==="
if [ -s "$PROBLEMS" ]; then cat "$PROBLEMS"; exit 1; else echo "Nessun problema rilevato."; fi
