#!/bin/bash
# Prova su emulatore Android dell'app completa: dashboard, menu, INSTALLER BEDROCK, Java Edition, DOWNLOAD MANAGER,
# MOD MANAGER (ci/mods_phase.sh), CERCA ONLINE (ci/catalog_phase.sh), Diagnostica, Impostazioni e sezioni non ancora costruite.
# Installa l'APK, apre file di prova dal selettore di sistema (come farebbe
# l'utente) e tocca i pulsanti. Con il FINTO Minecraft (ci/fakeminecraft) controlla anche che il file consegnato
# dall'app contenga tutto, byte per byte. Alla fine installa l'APK nuovo SOPRA quello della versione precedente per
# controllare che elenchi e cronologia non vadano persi. Salva schermate, dump dell'interfaccia e log in $OUT.
# Non si ferma al primo problema.
#
# Il finto Minecraft non importa niente: verifica solo che l'app consegni il file giusto con lo stesso Intent.
# Se Minecraft vero importi o no il file non si può provare qui.
#
# Il DOWNLOAD MANAGER si prova con un piccolo server HTTP (ci/dl_server.py) che gira su questo computer: l'emulatore lo
# raggiunge come 10.0.2.2:8765 (solo la build di prova ammette http verso quell'indirizzo). Si prova la ripresa, il taglio
# della rete, l'impronta sbagliata e il consenso a un sito nuovo. Lo stesso server imita anche l'API di Modrinth (--modrinth)
# per la fase CERCA ONLINE. Internet vero NON si prova qui (nessun accesso garantito): Modrinth vero lo prova un passo a parte
# del workflow.
PKG=com.mmi.modinstaller
FAKE_PKG=com.mojang.minecraftpe
APK=${APK:-apk/MinecraftModInstaller-debug.apk}
FAKE_APK=$(ls fake/*.apk 2>/dev/null | head -n 1)
BASE_APK=$(ls baseline/*.apk 2>/dev/null | head -n 1)
OUT=${OUT:-ci-out}
SAMPLES=${SAMPLES:-samples}
UI=${UI:-"python3 ci/ui.py"}
mkdir -p "$OUT/shots" "$OUT/ui" "$OUT/received"
PROBLEMS="$OUT/problemi.txt"; : > "$PROBLEMS"
NOTES="$OUT/note.txt"; : > "$NOTES"
STEP=0; LAST_UI=""; LAST_SHOT=""
CUR=/tmp/mmi_cur.xml
declare -A RECV_SEEN
RECV_LINE=""

log()  { echo "[$(date +%H:%M:%S)] $*"; }
fail() { log "PROBLEMA: $*"; echo "$*" >> "$PROBLEMS"; }
note() { log "NOTA: $*"; echo "$*" >> "$NOTES"; }
phase() { echo; log "=================== $* ==================="; }

# Sull'emulatore di GitHub (2 core) Android può mostrare «System UI isn't responding», «Pixel Launcher isn't responding» o simili:
# la finestra copre l'app e la prova non vede più nulla. È un problema dell'emulatore, non dell'app: si chiude («Wait» per la
# nostra app, «Close app» per i programmi di Android, vedi ui.py) e si annota. Se invece è l'app a non rispondere, è un problema
# vero e si segnala. Se la finestra non se ne va più (nell'esecuzione 9 comparve 246 volte di fila) l'emulatore è inutilizzabile:
# la prova si ferma con il codice 3 e lascia il file EMULATORE_INUTILIZZABILE, e il workflow la ripete con un emulatore nuovo.
ANR_TOTAL=0; ANR_STREAK=0
ANR_MAX_STREAK=${ANR_MAX_STREAK:-10}; ANR_MAX_TOTAL=${ANR_MAX_TOTAL:-40}
LOGCAT_PID=""

emulator_unusable() {
  echo "$1" > "$OUT/EMULATORE_INUTILIZZABILE"
  fail "emulatore inutilizzabile: $1 (non è un difetto dell'app: la prova va ripetuta con un emulatore nuovo)"
  adb shell cat /proc/loadavg > "$OUT/loadavg.txt" 2>&1
  adb shell top -b -n 1 2>/dev/null | head -n 25 > "$OUT/top.txt"
  [ -n "$LOGCAT_PID" ] && kill "$LOGCAT_PID" 2>/dev/null
  exit 3
}

# $1 = titolo della finestra appena chiusa da «ui.py anr» (può essere vuoto).
anr_seen() {
  local title="$1"
  ANR_TOTAL=$((ANR_TOTAL+1)); ANR_STREAK=$((ANR_STREAK+1))
  note "finestra di Android «${title:-non risponde}» chiusa (n. $ANR_TOTAL, di fila $ANR_STREAK)"
  if echo "$title" | grep -qiE "Minecraft Mod Installer|$PKG"; then fail "Android segnala che l'app non risponde: $title"; fi
  if [ $((ANR_STREAK % 3)) -eq 0 ]; then
    # Chiuderla non basta: si ferma con la forza il programma appeso (di solito il launcher) e si torna alla Home.
    note "la finestra ricompare: fermo il launcher e torno alla Home"
    adb shell am force-stop com.google.android.apps.nexuslauncher > /dev/null 2>&1
    adb shell am force-stop com.android.launcher3 > /dev/null 2>&1
    adb shell input keyevent KEYCODE_HOME > /dev/null 2>&1
    sleep 6
  fi
  if [ "$ANR_STREAK" -ge "$ANR_MAX_STREAK" ]; then emulator_unusable "la finestra «non risponde» è comparsa $ANR_STREAK volte di fila"; fi
  if [ "$ANR_TOTAL" -ge "$ANR_MAX_TOTAL" ]; then emulator_unusable "in tutto $ANR_TOTAL finestre «non risponde» di Android"; fi
}
dismiss_anr() {
  local title; title=$($UI anr 2>/dev/null) || title=""
  anr_seen "$title"
  sleep 4
}

shot() {
  STEP=$((STEP+1))
  # Nome file sicuro: GitHub rifiuta nei file caricati : " < > | * ? \ / e gli a capo, quindi si tiene solo ASCII semplice.
  local n; n=$(printf "%03d-%s" "$STEP" "$(echo "$1" | LC_ALL=C sed -e 's/[^A-Za-z0-9._-]/_/g' -e 's/__*/_/g' -e 's/_$//')")
  local attempt
  for attempt in 1 2 3; do
    adb exec-out screencap -p > "$OUT/shots/$n.png" 2>/dev/null
    LAST_SHOT="$OUT/shots/$n.png"
    $UI dump "$OUT/ui/$n.xml" >/dev/null 2>&1 || break
    if grep -qiE "isn.{1,8}t responding|not responding" "$OUT/ui/$n.xml"; then dismiss_anr; else ANR_STREAK=0; break; fi
  done
  if [ -s "$OUT/ui/$n.xml" ]; then
    LAST_UI="$OUT/ui/$n.xml"
    $UI texts "$LAST_UI" > "$OUT/ui/$n.txt" 2>/dev/null
    $UI actions "$LAST_UI" > "$OUT/ui/$n.azioni" 2>/dev/null
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
# Come expect, ma per testi che possono stare sotto il bordo dello schermo (pulsanti in fondo a una scheda lunga): scorre la pagina.
# Serve anche tornare in cima, così i passi seguenti ritrovano la pagina come prima.
expect_scroll() {
  local t
  for t in "$@"; do $UI scan "$t" 6 || fail "manca «$t» (nemmeno scorrendo la pagina) nella schermata $(basename "${LAST_UI:-?}" .xml)"; done
  $UI top
}
# Le azioni toccabili della schermata (ultima salvata con shot) devono essere solo quelle elencate: serve a provare
# che le sezioni non ancora costruite non hanno pulsanti finti.
only_actions() {
  [ -z "$LAST_UI" ] && return
  local line a ok
  while IFS= read -r line; do
    [ -z "$line" ] && continue
    ok=0
    for a in "$@"; do [ "$line" = "$a" ] && ok=1; done
    [ $ok = 1 ] || fail "$(basename "$LAST_UI" .xml): azione inattesa «$line» (nessun pulsante finto ammesso)"
  done < <($UI actions "$LAST_UI")
}

back() { adb shell input keyevent KEYCODE_BACK; sleep 1.2; }
app_front() { adb shell am start -n $PKG/.MainActivity > /dev/null 2>&1; sleep 2; }

# Legge la schermata attuale in $CUR; «seen TESTO» dice se il testo compare nell'ultima lettura.
snap() {
  local i
  for i in 1 2 3; do
    rm -f "$CUR"; $UI dump "$CUR" >/dev/null 2>&1
    if [ -s "$CUR" ] && grep -qiE "isn.{1,8}t responding|not responding" "$CUR"; then dismiss_anr; else ANR_STREAK=0; return 0; fi
  done
}
seen() { [ -s "$CUR" ] && $UI has-in "$CUR" "$1" 2>/dev/null; }

# Porta l'app alla dashboard (la prima schermata). Dalla dashboard «Indietro» esce dall'app, quindi si controlla prima.
go_dash() {
  local i
  for i in 1 2 3 4 5 6; do
    snap
    if seen "in un'unica app"; then return 0; fi
    back
  done
  app_front; snap; seen "in un'unica app"
}
# Porta l'app alla home dell'INSTALLER BEDROCK (nella versione precedente era la schermata iniziale).
go_home() {
  local i
  for i in 1 2 3 4 5 6; do
    snap
    if seen "SELEZIONA FILE"; then return 0; fi
    if seen "in un'unica app"; then $UI tap "INSTALLER BEDROCK" > /dev/null; sleep 1.5; continue; fi
    back
  done
  return 1
}
# Apre il menu laterale e sceglie una voce (l'ultima corrispondenza è quella del menu, composto dopo la pagina).
open_from_menu() {
  $UI tap "Apri il menu" || return 1
  sleep 1.5
  $UI tap-last "$1" || return 1
  sleep 1.5
}

# Colore di sfondo (somma di R+G+B) della pagina nell'ultimo screenshot: chiaro ≈ 740, scuro ≈ 52.
bg_sum() {
  local rgb; rgb=$(python3 -I ci/pixel.py "$LAST_SHOT" 6 1000 2>/dev/null) || { echo -1; return; }
  set -- $rgb; echo $(( $1 + $2 + $3 ))
}
check_theme() { # light|dark  descrizione
  local want="$1" what="$2" sum; sum=$(bg_sum)
  log "sfondo ($what): somma RGB $sum"
  if [ "$sum" -lt 0 ]; then fail "$what: non riesco a leggere il colore dello sfondo"
  elif [ "$want" = dark ] && [ "$sum" -ge 200 ]; then fail "$what: lo sfondo dovrebbe essere scuro (somma RGB $sum)"
  elif [ "$want" = light ] && [ "$sum" -le 600 ]; then fail "$what: lo sfondo dovrebbe essere chiaro (somma RGB $sum)"
  fi
}

pull_lib() { adb exec-out run-as $PKG cat files/library/library.json > "$1" 2>/dev/null; [ -s "$1" ]; }
lib_has() { pull_lib "$OUT/.lib.tmp" && grep -Eq "$1" "$OUT/.lib.tmp"; }

# Sceglie un file nel selettore di sistema GIÀ APERTO, come farebbe l'utente (il selettore resta quello di Android: l'app non c'entra).
# Si apre su «Recent», che elenca solo i file che Android ha già indicizzato: sull'emulatore appena avviato ne mostra pochi (nelle prove
# 10 e 11 ne vedeva 2 su una ventina). L'elenco vero è la cartella «Downloads», che si raggiunge dal pannello laterale («Show roots»).
# Si prova, in quest'ordine: l'elenco com'è, la cartella Downloads, la ricerca per nome. Ogni tocco su un file usa «tap-item», che
# scorre la lista e IGNORA i campi di testo (la ricerca ripete il nome nel proprio campo, e «tap» avrebbe toccato quello).
# I tentativi falliti lasciano schermata e testi (fino a 6 per prova) per capire cosa vedeva il selettore.
PICKER_DIAG=0
picker_diag() { # etichetta
  [ "$PICKER_DIAG" -ge 6 ] && return 0
  PICKER_DIAG=$((PICKER_DIAG+1))
  shot "selettore: $1"
}
picker_choose() { # nome-del-file
  local name="$1" i
  $UI wait "Show roots" 30 > /dev/null 2>&1 || $UI wait "Mostra radici" 3 > /dev/null 2>&1 || true
  if $UI tap-item "$name" > /dev/null 2>&1; then return 0; fi
  picker_diag "$name non e in Recent"
  if ! $UI has "Files in Downloads"; then
    for i in 1 2 3; do
      ($UI tap "Show roots" || $UI tap "Mostra radici") > /dev/null 2>&1 || { sleep 2; continue; }
      if $UI wait "Downloads" 8; then
        $UI tap-exact "Downloads" > /dev/null 2>&1 || $UI tap "Downloads" > /dev/null 2>&1
        if $UI wait "Files in Downloads" 12; then break; fi
        picker_diag "dopo il tocco su Downloads (tentativo $i)"
      else
        picker_diag "pannello delle radici non aperto (tentativo $i)"
      fi
    done
  fi
  if $UI tap-item "$name" > /dev/null 2>&1; then return 0; fi
  picker_diag "$name non e in Downloads"
  # La ricerca del selettore: dentro una cartella cerca per nome in quella cartella, da «Recent» in tutte le radici.
  if $UI tap "Search" > /dev/null 2>&1; then
    sleep 1.5
    $UI fill 0 "$name" > /dev/null 2>&1 || picker_diag "campo di ricerca non scrivibile"
    adb shell input keyevent KEYCODE_ENTER > /dev/null 2>&1
    for i in 1 2 3 4 5 6; do
      sleep 2
      if $UI tap-item "$name" > /dev/null 2>&1; then return 0; fi
    done
    picker_diag "ricerca senza risultati per $name"
  fi
  return 1
}

# Apre un file di prova dal selettore di sistema, come farebbe l'utente.
open_sample() {
  local name="$1"
  go_home || { fail "non torno alla Home prima di aprire $name"; return 1; }
  $UI tap "SELEZIONA FILE" || { fail "SELEZIONA FILE non toccabile ($name)"; return 1; }
  sleep 3
  if picker_choose "$name"; then
    sleep 1.5
    $UI tap "Open" || $UI tap "Apri" || true
    return 0
  fi
  shot "selettore senza $name"
  fail "nel selettore di sistema non trovo $name"
  back
  return 1
}

# Aspetta che il finto Minecraft scriva nel log di aver ricevuto un file NUOVO (una ricezione in più rispetto
# all'ultima attesa con lo stesso nome); la riga finisce in $RECV_LINE. Va chiamata senza $( ), per conservare il conteggio.
wait_received() {
  local name="$1" i n seen=${RECV_SEEN[$1]:-0}
  for i in $(seq 1 60); do
    n=$(grep -c "FAKE_MC.*RECEIVED name=$name " "$OUT/logcat.txt" 2>/dev/null); n=${n:-0}
    if [ "$n" -gt "$seen" ]; then
      RECV_SEEN[$name]=$n
      RECV_LINE=$(grep "FAKE_MC.*RECEIVED name=$name " "$OUT/logcat.txt" | tail -n 1)
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

# La memoria condivisa (/sdcard) si monta qualche secondo DOPO la fine dell'avvio: copiare i file troppo presto
# fallisce con «Operation not permitted» e il selettore di sistema resta vuoto. Si aspetta, si copia un file
# alla volta e si controlla la dimensione di ciascuno.
wait_storage() {
  local i
  for i in $(seq 1 90); do
    if adb shell 'mkdir -p /sdcard/Download && echo x > /sdcard/Download/.pronto && rm -f /sdcard/Download/.pronto && echo PRONTO' 2>/dev/null | grep -q PRONTO; then
      log "memoria condivisa pronta (tentativo $i)"
      return 0
    fi
    sleep 2
  done
  return 1
}

# ======================================================================================================== quale parte, e il salvavita

# Quale parte della prova eseguire: «base» (tutto tranne MOD MANAGER e CERCA ONLINE), «mods» (solo MOD MANAGER), «catalog» (solo
# CERCA ONLINE, che si prepara da sé il profilo) o «all» (tutto di fila, una dopo l'altra).
# L'emulatore del runner di GitHub (2 core) è lentissimo: ogni passaggio dura decine di secondi e tutto di fila supera l'ora e mezza.
# Per questo il workflow lancia le tre parti in tre lavori in parallelo, ciascuno con il suo emulatore.
PART=${SMOKE_PART:-all}
case "$PART" in all|base|mods|catalog) ;; *) echo "SMOKE_PART non valido: $PART (ammessi: all, base, mods, catalog)"; exit 2 ;; esac
runs() { [ "$PART" = all ] || [ "$PART" = "$1" ]; }
log "Parte della prova: $PART"

