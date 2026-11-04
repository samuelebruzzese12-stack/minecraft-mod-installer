#!/bin/bash
# Prova su emulatore Android: installa l'APK, avvia l'app, apre file di prova e tocca i pulsanti.
# Salva schermate, dump dell'interfaccia e log in $OUT. Non si ferma al primo problema: raccoglie tutto.
PKG=com.mmi.modinstaller
APK=${APK:-apk/MinecraftModInstaller-debug.apk}
OUT=${OUT:-ci-out}
SAMPLES=${SAMPLES:-samples}
UI="python3 ci/ui.py"
mkdir -p "$OUT/shots" "$OUT/ui"
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
    log "schermata $n: $(tr '\n' '|' < "$OUT/ui/$n.txt" | cut -c1-400)"
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
  for _ in 1 2 3 4; do
    $UI has "SELEZIONA FILE" && return 0
    back
  done
  return 1
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
pidof_app=$(adb shell pidof $PKG | tr -d '\r'); [ -z "$pidof_app" ] && fail "l'app non è più in esecuzione dopo la prova dei riquadri"

# ---------- File di prova: copia nel telefono e indicizzazione ----------
adb push "$SAMPLES"/. /sdcard/Download/ > "$OUT/push.txt" 2>&1
sleep 4
media_uri() {
  local id
  id=$(adb shell content query --uri content://media/external/downloads --projection _id --where "_display_name='$1'" 2>/dev/null | tr -d '\r' | sed -n 's/.*_id=\([0-9][0-9]*\).*/\1/p' | head -n 1)
  [ -n "$id" ] && echo "content://media/external/downloads/$id"
}
uri_of() {
  local u; u=$(media_uri "$1")
  if [ -z "$u" ]; then
    adb shell content call --uri content://media/ --method scan_volume --arg external_primary >> "$OUT/scan.txt" 2>&1
    sleep 6; u=$(media_uri "$1")
  fi
  echo "$u"
}
send_sample() {
  local uri; uri=$(uri_of "$1")
  if [ -z "$uri" ]; then fail "file di prova non trovato nel telefono: $1"; return 1; fi
  log "apro $1 come farebbe un'altra app: $uri"
  adb shell am start -W -a android.intent.action.VIEW -d "$uri" -t application/octet-stream --grant-read-uri-permission -n $PKG/.MainActivity 2>&1 | tail -n 4 | tee -a "$OUT/intents.txt"
}

# 1) texture pack valido: rapporto + pulsante INSTALLA con Minecraft assente
if send_sample "Texture_Verde.mcpack"; then
  if $UI wait "INSTALLA IN MINECRAFT" 40; then
    sleep 1; shot "rapporto texture"; expect "Texture Verde HD" "Resource Pack"
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
  go_home || fail "non riesco a tornare alla Home dopo il texture pack"
fi

# 2) add-on, 3) mondo, 4) mod Java, 5) archivio rotto, 6) dipendenza mancante, 7) file non Minecraft
check_sample() { # file  testo-atteso  nome-schermata
  if send_sample "$1"; then
    if $UI wait "$2" 40; then sleep 1; shot "$3"; else shot "$3 (attesa scaduta)"; fail "$1: non compare «$2»"; fi
    go_home || fail "dopo $1 non riesco a tornare alla Home"
  fi
}
check_sample "Super_Addon.mcaddon" "Super Addon" "rapporto addon"
check_sample "Mondo_di_Prova.mcworld" "Mondo di Prova" "rapporto mondo"
check_sample "Mod_Java_Fabric.zip" "Java" "rapporto mod java"
check_sample "Pacchetto_Rotto.mcpack" "danneggiato" "rapporto archivio rotto"
check_sample "Behavior_Dipendenza_Mancante.mcpack" "Dipendenza" "rapporto dipendenza mancante"
check_sample "Appunti.txt" "riconosciuto" "rapporto file non minecraft"

# ---------- Elenchi dopo l'uso ----------
for label in "FILE RECENTI" "CRONOLOGIA"; do
  if $UI tap "$label"; then sleep 1.5; shot "elenco dopo uso $label"; back; go_home; fi
done

# ---------- Selettore di sistema: scelta di un file come farebbe l'utente ----------
if $UI tap "SELEZIONA FILE"; then
  sleep 3; shot "selettore principale"
  $UI tap "Show roots" || $UI tap "Mostra radici" || true
  sleep 1.5; shot "selettore radici"
  ($UI tap "Downloads" || $UI tap "Download") && sleep 2.5 && shot "selettore download"
  if $UI tap "Texture_Verde.mcpack"; then
    sleep 1.5; shot "selettore file toccato"
    $UI tap "Open" || $UI tap "Apri" || true
    if $UI wait "INSTALLA IN MINECRAFT" 30; then sleep 1; shot "rapporto da selettore"; else shot "dopo selettore (nessun rapporto)"; fail "scegliendo il file dal selettore non compare il rapporto"; fi
  else
    fail "nel selettore di sistema non trovo Texture_Verde.mcpack (vedi le schermate)"
  fi
  go_home
else
  fail "SELEZIONA FILE non toccabile"
fi

# ---------- Verifica finale ----------
sleep 2
kill $LOGCAT_PID 2>/dev/null
[ -z "$(adb shell pidof $PKG | tr -d '\r')" ] && fail "l'app non è in esecuzione alla fine della prova"
if grep -qE "FATAL EXCEPTION|ANR in $PKG" "$OUT/logcat.txt"; then
  fail "nel log compaiono crash o blocchi dell'app"
  grep -nE -A12 "FATAL EXCEPTION|ANR in $PKG" "$OUT/logcat.txt" | head -n 80 > "$OUT/crash.txt"
fi
grep -E "modinstaller|MMI|AndroidRuntime" "$OUT/logcat.txt" | head -n 400 > "$OUT/logcat-app.txt"

log "=== RIEPILOGO ==="
if [ -s "$PROBLEMS" ]; then cat "$PROBLEMS"; exit 1; else echo "Nessun problema rilevato."; fi