# Salvavita: se la prova supera il tempo massimo si ferma da sola, ma prima salva schermata, interfaccia e log dell'ultimo punto
# raggiunto. Senza, un passaggio che si pianta consuma le due ore del lavoro e lascia poche tracce.
BUDGET_SEC=${SMOKE_BUDGET_SEC:-5100}
time_is_up() {
  trap '' USR1 TERM
  fail "tempo massimo della prova superato ($((BUDGET_SEC / 60)) minuti): mi fermo qui (ultima schermata: zz-tempo-scaduto)"
  adb exec-out screencap -p > "$OUT/shots/zz-tempo-scaduto.png" 2>/dev/null
  $UI dump "$OUT/ui/zz-tempo-scaduto.xml" > /dev/null 2>&1
  [ -s "$OUT/ui/zz-tempo-scaduto.xml" ] && $UI texts "$OUT/ui/zz-tempo-scaduto.xml" > "$OUT/ui/zz-tempo-scaduto.txt" 2>/dev/null
  [ -n "$LOGCAT_PID" ] && kill "$LOGCAT_PID" 2>/dev/null
  grep -E "modinstaller|MMI|AndroidRuntime|FAKE_MC" "$OUT/logcat.txt" 2>/dev/null | head -n 600 > "$OUT/logcat-app.txt"
  [ -n "${DL_PID:-}" ] && kill "$DL_PID" 2>/dev/null
  exit 1
}
trap time_is_up USR1
( sleep "$BUDGET_SEC"; kill -USR1 $$ 2>/dev/null ) > /dev/null 2>&1 &
WATCHDOG_PID=$!
trap 'kill "$WATCHDOG_PID" 2>/dev/null' EXIT

# ======================================================================================================== preparazione

log "Dispositivo: $(adb shell getprop ro.product.model | tr -d '\r'), Android API $(adb shell getprop ro.build.version.sdk | tr -d '\r'), schermo $(adb shell wm size | tr -d '\r')"
adb shell svc power stayon true > /dev/null 2>&1
adb shell input keyevent KEYCODE_WAKEUP > /dev/null 2>&1
adb logcat -c
adb logcat -v threadtime > "$OUT/logcat.txt" 2>&1 &
LOGCAT_PID=$!

# Subito dopo l'avvio l'emulatore è ancora carico (servizi, indicizzazione): con 2 core è normale restare con il carico alto per
# minuti. Si aspetta che il carico del processore scenda (al massimo 4 minuti), chiudendo nel frattempo le finestre «non risponde».
wait_calm() {
  local i load t calm=0
  sleep 15
  for i in $(seq 1 24); do
    if t=$($UI anr 2>/dev/null); then anr_seen "$t"; sleep 4; calm=0; continue; fi
    ANR_STREAK=0
    load=$(adb shell cat /proc/loadavg 2>/dev/null | tr -d '\r' | cut -d' ' -f1)
    if [ -z "$load" ]; then note "non riesco a leggere il carico del sistema: attendo 45 s"; sleep 45; return 0; fi
    log "carico del sistema (media su 1 minuto): $load"
    if awk -v l="$load" 'BEGIN { exit !(l < 3.0) }'; then
      calm=$((calm+1)); [ "$calm" -ge 2 ] && return 0
    else
      calm=0
    fi
    sleep 8
  done
  note "il carico del sistema non è sceso sotto 3 in 4 minuti: proseguo lo stesso"
}
log "Attendo che il sistema si calmi"
adb shell input keyevent KEYCODE_HOME > /dev/null 2>&1
wait_calm

log "Installo l'APK: $APK"
adb install -r -t "$APK" 2>&1 | tee "$OUT/install.txt"
if ! grep -q Success "$OUT/install.txt"; then
  fail "installazione dell'APK non riuscita"
  kill $LOGCAT_PID 2>/dev/null; exit 1
fi

: > "$OUT/push.txt"
if wait_storage; then
  for f in "$SAMPLES"/*; do
    n=$(basename "$f"); want=$(stat -c %s "$f"); ok=0
    for attempt in 1 2 3 4 5; do
      adb push "$f" "/sdcard/Download/$n" >> "$OUT/push.txt" 2>&1
      got=$(adb shell "stat -c %s '/sdcard/Download/$n'" 2>/dev/null | tr -d '\r')
      if [ "$got" = "$want" ]; then ok=1; break; fi
      log "copia di $n non riuscita (tentativo $attempt): atteso $want byte, trovati '$got'"
      sleep 3
    done
    [ $ok = 1 ] || fail "non riesco a copiare $n nella memoria dell'emulatore"
  done
else
  fail "la memoria condivisa dell'emulatore non è diventata disponibile"
fi
adb shell ls -l /sdcard/Download > "$OUT/download-dir.txt" 2>&1

# ======================================================================================================== permessi

if runs base; then
phase "Permessi dichiarati dall'app"
adb shell dumpsys package $PKG > "$OUT/dumpsys-package.txt" 2>&1
# L'unico permesso ammesso è INTERNET (serve al DOWNLOAD MANAGER, che fa solo richieste per ricevere file).
python3 -I - "$OUT/dumpsys-package.txt" > "$OUT/permessi.txt" <<'PY'
import sys
lines = open(sys.argv[1], errors="replace").read().splitlines()
perms = []
for i, line in enumerate(lines):
    if line.strip() == "requested permissions:":
        indent = len(line) - len(line.lstrip())
        for nxt in lines[i + 1:]:
            if not nxt.strip() or len(nxt) - len(nxt.lstrip()) <= indent:
                break
            perms.append(nxt.strip().split(":")[0])
        break
print("PERMESSI: " + (" ".join(perms) if perms else "(nessuno)"))
bad = [p for p in perms if p.startswith("android.permission.") and p != "android.permission.INTERNET"]
print("INATTESI: " + (" ".join(bad) if bad else "(nessuno)"))
print("INTERNET: " + ("sì" if "android.permission.INTERNET" in perms else "no"))
PY
cat "$OUT/permessi.txt"
grep -q "^INTERNET: sì" "$OUT/permessi.txt" || fail "l'app non dichiara il permesso INTERNET: il DOWNLOAD MANAGER non potrebbe scaricare"
grep -q "^INATTESI: (nessuno)" "$OUT/permessi.txt" || fail "l'app dichiara permessi oltre a INTERNET (vedi permessi.txt)"
if grep -Eq "android.permission.(READ|WRITE|MANAGE)_EXTERNAL_STORAGE|READ_MEDIA" "$OUT/dumpsys-package.txt"; then
  fail "l'app dichiara permessi sulla memoria del telefono: dovrebbe usare solo il selettore di sistema"
fi

# ======================================================================================================== dashboard

phase "Dashboard"
adb shell am start -W -n $PKG/.MainActivity 2>&1 | tee "$OUT/start.txt"
sleep 3
shot "dashboard"
expect "Minecraft Mod Installer" "in un'unica app" "Stato" "Minecraft Bedrock" "non trovato" \
  "Contenuti Bedrock gestiti" "Runtime Java" "non integrato" "Errori registrati" "Sezioni" \
  "INSTALLER BEDROCK" "JAVA EDITION" "Pronto" "Parziale" "In arrivo"
check_theme light "tema predefinito (Automatico, telefono in modalità chiara)"

# ======================================================================================================== menu laterale

phase "Menu laterale"
if go_dash && $UI tap "Apri il menu"; then
  sleep 1.5; shot "menu laterale"
  expect "HOME" "INSTALLER BEDROCK" "JAVA EDITION" "MOD MANAGER" "MODPACK MANAGER" "WORLD MANAGER" \
    "WORLD CONVERTER" "JAVA TO BEDROCK" "DOWNLOAD MANAGER" "DIAGNOSTICA" "IMPOSTAZIONI"
  # Con il menu aperto «Indietro» deve chiudere solo il menu, non uscire dall'app.
  back; snap
  if seen "HOME"; then fail "menu: con il menu aperto il tasto Indietro non lo chiude"; fi
  seen "in un'unica app" || fail "menu: chiudendo il menu con Indietro non si resta sulla dashboard"
else
  fail "menu: l'icona «Apri il menu» non si trova nella dashboard"
fi

# ======================================================================================================== sezioni non costruite

phase "Sezioni non ancora costruite"
for s in "MODPACK MANAGER" "WORLD MANAGER" "WORLD CONVERTER" "JAVA TO BEDROCK"; do
  go_dash || { fail "$s: non trovo la dashboard"; continue; }
  if ! $UI tap "$s"; then fail "$s: scheda non trovata nella dashboard"; continue; fi
  if $UI wait "TORNA ALLA DASHBOARD" 10; then
    sleep 0.5; shot "sezione $s"
    expect "$s" "In arrivo" "Cosa farà" "A che punto è" "Non è ancora stato costruito"
    only_actions "Indietro" "Apri il menu" "TORNA ALLA DASHBOARD"
    back; sleep 0.5; snap
    seen "in un'unica app" || fail "$s: il tasto Indietro non riporta alla dashboard"
  else
    shot "sezione $s non aperta"; fail "$s: toccando la scheda non si apre la pagina della sezione"
  fi
done

# Dal menu laterale, poi il pulsante «TORNA ALLA DASHBOARD».
if go_dash && open_from_menu "MODPACK MANAGER"; then
  if $UI wait "TORNA ALLA DASHBOARD" 10; then
    shot "sezione aperta dal menu"; expect "MODPACK MANAGER" "In arrivo"
    $UI tap "TORNA ALLA DASHBOARD"; sleep 1.2; snap
    seen "in un'unica app" || fail "«TORNA ALLA DASHBOARD» non riporta alla dashboard"
  else
    fail "MODPACK MANAGER: aprendolo dal menu non compare la pagina della sezione"
  fi
else
  fail "il menu laterale non apre MODPACK MANAGER"
fi
# Dal menu laterale di una sezione: la voce HOME riporta alla dashboard; «Indietro» col menu aperto chiude solo il menu.
if go_dash && $UI tap "WORLD MANAGER" && $UI wait "TORNA ALLA DASHBOARD" 10; then
  $UI tap "Apri il menu"; sleep 1.5
  back; snap
  if seen "IMPOSTAZIONI"; then fail "menu: aperto da una sezione, Indietro non lo chiude"; fi
  seen "TORNA ALLA DASHBOARD" || fail "menu: chiudendolo con Indietro si esce dalla sezione"
  $UI tap "Apri il menu"; sleep 1.5
  if $UI tap-last "HOME"; then
    sleep 1.2; snap
    seen "in un'unica app" || fail "menu: la voce HOME non porta alla dashboard"
  else
    fail "menu: voce HOME non trovata"
  fi
fi

# ======================================================================================================== Java Edition

phase "Java Edition"
if go_dash && $UI tap "JAVA EDITION"; then
  if $UI wait "non si può ancora avviare" 10; then
    sleep 0.5; shot "java edition"
    expect "JAVA EDITION" "Java Edition non si può ancora avviare"
    only_actions "Indietro" "Apri il menu" "CAMBIA PROFILO PRESTAZIONI"
    for t in "Il tuo telefono" "Memoria consigliata" "Versione di Java richiesta" "Java 8" "Java 17" "Java 21" "CAMBIA PROFILO PRESTAZIONI"; do
      $UI scan "$t" 6 || fail "Java Edition: manca «$t»"
    done
    if $UI tap "CAMBIA PROFILO PRESTAZIONI"; then
      $UI wait "Profilo prestazioni" 10 || fail "Java Edition: «CAMBIA PROFILO PRESTAZIONI» non apre le Impostazioni"
    fi
  else
    shot "java edition non aperta"; fail "JAVA EDITION: la pagina non compare"
  fi
else
  fail "JAVA EDITION: scheda non trovata nella dashboard"
fi

# ======================================================================================================== Diagnostica (prima dell'uso)

phase "Diagnostica"
if go_dash && open_from_menu "DIAGNOSTICA"; then
  if $UI wait "Controllo dei file" 12; then
    sleep 0.5; shot "diagnostica"
    expect "Telefono" "Modello" "Android" "Architettura" "Core CPU" "Memoria libera" "Spazio libero"
    $UI scan "Spazio usato dall'app" 4 || fail "Diagnostica: manca «Spazio usato dall'app»"
    $UI top
    if $UI tap "CONTROLLA I FILE"; then
      if $UI wait "controllat" 20; then
        sleep 0.5; shot "controllo file senza contenuti"; expect "Nessun problema"
      else
        shot "controllo file senza esito"; fail "Diagnostica: «CONTROLLA I FILE» non mostra alcun esito"
      fi
    else
      fail "Diagnostica: pulsante «CONTROLLA I FILE» non trovato"
    fi
    $UI scan "Ultimi errori" 6 || fail "Diagnostica: manca «Ultimi errori»"
    $UI scan "Nessun errore registrato" 6 || fail "Diagnostica: all'inizio dovrebbe dire «Nessun errore registrato»"
    if $UI tap "CONDIVIDI IL RAPPORTO"; then
      sleep 3; shot "condivisione del rapporto"
      # Il foglio di condivisione di Android 14 mostra «Sharing text» e l'anteprima del testo: deve esserci il nostro rapporto.
      $UI has "Rapporto di diagnostica" || fail "Diagnostica: «CONDIVIDI IL RAPPORTO» non apre la finestra di condivisione con il rapporto"
      back; sleep 1
      $UI wait "CONDIVIDI IL RAPPORTO" 10 || $UI scan "CONDIVIDI IL RAPPORTO" 4 || fail "Diagnostica: dopo la condivisione non si torna alla pagina"
    else
      fail "Diagnostica: pulsante «CONDIVIDI IL RAPPORTO» non trovato"
    fi
  else
    shot "diagnostica non aperta"; fail "DIAGNOSTICA: la pagina non compare"
  fi
else
  fail "DIAGNOSTICA: non si apre dal menu"
fi

# ======================================================================================================== Impostazioni

phase "Impostazioni"
if go_dash && open_from_menu "IMPOSTAZIONI"; then
  if $UI wait "Profilo prestazioni" 12; then
    sleep 0.5; shot "impostazioni"
    expect "Tema" "Automatico" "Chiaro" "Scuro" "Profilo prestazioni" "Risparmio energetico" "Bilanciato" "Prestazioni" "Personalizzato"
    for t in "Manutenzione" "SVUOTA I FILE TEMPORANEI" "AZZERA IL REGISTRO ERRORI" "Versione"; do
      $UI scan "$t" 6 || fail "Impostazioni: manca «$t»"
    done
    $UI top

    # Tema: ogni scelta cambia davvero i colori. «Automatico» segue il telefono.
    $UI tap "Scuro"; sleep 1.5; shot "tema scuro"; check_theme dark "tema Scuro"
    $UI tap "Chiaro"; sleep 1.5; shot "tema chiaro"; check_theme light "tema Chiaro"
    $UI tap "Automatico"; sleep 1
    adb shell cmd uimode night yes > /dev/null 2>&1; sleep 2.5
    shot "automatico con telefono scuro"; check_theme dark "Automatico con il telefono in modalità scura"
    adb shell cmd uimode night no > /dev/null 2>&1; sleep 2.5
    shot "automatico con telefono chiaro"; check_theme light "Automatico con il telefono in modalità chiara"

    # Profilo prestazioni e memoria personalizzata.
    $UI tap "Prestazioni"; sleep 1
    $UI tap "Personalizzato"; sleep 1.2
    shot "profilo personalizzato"; expect "2048 MB"
    if $UI tap "+"; then sleep 1; shot "memoria personalizzata"; expect "2304 MB"; else fail "Impostazioni: pulsante «+» della memoria non trovato"; fi

    # Le scelte restano dopo la chiusura dell'app: sono nell'archivio centrale.
    $UI tap "Scuro"; sleep 2.5
    lib_has '"theme" *: *"DARK"' || fail "Impostazioni: il tema scelto non è nell'archivio dell'app"
    lib_has '"profile" *: *"CUSTOM"' || fail "Impostazioni: il profilo scelto non è nell'archivio dell'app"
    lib_has '"customMemoryMb" *: *2304' || fail "Impostazioni: la memoria personalizzata non è nell'archivio dell'app"
    adb shell am force-stop $PKG; sleep 1.5
    adb shell am start -W -n $PKG/.MainActivity > /dev/null 2>&1; sleep 3
    shot "dopo riavvio con tema scuro"; check_theme dark "tema Scuro dopo il riavvio dell'app"
    if go_dash && open_from_menu "IMPOSTAZIONI" && $UI wait "Profilo prestazioni" 12; then
      shot "impostazioni dopo riavvio"; expect "Personalizzato" "2304 MB"
      # Ripristino i valori predefiniti, così le prove seguenti vedono l'aspetto normale.
      $UI tap "Automatico"; sleep 1
      $UI tap "Bilanciato"; sleep 2
      lib_has '"theme" *: *"AUTO"' || fail "Impostazioni: il ripristino del tema non è stato salvato"
      lib_has '"profile" *: *"BALANCED"' || fail "Impostazioni: il ripristino del profilo non è stato salvato"
    else
      fail "Impostazioni: non si riaprono dopo il riavvio dell'app"
    fi

    # Manutenzione: svuota i file temporanei.
    if $UI tap "SVUOTA I FILE TEMPORANEI"; then
      if $UI wait "Non c'erano file temporanei" 6 || $UI has "File temporanei eliminati"; then
        shot "file temporanei svuotati"
      else
        shot "svuota temporanei senza messaggio"; fail "Impostazioni: «SVUOTA I FILE TEMPORANEI» non mostra alcun messaggio"
      fi
    else
      fail "Impostazioni: «SVUOTA I FILE TEMPORANEI» non trovato"
    fi
  else
    shot "impostazioni non aperte"; fail "IMPOSTAZIONI: la pagina non compare"
  fi
else
  fail "IMPOSTAZIONI: non si apre dal menu"
fi

fi   # fine della parte «base» iniziale (permessi, dashboard, menu, sezioni, Java Edition, Diagnostica, Impostazioni)

# ======================================================================================================== DOWNLOAD MANAGER

# Il server di prova (ci/dl_server.py) gira su questo computer; l'app lo raggiunge come 10.0.2.2:8765.
DL_PORT=8765
DL_LOG="$OUT/dl-server.log"
DL_PID=""
DL_BASE="http://10.0.2.2:$DL_PORT"

dl_start_server() { # [argomenti in più per il server, es. --modrinth CARTELLA]
  python3 -I ci/dl_server.py --port $DL_PORT --payload "$SAMPLES/Texture_Verde.mcpack" --jar "$SAMPLES/Mod_Scaricata.jar" --out "$OUT" "$@" >> "$OUT/dl-server-stdout.txt" 2>&1 &
  DL_PID=$!
  local i
  for i in $(seq 1 30); do
    if python3 -I -c "import socket; socket.create_connection(('127.0.0.1', $DL_PORT), 1).close()" 2>/dev/null; then return 0; fi
    sleep 0.5
  done
  return 1
}

dl_size() { awk -v n="$1" '$1==n {print $2}' "$OUT/dl-expected.txt"; }   # dimensione attesa di un file del server
dl_sha()  { awk -v n="$1" '$1==n {print $3}' "$OUT/dl-expected.txt"; }   # SHA-256 atteso
dl_device_ls() { adb shell run-as $PKG ls files/downloads 2>/dev/null | tr -d '\r'; }
dl_device_sha() { adb exec-out run-as $PKG cat "files/downloads/$1" 2>/dev/null | sha256sum | cut -d' ' -f1; }
dl_device_part_size() {
  local p; p=$(dl_device_ls | grep '\.part$' | head -n 1)
  [ -n "$p" ] && adb shell run-as $PKG stat -c %s "files/downloads/$p" 2>/dev/null | tr -d '\r'
}
# Le richieste ricevute dal server per un percorso, una riga ciascuna: «RANGE|IF-RANGE» (vuoti se l'app non li ha mandati).
dl_starts() {
  python3 -I - "$DL_LOG" "$1" <<'PY'
import json, sys
for line in open(sys.argv[1], errors="replace"):
    try:
        e = json.loads(line)
    except ValueError:
        continue
    if e.get("event") == "start" and e.get("path") == sys.argv[2]:
        print("%s|%s" % (e.get("range") or "", e.get("if_range") or ""))
PY
}
dl_count() { dl_starts "$1" | grep -c .; }
# I siti che l'utente ha consentito, come li ha salvati l'app nell'archivio centrale.
lib_hosts() {
  pull_lib "$OUT/.lib.tmp" || return 1
  python3 -I -c "import json,sys; print(' '.join(json.load(open(sys.argv[1])).get('download_hosts') or []))" "$OUT/.lib.tmp"
}

# Porta l'app alla pagina DOWNLOAD MANAGER, in cima. Dalla dashboard si tocca la scheda; altrove si torna indietro.
dl_open() {
  local i
  for i in 1 2 3 4 5 6; do
    snap
    if seen "in un'unica app"; then
      $UI tap "DOWNLOAD MANAGER" > /dev/null; sleep 1.5; snap
      if seen "in un'unica app"; then return 1; fi
    fi
    if seen "DOWNLOAD MANAGER" && ! seen "TORNA ALLA DASHBOARD"; then $UI top; return 0; fi
    back
  done
  return 1
}
# Aspetta un testo, scorrendo la pagina se serve (le schede dei download possono finire sotto lo schermo).
dl_wait() { $UI wait "$1" "$2" || $UI scan "$1" 4; }
# Svuota i due campi di testo (indirizzo e impronta).
dl_clear_fields() { $UI clear-field 0 > /dev/null 2>&1; $UI clear-field 1 > /dev/null 2>&1; }
# Scrive indirizzo (e impronta) e tocca AGGIUNGI ALLA CODA.
dl_add() { # indirizzo [impronta]
  dl_open || { fail "DOWNLOAD MANAGER: non riesco ad aprire la pagina per aggiungere $1"; return 1; }
  dl_clear_fields
  $UI fill 0 "$1" || { fail "DOWNLOAD MANAGER: il campo dell'indirizzo non si trova o non accetta il testo"; return 1; }
  if [ -n "$2" ]; then $UI fill 1 "$2" || { fail "DOWNLOAD MANAGER: il campo dell'impronta non si trova o non accetta il testo"; return 1; }; fi
  $UI tap "AGGIUNGI ALLA CODA" || { fail "DOWNLOAD MANAGER: pulsante AGGIUNGI ALLA CODA non trovato"; return 1; }
}
# Elimina dall'app la prima scheda terminata (completata, non riuscita o annullata), con la conferma.
dl_remove_card() {
  dl_open || return 1
  $UI tap "ELIMINA" || return 1
  $UI wait "Eliminare il file?" 8 || return 1
  $UI tap-last "ELIMINA"; sleep 1.5
}
# Prova che il file scaricato sul telefono sia identico a quello del server.
dl_check_file() { # nome-sul-telefono nome-sul-server
  local want got
  want=$(dl_sha "$2"); got=$(dl_device_sha "$1")
  if [ -z "$got" ] || [ "$got" != "$want" ]; then
    fail "DOWNLOAD MANAGER: $1 sul telefono non è identico a quello del server (atteso ${want:0:16}…, trovato ${got:0:16}…)"
    return 1
  fi
  log "download: $1 identico a quello del server (SHA-256 ${want:0:16}…)"
  if dl_device_ls | grep -q '\.part$'; then fail "DOWNLOAD MANAGER: dopo il completamento di $1 resta un file parziale (.part)"; fi
}

download_tests() {
  phase "DOWNLOAD MANAGER"
  if ! dl_start_server; then
    fail "DOWNLOAD MANAGER: il server di prova non parte (il download non è stato provato)"
    cat "$OUT/dl-server-stdout.txt" 2>/dev/null
    return
  fi
  log "server di prova in ascolto su 127.0.0.1:$DL_PORT (per l'emulatore: 10.0.2.2:$DL_PORT)"
  adb shell settings put secure show_ime_with_hard_keyboard 0 > /dev/null 2>&1

  # ---- la pagina
  go_dash
  if ! dl_open; then
    shot "download manager non aperto"; fail "DOWNLOAD MANAGER: la pagina non si apre dalla dashboard"
    kill $DL_PID 2>/dev/null; return
  fi
  sleep 0.5; shot "download manager vuoto"
  expect "DOWNLOAD MANAGER" "Scarica un file da Internet" "Nuovo scaricamento" "AGGIUNGI ALLA CODA" "Nessun download"
  $UI scan "cdn.modrinth.com" 6 || fail "DOWNLOAD MANAGER: l'elenco dei siti consentiti non mostra cdn.modrinth.com"
  $UI top
  if [ -n "$(dl_device_ls)" ]; then fail "DOWNLOAD MANAGER: la cartella dei download non è vuota all'inizio"; fi

  # ---- controlli sugli indirizzi
  dl_add "http://example.com/x.mcpack"
  if $UI wait "solo indirizzi sicuri" 10; then
    shot "download: http rifiutato"
  else
    shot "download: http non rifiutato"; fail "DOWNLOAD MANAGER: un indirizzo http:// verso un sito qualsiasi non viene rifiutato con un messaggio"
  fi
  [ -z "$(dl_device_ls)" ] || fail "DOWNLOAD MANAGER: dopo un indirizzo rifiutato compaiono file nella cartella dei download"

  dl_add "$DL_BASE/ok.mcpack" "1234"
  if $UI wait "L'impronta non è valida" 10; then
    shot "download: impronta non valida"
  else
    shot "download: impronta non valida senza messaggio"; fail "DOWNLOAD MANAGER: un'impronta di 4 caratteri non viene rifiutata con un messaggio"
  fi

  # ---- download vero con impronta corretta (l'indirizzo resta nel campo: si cambia solo l'impronta)
  sha_ok=$(dl_sha ok.mcpack)
  $UI clear-field 1; $UI fill 1 "$sha_ok"
  snap; seen "10.0.2.2:$DL_PORT/ok.mcpack" || note "campo indirizzo: il testo scritto non compare nella lettura dell'interfaccia (dipende da come Compose espone il campo)"
  shot "download: campi compilati"
  $UI tap "AGGIUNGI ALLA CODA"
  if dl_wait "Completato" 60; then
    sleep 1; shot "download: completato"
    expect "ok.mcpack" "10.0.2.2" "SHA-256:" "verificata"
    expect_scroll "APRI NELL'INSTALLER BEDROCK" "SALVA UNA COPIA" "ELIMINA"   # sotto il bordo dello schermo
    dl_check_file ok.mcpack ok.mcpack
    lib_has '"state" *: *"COMPLETED"' || fail "DOWNLOAD MANAGER: il download completato non risulta nell'archivio centrale"
    [ "$(dl_count /ok.mcpack)" = 1 ] || fail "DOWNLOAD MANAGER: per un file piccolo il server ha ricevuto $(dl_count /ok.mcpack) richieste invece di una"
  else
    shot "download: non completato"; fail "DOWNLOAD MANAGER: il download di ok.mcpack non si completa entro 60 s"
  fi

  # ---- il file scaricato passa dal sistema di importazione di sempre (INSTALLER BEDROCK)
  if $UI tap "APRI NELL'INSTALLER BEDROCK"; then
    if $UI wait "Rapporto di analisi" 40 && $UI wait "Texture Verde HD" 30; then
      sleep 1; shot "download: rapporto dell'installer"
      expect "ok.mcpack" "Resource Pack"
    else
      shot "download: nessun rapporto"; fail "DOWNLOAD MANAGER: «APRI NELL'INSTALLER BEDROCK» non mostra il rapporto del file scaricato"
    fi
    go_dash
  else
    fail "DOWNLOAD MANAGER: pulsante «APRI NELL'INSTALLER BEDROCK» non trovato"
  fi

  # ---- elimina
  if dl_remove_card; then
    sleep 1; shot "download: eliminato"
    dl_device_ls | grep -q '^ok.mcpack$' && fail "DOWNLOAD MANAGER: dopo ELIMINA il file è ancora sul telefono"
    $UI has "Nessun download" || fail "DOWNLOAD MANAGER: dopo ELIMINA l'elenco non è vuoto"
  else
    shot "download: eliminazione non riuscita"; fail "DOWNLOAD MANAGER: ELIMINA con conferma non funziona"
  fi

  # ---- impronta sbagliata: il file NON deve essere tenuto
  dl_add "$DL_BASE/wrong-hash.mcpack" "0000000000000000000000000000000000000000000000000000000000000000"
  if dl_wait "Non riuscito" 40; then
    sleep 1; shot "download: impronta sbagliata"
    expect "non corrisponde" "RIPROVA"
    if dl_device_ls | grep -q 'wrong-hash'; then fail "DOWNLOAD MANAGER: con l'impronta sbagliata il file è stato tenuto"; fi
    if dl_device_ls | grep -q '\.part$'; then fail "DOWNLOAD MANAGER: con l'impronta sbagliata resta un file parziale"; fi
    [ "$(dl_count /wrong-hash.mcpack)" = 1 ] || fail "DOWNLOAD MANAGER: con l'impronta sbagliata l'app ha ripetuto la richiesta (inutile: il file era intero)"
    dl_remove_card || fail "DOWNLOAD MANAGER: non riesco a togliere il download non riuscito"
  else
    shot "download: impronta sbagliata non rilevata"; fail "DOWNLOAD MANAGER: con l'impronta sbagliata il download non risulta «Non riuscito»"
  fi

  # ---- il file non esiste (404): errore chiaro, nessun nuovo tentativo
  dl_add "$DL_BASE/gone.bin"
  if dl_wait "Non riuscito" 40; then
    sleep 1; shot "download: errore 404"
    expect "errore 404"
    [ "$(dl_count /gone.bin)" = 1 ] || fail "DOWNLOAD MANAGER: con l'errore 404 l'app ha ripetuto la richiesta"
    dl_remove_card || fail "DOWNLOAD MANAGER: non riesco a togliere il download con errore 404"
  else
    shot "download: 404 non rilevato"; fail "DOWNLOAD MANAGER: un file inesistente (404) non risulta «Non riuscito»"
  fi

  # ---- reindirizzamento sullo stesso sito, senza impronta: si scarica e si dice che l'impronta non è stata confrontata
  dl_add "$DL_BASE/hop.mcpack"
  if dl_wait "Completato" 60; then
    sleep 1; shot "download: dopo il reindirizzamento"
    expect "hop.mcpack" "non confrontata"
    dl_check_file hop.mcpack ok.mcpack
    dl_remove_card || fail "DOWNLOAD MANAGER: non riesco a togliere il download del reindirizzamento"
  else
    shot "download: reindirizzamento non seguito"; fail "DOWNLOAD MANAGER: il reindirizzamento 302 verso lo stesso sito non viene seguito"
  fi

  # ---- pausa e ripresa: l'app deve riprendere da dove era arrivata (Range + If-Range), senza riscaricare tutto
  dl_add "$DL_BASE/slow.bin"
  if dl_wait "In corso" 20; then
    if $UI tap "PAUSA"; then
      if dl_wait "In pausa" 20; then
        sleep 2; part1=$(dl_device_part_size); sleep 2; part2=$(dl_device_part_size)
        shot "download: in pausa"
        expect "RIPRENDI" "ANNULLA"
        total=$(dl_size slow.bin)
        log "pausa: file parziale di $part1 byte su $total (dopo altri 2 s: $part2)"
        if [ -z "$part1" ] || [ "$part1" -le 0 ] || [ "$part1" -ge "$total" ]; then
          fail "DOWNLOAD MANAGER: in pausa il file parziale non è né vuoto né completo come dovrebbe ($part1 su $total)"
        fi
        [ "$part1" = "$part2" ] || fail "DOWNLOAD MANAGER: in pausa il file parziale continua a crescere ($part1 → $part2)"
        if $UI tap "RIPRENDI"; then
          if dl_wait "Completato" 90; then
            sleep 1; shot "download: ripreso e completato"
            dl_check_file slow.bin slow.bin
            starts=$(dl_starts /slow.bin); second=$(echo "$starts" | sed -n 2p)
            log "richieste per slow.bin: $(echo "$starts" | tr '\n' ' ')"
            case "$second" in
              "bytes=$part1-|"?*) ;;
              *) fail "DOWNLOAD MANAGER: la ripresa non ha chiesto «bytes=$part1-» con If-Range (seconda richiesta: «$second»)" ;;
            esac
            dl_remove_card || fail "DOWNLOAD MANAGER: non riesco a togliere il download ripreso"
          else
            shot "download: ripresa non completata"; fail "DOWNLOAD MANAGER: dopo RIPRENDI il download non si completa entro 90 s"
          fi
        else
          fail "DOWNLOAD MANAGER: pulsante RIPRENDI non trovato"
        fi
      else
        shot "download: pausa non applicata"; fail "DOWNLOAD MANAGER: toccando PAUSA il download non risulta «In pausa»"
      fi
    else
      shot "download: PAUSA non toccabile"; fail "DOWNLOAD MANAGER: pulsante PAUSA non trovato mentre scarica (finito troppo presto?)"
    fi
  else
    shot "download: lento non partito"; fail "DOWNLOAD MANAGER: il download lento non risulta «In corso»"
  fi

  # ---- la rete cade a metà: l'app riprova da sola e riprende da dove era arrivata
  dl_add "$DL_BASE/drop.bin"
  if dl_wait "Completato" 120; then
    sleep 1; shot "download: completato dopo il taglio"
    dl_check_file drop.bin drop.bin
    starts=$(dl_starts /drop.bin); second=$(echo "$starts" | sed -n 2p)
    log "richieste per drop.bin: $(echo "$starts" | tr '\n' ' ')"
    first_range=$(echo "$starts" | sed -n 1p | cut -d'|' -f1)
    [ -z "$first_range" ] || fail "DOWNLOAD MANAGER: la prima richiesta di drop.bin aveva già un Range ($first_range)"
    start=$(echo "$second" | sed -n 's/^bytes=\([0-9][0-9]*\)-|.\+/\1/p')
    if [ -z "$start" ] || [ "$start" -le 0 ] || [ "$start" -gt 1048576 ]; then
      fail "DOWNLOAD MANAGER: dopo il taglio della rete l'app non ha ripreso con Range da un punto valido (seconda richiesta: «$second»)"
    fi
    dl_remove_card || fail "DOWNLOAD MANAGER: non riesco a togliere il download del taglio"
  else
    shot "download: taglio non recuperato"; fail "DOWNLOAD MANAGER: dopo il taglio della rete il download non si completa da solo entro 120 s"
  fi

  # ---- sito https non consentito scritto a mano: domanda di consenso, e «ANNULLA» non aggiunge nulla
  dl_add "https://cdn.altro-sito.invalid/f.mcpack"
  if $UI wait "Consentire i download da" 12; then
    shot "download: consenso richiesto"
    expect "cdn.altro-sito.invalid" "CONSENTI E CONTINUA" "ANNULLA"
    $UI tap-last "ANNULLA"; sleep 1.5
    $UI has "Consentire i download da" && fail "DOWNLOAD MANAGER: «ANNULLA» non chiude la domanda di consenso"
    case " $(lib_hosts) " in *altro-sito*) fail "DOWNLOAD MANAGER: annullando la domanda il sito è stato consentito lo stesso" ;; esac
    $UI has "Nessun download" || fail "DOWNLOAD MANAGER: annullando la domanda di consenso è comparso un download"
  else
    shot "download: nessun consenso richiesto"; fail "DOWNLOAD MANAGER: un sito https non consentito non apre la domanda di consenso"
  fi

  # ---- il server manda su un sito non consentito: il download aspetta il consenso e riparte solo dopo
  dl_add "$DL_BASE/away.mcpack"
  if dl_wait "E RIPRENDI" 40; then
    sleep 1; shot "download: sito di destinazione da consentire"
    expect "cdn.consent-test.invalid" "In pausa"
    case " $(lib_hosts) " in *consent-test*) fail "DOWNLOAD MANAGER: il sito di destinazione risulta consentito prima della risposta dell'utente" ;; esac
    $UI tap "E RIPRENDI"
    if $UI wait "Consentire i download da" 10; then
      shot "download: domanda di consenso (reindirizzamento)"
      expect "cdn.consent-test.invalid" "CONSENTI E CONTINUA"
      $UI tap "CONSENTI E CONTINUA"; sleep 2
      case " $(lib_hosts) " in
        *cdn.consent-test.invalid*) log "sito consentito e salvato: $(lib_hosts)" ;;
        *) fail "DOWNLOAD MANAGER: dopo «CONSENTI E CONTINUA» il sito non è nell'archivio centrale" ;;
      esac
      # Ora il motore deve provare davvero a contattare quel sito (che non esiste): il pulsante di consenso sparisce.
      sleep 3; snap
      seen "E RIPRENDI" && fail "DOWNLOAD MANAGER: dopo il consenso il download resta fermo in attesa del consenso"
      shot "download: dopo il consenso"
      # Il sito non esiste: dopo i nuovi tentativi (1, 3 e 8 secondi) il download deve risultare «Non riuscito», con la causa.
      # (Qui non si prova ANNULLA: lo stato cambia mentre lo script guarda e nella prova 10 il tocco cadde su un altro pulsante.
      # L'annullamento ha la sua prova più sotto, con un file «incagliato» che resta in corso finché lo si annulla.)
      if dl_wait "Non riuscito" 150; then
        sleep 1; shot "download: sito inesistente"
        $UI scan "Il server non risponde" 4 || note "DOWNLOAD MANAGER: la causa dell'errore sul sito inesistente non compare nella lettura dell'interfaccia"
        $UI top
      else
        shot "download: sito inesistente senza esito"; fail "DOWNLOAD MANAGER: dopo il consenso un sito inesistente non porta il download a «Non riuscito» entro 150 s"
      fi
      dl_remove_card || fail "DOWNLOAD MANAGER: non riesco a togliere il download non riuscito"
    else
      shot "download: nessuna domanda di consenso"; fail "DOWNLOAD MANAGER: «CONSENTI … E RIPRENDI» non apre la domanda di consenso"
    fi
  else
    shot "download: reindirizzamento non fermato"; fail "DOWNLOAD MANAGER: un reindirizzamento verso un sito non consentito non ferma il download in attesa del consenso"
  fi

  # ---- annulla: un download incagliato (quasi fermo) resta «In corso» finché non lo si annulla; il file parziale sparisce
  dl_add "$DL_BASE/stuck.bin"
  if dl_wait "In corso" 40; then
    sleep 3; shot "download: incagliato in corso"
    if $UI tap "ANNULLA"; then
      if dl_wait "Annullato" 45; then
        sleep 1; shot "download: annullato"
        dl_device_ls | grep -q '\.part$' && fail "DOWNLOAD MANAGER: dopo ANNULLA resta un file parziale (.part) sul telefono"
        dl_device_ls | grep -q 'stuck' && fail "DOWNLOAD MANAGER: dopo ANNULLA restano file di stuck.bin sul telefono"
        dl_remove_card || fail "DOWNLOAD MANAGER: non riesco a togliere il download annullato"
      else
        shot "download: annullamento senza effetto"; fail "DOWNLOAD MANAGER: ANNULLA non porta il download a «Annullato» entro 45 s"
      fi
    else
      shot "download: ANNULLA non trovato"; fail "DOWNLOAD MANAGER: pulsante ANNULLA non trovato mentre il download è in corso"
    fi
  else
    shot "download: incagliato non partito"; fail "DOWNLOAD MANAGER: il download incagliato non risulta «In corso»"
  fi

  # ---- indirizzo già in coda + chiusura dell'app a metà: niente riparte da solo, poi RIPRENDI continua dal punto raggiunto
  dl_add "$DL_BASE/slow2.bin"
  if dl_wait "In corso" 20; then
    dl_add "$DL_BASE/slow2.bin"
    $UI wait "già nella lista" 10 || fail "DOWNLOAD MANAGER: lo stesso indirizzo aggiunto due volte non viene rifiutato"
    sleep 2
    adb shell am force-stop $PKG; sleep 2
    pre=$(dl_device_part_size); n_before=$(dl_count /slow2.bin)
    log "app chiusa a metà: file parziale di ${pre:-?} byte, richieste ricevute finora: $n_before"
    adb shell am start -W -n $PKG/.MainActivity > /dev/null 2>&1; sleep 3
    if dl_open && dl_wait "In pausa" 25; then
      shot "download: dopo la chiusura dell'app"
      expect "Interrotto quando l'app si è chiusa" "RIPRENDI"
      sleep 6
      [ "$(dl_count /slow2.bin)" = "$n_before" ] || fail "DOWNLOAD MANAGER: dopo il riavvio dell'app il download è ripartito da solo"
      if $UI tap "RIPRENDI"; then
        if dl_wait "Completato" 90; then
          sleep 1; shot "download: ripreso dopo la chiusura dell'app"
          dl_check_file slow2.bin slow2.bin
          starts=$(dl_starts /slow2.bin); second=$(echo "$starts" | sed -n 2p)
          log "richieste per slow2.bin: $(echo "$starts" | tr '\n' ' ')"
          case "$second" in
            "bytes=$pre-|"?*) ;;
            *) fail "DOWNLOAD MANAGER: dopo la chiusura dell'app la ripresa non ha chiesto «bytes=$pre-» (seconda richiesta: «$second»)" ;;
          esac
          dl_remove_card || fail "DOWNLOAD MANAGER: non riesco a togliere il download ripreso dopo la chiusura"
        else
          shot "download: ripresa dopo chiusura non completata"; fail "DOWNLOAD MANAGER: dopo la chiusura dell'app e RIPRENDI il download non si completa entro 90 s"
        fi
      else
        fail "DOWNLOAD MANAGER: pulsante RIPRENDI non trovato dopo il riavvio"
      fi
    else
      shot "download: stato dopo la chiusura"; fail "DOWNLOAD MANAGER: dopo la chiusura dell'app il download in corso non risulta «In pausa»"
    fi
  else
    shot "download: slow2 non partito"; fail "DOWNLOAD MANAGER: il secondo download lento non risulta «In corso»"
  fi

  # ---- niente è rimasto nella cartella dei download, e il server ha visto solo quello che ci si aspetta
  go_dash
  rest=$(dl_device_ls)
  [ -z "$rest" ] || fail "DOWNLOAD MANAGER: alla fine nella cartella dei download restano file: $(echo $rest)"
  kill $DL_PID 2>/dev/null
  wait $DL_PID 2>/dev/null
  DL_PID=""
}

runs base && download_tests

# ======================================================================================================== MOD MANAGER

# La fase sta in un file a parte (ci/mods_phase.sh): usa le funzioni e le variabili di questo script.
source ci/mods_phase.sh
if runs mods; then
  # Nella parte «mods» l'app non è ancora stata aperta (la dashboard la apre la parte «base»).
  [ "$PART" = mods ] && { adb shell am start -W -n $PKG/.MainActivity > /dev/null 2>&1; sleep 3; }
  mods_tests
fi

# ======================================================================================================== CERCA ONLINE (catalogo Modrinth)

# Anche questa fase sta in un file a parte (ci/catalog_phase.sh) e parla con un finto Modrinth servito da ci/dl_server.py.
source ci/catalog_phase.sh
if runs catalog; then
  # Nella parte «catalog» l'app è appena installata e mai aperta: si apre qui, poi catalog_tests prepara il suo profilo.
  [ "$PART" = catalog ] && { adb shell am start -W -n $PKG/.MainActivity > /dev/null 2>&1; sleep 3; }
  catalog_tests
fi

# ======================================================================================================== INSTALLER BEDROCK (Minecraft assente)

if runs base; then
phase "INSTALLER BEDROCK, Minecraft non installato"
go_dash
if $UI tap "INSTALLER BEDROCK"; then
  $UI wait "SELEZIONA FILE" 10 || fail "INSTALLER BEDROCK: la scheda non apre la home dell'installer"
  sleep 0.5; shot "installer home"
  expect "SELEZIONA FILE" "IMPORTA MOD" "IMPORTA MAPPA" "IMPORTA TEXTURE" "IMPORTA ADD-ON" "FILE RECENTI" "MOD INSTALLATI" "CRONOLOGIA" "BACKUP" "Minecraft non trovato" "Apri il menu"
  # Dalla home dell'installer «Indietro» porta alla dashboard (non esce dall'app).
  back; snap
  seen "in un'unica app" || fail "INSTALLER BEDROCK: Indietro dalla home non riporta alla dashboard"
else
  fail "scheda INSTALLER BEDROCK non trovata nella dashboard"
fi
go_home || fail "non riesco a tornare alla home dell'installer"

for label in "FILE RECENTI" "MOD INSTALLATI" "CRONOLOGIA" "BACKUP"; do
  if $UI tap "$label"; then
    sleep 1.5; shot "elenco $label"
    back
    $UI wait "SELEZIONA FILE" 8 || fail "dopo «$label» il tasto Indietro non riporta alla home dell'installer"
  else
    fail "pulsante «$label» non trovato nella home dell'installer"
  fi
done

if $UI tap "Informazioni e limiti"; then
  sleep 1.5; shot "informazioni"; back
  $UI wait "SELEZIONA FILE" 8 || fail "dopo «Informazioni» il tasto Indietro non riporta alla home dell'installer"
else
  fail "icona Informazioni non trovata"
fi

for tile in "IMPORTA MAPPA" "IMPORTA TEXTURE"; do
  if $UI tap "$tile"; then
    sleep 2.5; shot "selettore da $tile"
    back; sleep 1
    go_home || fail "dopo il selettore («$tile») non torno alla home dell'installer"
  else
    fail "riquadro «$tile» non trovato"
  fi
done
[ -z "$(adb shell pidof $PKG | tr -d '\r')" ] && fail "l'app non è più in esecuzione dopo la prova dei riquadri"

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

# ======================================================================================================== con il finto Minecraft

phase "Consegna a Minecraft (finto Minecraft)"
if [ -z "$FAKE_APK" ]; then
  fail "finto Minecraft non disponibile: la consegna del file non è stata provata"
else
  go_home
  log "Installo il finto Minecraft: $FAKE_APK"
  adb install -r -t "$FAKE_APK" 2>&1 | tee "$OUT/install-fake.txt"
  grep -q Success "$OUT/install-fake.txt" || fail "installazione del finto Minecraft non riuscita"
  # Come per un utente che installa Minecraft dal Play Store e poi torna nell'app: prima la si manda in secondo piano.
  adb shell input keyevent KEYCODE_HOME; sleep 2
  app_front
  go_home
  t0=$(date +%s)
  if $UI wait "Versione 1.21.50.7" 40; then
    log "Minecraft riconosciuto dopo $(( $(date +%s) - t0 )) s"
    shot "home con finto minecraft"; expect "trovato"
  else
    shot "home con finto minecraft non riconosciuto"
    fail "con il finto Minecraft installato la home dell'installer non lo riconosce entro 40 s"
    adb shell input keyevent KEYCODE_HOME; sleep 2; app_front; go_home
    $UI wait "Versione 1.21.50.7" 20 || fail "Minecraft non viene riconosciuto nemmeno dopo aver riaperto l'app"
  fi

  # La dashboard deve mostrare la stessa informazione.
  go_dash && { shot "dashboard con finto minecraft"; expect "versione 1.21.50.7"; }

  # A) Gun Mod: due pacchetti → un solo .mcaddon con tutti i file
  if open_sample "Gun_Mod.zip" && $UI wait "INSTALLA IN MINECRAFT (2)" 40; then
    sleep 1; shot "rapporto gun mod con minecraft"
    if $UI tap "INSTALLA IN MINECRAFT (2)"; then
      if wait_received "Gun_Mod.mcaddon"; then
        log "ricevuto: $RECV_LINE"
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

  # C) texture pack già corretto: dev'essere consegnato identico all'originale (copia senza modifiche).
  #    Il file preparato prende il nome del pacchetto («Texture Verde HD»), non quello del file scelto.
  if open_sample "Texture_Verde.mcpack" && $UI wait "INSTALLA IN MINECRAFT" 40; then
    sleep 1
    if $UI tap "INSTALLA IN MINECRAFT"; then
      if wait_received "Texture_Verde_HD.mcpack"; then
        log "ricevuto: $RECV_LINE"
        if pull_received "Texture_Verde_HD.mcpack"; then
          a=$(sha256sum "$OUT/received/Texture_Verde_HD.mcpack" | cut -d' ' -f1)
          b=$(sha256sum "$SAMPLES/Texture_Verde.mcpack" | cut -d' ' -f1)
          log "Texture_Verde: ricevuto $a, originale $b"
          [ "$a" = "$b" ] || fail "Texture_Verde: il file ricevuto non è identico all'originale (atteso: copia senza modifiche)"
        else
          fail "Texture_Verde: non riesco a leggere il file ricevuto"
        fi
      else
        shot "texture verde non ricevuto"; fail "Texture_Verde: il finto Minecraft non ha ricevuto nulla"
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
  else
    shot "rapporto texture verde mancante"; fail "Texture_Verde: il rapporto non compare con il finto Minecraft installato"
  fi
  go_home

  # D) ZIP creato su Windows (cartelle scritte con la barra rovesciata): nel file consegnato non devono esserci
  #    «file» che sono in realtà cartelle, e ogni file del sorgente deve esserci con lo stesso contenuto.
  if open_sample "Texture_Windows.zip" && $UI wait "INSTALLA IN MINECRAFT" 40; then
    sleep 1; shot "rapporto zip windows"; expect "Texture Windows"
    if $UI tap "INSTALLA IN MINECRAFT"; then
      if wait_received "Texture_Windows.mcpack"; then
        if pull_received "Texture_Windows.mcpack"; then
          python3 -I ci/verify_windows.py "$OUT/received/Texture_Windows.mcpack" "$SAMPLES/Texture_Windows.zip" | tee "$OUT/verifica-zip-windows.txt"
          [ "${PIPESTATUS[0]}" = 0 ] || fail "Texture_Windows: il file ricevuto non è corretto (vedi verifica-zip-windows.txt)"
        else
          fail "Texture_Windows: non riesco a leggere il file ricevuto"
        fi
      else
        shot "zip windows non ricevuto"; fail "Texture_Windows: il finto Minecraft non ha ricevuto nulla"
      fi
      app_front
      if $UI wait "Minecraft ha importato il contenuto?" 20; then
        $UI tap "Sì, Minecraft l'ha importato" || fail "Texture_Windows: pulsante «Sì, Minecraft l'ha importato» non trovato"
        sleep 2; shot "dopo conferma zip windows"
      else
        shot "conferma zip windows mancante"; fail "Texture_Windows: non compare la domanda di conferma"
      fi
    fi
  else
    shot "rapporto zip windows mancante"; fail "il rapporto di Texture_Windows.zip non compare"
  fi
  go_home
  if $UI tap "MOD INSTALLATI"; then
    sleep 1.5; shot "contenuti gestiti dopo texture"; expect "Gun Mod" "Texture Verde HD" "Texture Windows"
    back; go_home
  fi
fi

# ======================================================================================================== elenchi dopo l'uso

phase "Elenchi dopo l'uso"
go_home
for label in "FILE RECENTI" "CRONOLOGIA"; do
  if $UI tap "$label"; then sleep 1.5; shot "elenco dopo uso $label"; back; go_home; fi
done

# ======================================================================================================== controllo dei file dopo l'uso

phase "Diagnostica: controllo dei file dopo l'uso"
if go_dash && open_from_menu "DIAGNOSTICA" && $UI wait "Controllo dei file" 12; then
  if $UI tap "CONTROLLA I FILE" && $UI wait "controllat" 20; then
    sleep 0.5; shot "controllo file dopo uso"; expect "Nessun problema"
  else
    shot "controllo file dopo uso senza esito"; fail "Diagnostica: il controllo dei file non dà un esito dopo l'uso"
  fi
  # Tolgo di nascosto un file preparato: la Diagnostica deve accorgersene e dirlo, senza modificare nulla.
  victim=$(adb shell run-as $PKG ls files/prepared 2>/dev/null | tr -d '\r' | grep -i windows | head -n 1)
  if [ -n "$victim" ]; then
    adb shell run-as $PKG rm "files/prepared/$victim"
    if $UI tap "CONTROLLA I FILE" && $UI wait "preparato mancante" 20; then
      sleep 0.5; shot "controllo file con un file mancante"; expect "Texture Windows" "file preparato mancante"
    else
      shot "controllo file senza segnalazione"; fail "Diagnostica: un file preparato è stato eliminato ma il controllo non lo segnala"
    fi
  else
    fail "Diagnostica: non trovo il file preparato di Texture Windows per la prova"
  fi
else
  fail "Diagnostica: non si riapre dopo l'uso"
fi

# ======================================================================================================== arresto inatteso

phase "Arresto inatteso dell'app"
if go_dash && open_from_menu "DIAGNOSTICA" && $UI wait "Controllo dei file" 12; then
  $UI tap "SVUOTA IL REGISTRO" > /dev/null 2>&1 && sleep 1   # toglie gli errori dell'uso normale, per vedere solo l'arresto
  go_dash
  adb shell settings put global hide_error_dialogs 1 > /dev/null 2>&1
  adb shell am crash $PKG > "$OUT/crash-test.txt" 2>&1
  sleep 4
  if adb shell run-as $PKG ls files/last_crash.txt > /dev/null 2>&1; then
    adb exec-out run-as $PKG cat files/last_crash.txt > "$OUT/last_crash.txt" 2>/dev/null
    log "rapporto dell'arresto scritto: $(head -c 120 "$OUT/last_crash.txt" | tr '\n' '|')"
  else
    fail "arresto inatteso: l'app non ha scritto il rapporto dell'arresto"
  fi
  [ -n "$(adb shell pidof $PKG | tr -d '\r')" ] && note "dopo «am crash» il processo dell'app era ancora in vita"
  adb shell settings put global hide_error_dialogs 0 > /dev/null 2>&1
  adb shell am start -W -n $PKG/.MainActivity > /dev/null 2>&1; sleep 3
  if $UI wait "in un'unica app" 15; then
    shot "dashboard dopo l'arresto"
    if adb shell run-as $PKG ls files/last_crash.txt > /dev/null 2>&1; then
      fail "arresto inatteso: dopo il riavvio il rapporto non è stato letto e tolto"
    fi
    if open_from_menu "DIAGNOSTICA" && $UI wait "Controllo dei file" 12; then
      if $UI scan "Arresto inatteso dell'app" 8; then
        shot "diagnostica con l'arresto"
      else
        shot "diagnostica senza l'arresto"; fail "arresto inatteso: la Diagnostica non mostra «Arresto inatteso dell'app»"
      fi
      if $UI tap "SVUOTA IL REGISTRO"; then
        sleep 1; $UI scan "Nessun errore registrato" 8 || fail "Diagnostica: dopo «SVUOTA IL REGISTRO» gli errori sono ancora elencati"
      else
        fail "Diagnostica: «SVUOTA IL REGISTRO» non trovato"
      fi
    else
      fail "Diagnostica: non si apre dopo l'arresto"
    fi
  else
    shot "app non riparte dopo l'arresto"; fail "arresto inatteso: l'app non riparte"
  fi
else
  fail "arresto inatteso: non riesco a preparare la prova"
fi

# ======================================================================================================== uscita con Indietro

phase "Tasto Indietro sulla dashboard"
if go_dash; then
  adb shell input keyevent KEYCODE_BACK; sleep 2
  focus=$(adb shell dumpsys window 2>/dev/null | grep -E "mCurrentFocus" | head -n 1)
  log "finestra attiva dopo Indietro: $focus"
  case "$focus" in
    *"$PKG"*) fail "dalla dashboard il tasto Indietro non esce dall'app" ;;
  esac
  app_front
  $UI wait "in un'unica app" 15 || fail "dopo l'uscita l'app non riparte dalla dashboard"
fi

# ======================================================================================================== aggiornamento dalla versione precedente

phase "Aggiornamento sopra la versione precedente"
if [ -z "$BASE_APK" ] || [ -z "$FAKE_APK" ]; then
  note "prova di aggiornamento NON eseguita: manca l'APK della versione precedente o il finto Minecraft"
  echo "NON ESEGUITA" > "$OUT/aggiornamento.txt"
else
  log "Versione precedente: $BASE_APK"
  adb uninstall $PKG > /dev/null 2>&1
  adb install -r -t "$BASE_APK" 2>&1 | tee "$OUT/install-precedente.txt"
  if ! grep -q Success "$OUT/install-precedente.txt"; then
    fail "aggiornamento: installazione della versione precedente non riuscita"
  else
    adb shell am start -W -n $PKG/.MainActivity > /dev/null 2>&1; sleep 3
    shot "versione precedente: home"
    expect "SELEZIONA FILE"
    old_version=$(adb shell dumpsys package $PKG | grep -m1 versionName | tr -d ' \r')
    log "versione precedente installata: $old_version"
    # Con la versione precedente apro un file, lo invio al finto Minecraft e confermo: così ci sono dati veri da conservare.
    if open_sample "Texture_Verde.mcpack" && $UI wait "INSTALLA IN MINECRAFT" 40; then
      sleep 1
      if $UI tap "INSTALLA IN MINECRAFT"; then
        if wait_received "Texture_Verde_HD.mcpack"; then
          app_front
          if $UI wait "Minecraft ha importato il contenuto?" 20; then
            $UI tap "Sì, Minecraft l'ha importato" || fail "aggiornamento: pulsante di conferma non trovato nella versione precedente"
            sleep 2
          else
            fail "aggiornamento: la versione precedente non chiede la conferma"
          fi
        else
          fail "aggiornamento: il finto Minecraft non ha ricevuto il file dalla versione precedente"
        fi
      fi
    else
      fail "aggiornamento: la versione precedente non mostra il rapporto"
    fi
    go_home
    shot "versione precedente: dopo l'uso"
    pull_lib "$OUT/library-prima.json" || fail "aggiornamento: non riesco a leggere l'archivio della versione precedente"

    # Installo la versione nuova sopra la vecchia, come farebbe l'utente.
    log "Installo la versione nuova sopra la precedente"
    adb install -r -t "$APK" 2>&1 | tee "$OUT/install-aggiornamento.txt"
    if grep -q Success "$OUT/install-aggiornamento.txt"; then
      log "versione nuova: $(adb shell dumpsys package $PKG | grep -m1 versionName | tr -d ' \r')"
      adb shell am start -W -n $PKG/.MainActivity > /dev/null 2>&1; sleep 3
      if $UI wait "in un'unica app" 20; then
        shot "dopo l'aggiornamento: dashboard"
        expect "Contenuti Bedrock gestiti"
        go_home
        if $UI tap "FILE RECENTI"; then
          sleep 1.5; shot "dopo l'aggiornamento: file recenti"; expect "Texture_Verde.mcpack"; back; go_home
        else
          fail "aggiornamento: FILE RECENTI non trovato dopo l'aggiornamento"
        fi
        if $UI tap "MOD INSTALLATI"; then
          sleep 1.5; shot "dopo l'aggiornamento: contenuti gestiti"; expect "Texture Verde HD"; back; go_home
        else
          fail "aggiornamento: MOD INSTALLATI non trovato dopo l'aggiornamento"
        fi
        if $UI tap "CRONOLOGIA"; then
          sleep 1.5; shot "dopo l'aggiornamento: cronologia"; expect "Texture Verde HD"; back; go_home
        else
          fail "aggiornamento: CRONOLOGIA non trovato dopo l'aggiornamento"
        fi
        if pull_lib "$OUT/library-dopo.json" && [ -s "$OUT/library-prima.json" ]; then
          python3 -I ci/verify_library.py "$OUT/library-prima.json" "$OUT/library-dopo.json" | tee "$OUT/aggiornamento.txt"
          [ "${PIPESTATUS[0]}" = 0 ] || fail "aggiornamento: dati persi o cambiati (vedi aggiornamento.txt)"
        else
          fail "aggiornamento: non riesco a confrontare l'archivio prima e dopo"
        fi
        # I dati salvati dalla versione precedente restano utilizzabili: il pacchetto già consegnato si può reinviare.
        if go_dash && open_from_menu "DIAGNOSTICA" && $UI wait "Controllo dei file" 12 && $UI tap "CONTROLLA I FILE" && $UI wait "controllat" 20; then
          shot "dopo l'aggiornamento: controllo dei file"; expect "Nessun problema"
        else
          fail "aggiornamento: il controllo dei file non funziona sui dati della versione precedente"
        fi
      else
        shot "dopo l'aggiornamento: app non parte"; fail "aggiornamento: dopo l'installazione sopra la vecchia l'app non mostra la dashboard"
      fi
    else
      fail "aggiornamento: installazione sopra la versione precedente non riuscita (firma o versione incompatibili?)"
    fi
  fi
fi

fi   # fine della parte «base» finale (INSTALLER BEDROCK, consegna al finto Minecraft, arresto provocato, aggiornamento)

# ======================================================================================================== verifica finale

phase "Verifica finale"
go_dash
sleep 2
kill $LOGCAT_PID 2>/dev/null
cp "$CUR" "$OUT/ultima-schermata.xml" 2>/dev/null
pull_lib "$OUT/library-finale.json" || true
[ -z "$(adb shell pidof $PKG | tr -d '\r')" ] && fail "l'app non è in esecuzione alla fine della prova"

# Gli arresti provocati di proposito («am crash», messaggio «shell-induced crash») sono attesi; ogni altro no.
python3 -I - "$OUT/logcat.txt" "$PKG" > "$OUT/crash.txt" <<'PY'
import sys
lines = open(sys.argv[1], errors="replace").read().splitlines()
pkg = sys.argv[2]
expected = unexpected = 0
for i, line in enumerate(lines):
    if "FATAL EXCEPTION" not in line:
        continue
    block = lines[i:i + 30]
    if not any(("Process: " + pkg) in b for b in block[:6]):
        continue
    if any("shell-induced crash" in b for b in block):
        expected += 1
    else:
        unexpected += 1
        print("\n".join(block))
        print("-----")
print("ARRESTI_ATTESI=%d ARRESTI_INATTESI=%d" % (expected, unexpected))
PY
summary=$(tail -n 1 "$OUT/crash.txt")
log "arresti dell'app nel log: $summary"
case "$summary" in
  *"ARRESTI_INATTESI=0") ;;
  *) fail "nel log compaiono arresti inattesi dell'app (vedi crash.txt)" ;;
esac
# L'arresto provocato di proposito sta nella parte «base»: nelle altre parti non ce ne devono essere.
if runs base; then want_crashes=1; else want_crashes=0; fi
case "$summary" in
  "ARRESTI_ATTESI=$want_crashes "*) ;;
  *) fail "gli arresti provocati di proposito nel log dovevano essere $want_crashes ($summary)" ;;
esac
if grep -q "ANR in $PKG" "$OUT/logcat.txt"; then
  fail "nel log compare un blocco dell'app (ANR)"
  grep -n -A12 "ANR in $PKG" "$OUT/logcat.txt" | head -n 60 >> "$OUT/crash.txt"
fi
grep -E "modinstaller|MMI|AndroidRuntime|FAKE_MC" "$OUT/logcat.txt" | head -n 600 > "$OUT/logcat-app.txt"

# Rete di sicurezza: GitHub rifiuta il caricamento se un nome di file contiene : " < > | * ? \ o un a capo.
find "$OUT" -depth -name '*[:"<>|*?\\]*' 2>/dev/null | while IFS= read -r bad; do
  dir=$(dirname "$bad"); base=$(basename "$bad")
  good=$(printf '%s' "$base" | LC_ALL=C sed -e 's/[:"<>|*?\\]/_/g')
  mv "$bad" "$dir/$good" && log "NOTA: rinominato per il caricamento: $base"
done

echo
log "=== RIEPILOGO ==="
[ -s "$NOTES" ] && { echo "Note:"; cat "$NOTES"; }
if [ -s "$PROBLEMS" ]; then cat "$PROBLEMS"; exit 1; else echo "Nessun problema rilevato."; fi
