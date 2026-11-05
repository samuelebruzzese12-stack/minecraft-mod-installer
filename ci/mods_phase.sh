#!/bin/bash
# Fase «MOD MANAGER» della prova su emulatore. Non si esegue da sola: la include ci/emulator_smoke.sh (usa le sue funzioni
# e variabili: $UI, $PKG, $SAMPLES, $OUT, fail, note, shot, expect, snap, seen, back, go_dash, lib_has, dl_*…).
#
# Si prova con i file di ci/make_samples.py (mod Fabric e Forge finte, una raccolta in ZIP, un resource pack Java):
# profilo proposto dal primo file, installazione, interruttore, annulla, rimozione, caricatore sbagliato, dipendenza
# mancante, verifica del profilo, due profili indipendenti, raccolta di mod, esportazione in ZIP, passaggio dal DOWNLOAD
# MANAGER, dati ancora presenti dopo la chiusura dell'app, eliminazione di un profilo.
# I file nelle cartelle dei profili si leggono DAL TELEFONO e si confrontano byte per byte (SHA-256) con gli originali.
# Le attese su pronto/bloccato/serve conferma sono controllate prima, con l'analizzatore vero, dal test EmulatorSamplesTest.

# ---- il disco del telefono: files/java/profiles/<id>/{mods,resourcepacks,shaderpacks,datapacks}
mods_ids() { adb shell run-as $PKG ls files/java/profiles 2>/dev/null | tr -d '\r' | grep -v '^$'; }
mods_ls()  { adb shell run-as $PKG ls -A "files/java/profiles/$1/$2" 2>/dev/null | tr -d '\r' | grep -v '^$'; }   # id cartella
mods_sha() { adb exec-out run-as $PKG cat "files/java/profiles/$1/$2/$3" 2>/dev/null | sha256sum | cut -d' ' -f1; } # id cartella file
mods_count() { mods_ls "$1" "$2" | grep -vc '^\.'; }                                                              # file visibili
sample_sha() { sha256sum "$SAMPLES/$1" | cut -d' ' -f1; }
zip_member_sha() { python3 -I -c "import hashlib,sys,zipfile; print(hashlib.sha256(zipfile.ZipFile(sys.argv[1]).read(sys.argv[2])).hexdigest())" "$SAMPLES/$1" "$2"; }

# Il nome sul disco (anche se disattivato) del file con quell'impronta nella cartella del profilo.
mods_find() { # id cartella sha256
  local f
  while IFS= read -r f; do
    case "$f" in ""|.*) continue ;; esac
    if [ "$(mods_sha "$1" "$2" "$f")" = "$3" ]; then echo "$f"; return 0; fi
  done < <(mods_ls "$1" "$2")
  return 1
}
# L'id del profilo che ha un file con quell'impronta in quella cartella.
mods_id_with() { # cartella sha256
  local id
  for id in $(mods_ids); do
    if mods_find "$id" "$1" "$2" > /dev/null; then echo "$id"; return 0; fi
  done
  return 1
}
mods_has() { mods_id_with "$1" "$2" > /dev/null; }                    # cartella sha256
mods_until() { local t=$1 i; shift; for i in $(seq 1 "$t"); do "$@" && return 0; sleep 1; done; return 1; }   # secondi comando…
mods_name_is() { mods_ls "$1" "$2" | grep -qxF "$3"; }               # id cartella nome-esatto

# ---- l'interfaccia
mods_wait() { $UI wait "$1" "$2" || $UI scan "$1" 4; }               # testo secondi (poi scorre la pagina)
# Porta l'app alla pagina MOD MANAGER, in cima. Dalla dashboard si tocca la scheda; altrove si torna indietro.
mods_open() {
  local i
  for i in 1 2 3 4 5 6; do
    snap
    if seen "in un'unica app"; then
      $UI tap "MOD MANAGER" > /dev/null; sleep 1.5; snap
      if seen "in un'unica app"; then return 1; fi
    fi
    if seen "MOD MANAGER" && seen "Indietro" && ! seen "TORNA ALLA DASHBOARD"; then
      $UI top; snap
      if seen "Mod Java in profili indipendenti"; then return 0; fi
    fi
    back
  done
  return 1
}
# Tocca un pulsante dell'app che apre il selettore di sistema e sceglie il file, come farebbe l'utente.
pick_file() { # testo-del-pulsante nome-del-file
  local button="$1" name="$2"
  $UI tap "$button" || { fail "il pulsante «$button» non si trova ($name)"; return 1; }
  sleep 3
  # Il selettore si apre su «Recent», che non elenca ancora tutti i file di prova: picker_choose passa da «Downloads» (vedi emulator_smoke.sh).
  if picker_choose "$name"; then
    sleep 2
    # Con la selezione multipla il selettore può restare aperto e mostrare «Open»: si conferma. Se si è già chiuso, non c'è.
    if $UI has "Open" && ! $UI has "AGGIUNGI FILE"; then $UI tap "Open" > /dev/null 2>&1; fi
    return 0
  fi
  shot "selettore senza $name"
  fail "nel selettore di sistema non trovo $name"
  back
  return 1
}
mods_add() { # nome-del-file
  mods_open || { fail "MOD MANAGER: non riesco ad aprire la pagina per aggiungere $1"; return 1; }
  pick_file "AGGIUNGI FILE (.jar, .zip)" "$1"
}
mods_clear_pending() { mods_open && $UI tap "SVUOTA L'ELENCO" > /dev/null 2>&1; sleep 1; }
# Controlla che nella schermata (ultima salvata con shot) NON ci sia un'azione che inizia con quel testo.
no_action() { # testo
  [ -z "$LAST_UI" ] && return 0
  ! grep -q "^$1" "${LAST_UI%.xml}.azioni" 2>/dev/null
}

mods_tests() {
  phase "MOD MANAGER"
  local fab_sha forge_sha dep_sha lib_sha tex_sha uno_sha due_sha tre_sha mod_sha pid pid2 f n out
  fab_sha=$(sample_sha Mod_Fabric_Esempio.jar); forge_sha=$(sample_sha Mod_Forge_Esempio.jar)
  dep_sha=$(sample_sha Mod_Con_Dipendenza.jar); lib_sha=$(sample_sha Libreria_Esempio.jar)
  tex_sha=$(sample_sha Texture_Java.zip); mod_sha=$(sample_sha Mod_Scaricata.jar)
  uno_sha=$(zip_member_sha Raccolta_Mod.zip mods/Uno.jar); due_sha=$(zip_member_sha Raccolta_Mod.zip mods/Due.jar)
  tre_sha=$(zip_member_sha Raccolta_Mod.zip mods/Tre.jar)

  # ================================================================== la pagina vuota
  go_dash
  if ! mods_open; then
    shot "mod manager non aperto"; fail "MOD MANAGER: la pagina non si apre dalla dashboard"; return
  fi
  sleep 0.5; shot "mod manager vuoto"
  expect "MOD MANAGER" "Mod Java in profili indipendenti" "Nessun profilo" "CREA IL PRIMO PROFILO" "AGGIUNGI FILE (.jar, .zip)"
  only_actions "Indietro" "Apri il menu" "CREA IL PRIMO PROFILO" "AGGIUNGI FILE (.jar, .zip)" "CERCA ONLINE (MODRINTH)"
  $UI scan "Non esegue mai il codice della mod" 8 || fail "MOD MANAGER: manca l'avviso che il codice delle mod non viene mai eseguito"
  $UI scan "CurseForge no (serve una chiave personale)" 8 || fail "MOD MANAGER: manca l'avviso che CurseForge non è collegato"
  $UI top
  [ -z "$(mods_ids)" ] || fail "MOD MANAGER: all'inizio ci sono già profili sul telefono: $(mods_ids | tr '\n' ' ')"

  # ================================================================== primo file, senza profilo: l'app propone i valori letti nel file
  mods_add Mod_Fabric_Esempio.jar || return
  if ! $UI wait "Nuovo profilo" 45; then
    shot "mod manager: profilo non proposto"
    fail "MOD MANAGER: dopo aver aggiunto un file senza profilo l'app non propone di crearne uno"
    return
  fi
  sleep 1; shot "mod manager: profilo proposto"
  expect "Nuovo profilo" "Valori proposti dal file" "Nome del profilo" "Caricatore" "Fabric" "Quilt" "Forge" "NeoForge" "CREA PROFILO" "ANNULLA"
  snap
  seen "Fabric 1.20.1" || note "MOD MANAGER: il nome proposto «Fabric 1.20.1» non compare nella lettura dell'interfaccia (dipende da come Compose espone i campi)"
  if $UI tap "CREA PROFILO"; then
    if mods_wait "Java richiesto dal gioco" 25; then
      $UI top; sleep 0.5; shot "mod manager: profilo creato"
      expect "Fabric 1.20.1" "Minecraft 1.20.1" "Java 17" "VERIFICA PROFILO" "ESPORTA I FILE ATTIVI (ZIP)"
      expect_scroll "NESSUNA OPERAZIONE DA ANNULLARE"   # sotto il bordo dello schermo
    else
      shot "mod manager: profilo non creato"; fail "MOD MANAGER: dopo CREA PROFILO non compare il profilo"; return
    fi
  else
    fail "MOD MANAGER: pulsante CREA PROFILO non trovato"; return
  fi
  [ "$(mods_ids | grep -c .)" = 1 ] || fail "MOD MANAGER: dopo CREA PROFILO le cartelle dei profili sul telefono sono $(mods_ids | grep -c .) invece di una"
  lib_has '"java_profiles"' || fail "MOD MANAGER: il profilo non è nell'archivio centrale dell'app"

  # ================================================================== installazione
  if mods_wait "Pronto" 20; then
    $UI scan "Mod_Fabric_Esempio.jar" 6 > /dev/null; sleep 0.5; shot "mod manager: file pronto"
    expect "Mod_Fabric_Esempio.jar" "Pronto"
    if $UI tap-exact "INSTALLA"; then :; else fail "MOD MANAGER: pulsante INSTALLA non trovato"; return; fi
  else
    shot "mod manager: file non pronto"; fail "MOD MANAGER: la mod Fabric non risulta «Pronta» nel profilo Fabric 1.20.1"; return
  fi
  if mods_until 40 mods_has mods "$fab_sha"; then
    pid=$(mods_id_with mods "$fab_sha"); f=$(mods_find "$pid" mods "$fab_sha")
    log "mod installata: profilo $pid, file $f, identico all'originale (SHA-256 ${fab_sha:0:16}…)"
  else
    shot "mod manager: installazione non riuscita"
    fail "MOD MANAGER: dopo INSTALLA nella cartella del profilo non c'è un file identico a Mod_Fabric_Esempio.jar"
    return
  fi
  mods_wait "Installato nel profilo" 15 || fail "MOD MANAGER: dopo l'installazione la scheda non dice «Installato nel profilo»"
  shot "mod manager: installata"
  [ "$(mods_count "$pid" mods)" = 1 ] || fail "MOD MANAGER: nella cartella mods ci sono $(mods_count "$pid" mods) file invece di uno"
  mods_ls "$pid" mods | grep -q '\.part$' && fail "MOD MANAGER: dopo l'installazione resta un file parziale (.part)"
  lib_has "\"sha256\" *: *\"$fab_sha\"" || fail "MOD MANAGER: l'archivio centrale non ha l'impronta del file installato"

  # ---- l'elenco «Nel profilo» e i dettagli
  mods_clear_pending
  if mods_wait "Nel profilo" 15; then
    $UI scan "Esempio Fabric" 8 > /dev/null; sleep 0.5; shot "mod manager: elenco del profilo"
    expect "Esempio Fabric" "$f"
    $UI scan "Mod (1)" 6 || fail "MOD MANAGER: la scheda «Mod (1)» non mostra il conteggio"
    $UI top
    if $UI tap "DETTAGLI"; then
      if $UI wait "SHA-256" 10; then
        sleep 0.5; shot "mod manager: dettagli"
        expect "Esempio Fabric" "Tipo" "File" "Dimensione" "Stato" "attivo" "Origine" "Mod_Fabric_Esempio.jar" "SHA-256: $fab_sha" "CHIUDI"
        $UI tap "CHIUDI"; sleep 1
      else
        shot "mod manager: dettagli non aperti"; fail "MOD MANAGER: DETTAGLI non apre la finestra con l'impronta del file"
      fi
    else
      fail "MOD MANAGER: pulsante DETTAGLI non trovato"
    fi
  else
    fail "MOD MANAGER: manca la sezione «Nel profilo»"
  fi

  # ================================================================== interruttore e annulla
  mods_open
  if $UI tap-switch 0; then
    if mods_until 30 mods_name_is "$pid" mods "$f.disabled"; then
      $UI scan "Disattivato: il gioco non lo carica." 8 && { sleep 0.5; shot "mod manager: disattivata"; } || fail "MOD MANAGER: dopo l'interruttore manca «Disattivato: il gioco non lo carica.»"
      mods_name_is "$pid" mods "$f" && fail "MOD MANAGER: disattivata, ma sul telefono c'è ancora anche il file con il nome normale"
    else
      shot "mod manager: interruttore senza effetto"; fail "MOD MANAGER: l'interruttore non ha aggiunto «.disabled» al file sul telefono"
    fi
    # ANNULLA riporta il file com'era.
    $UI top
    if $UI tap "ANNULLA: Disattivato"; then
      if mods_until 30 mods_name_is "$pid" mods "$f"; then
        [ "$(mods_sha "$pid" mods "$f")" = "$fab_sha" ] || fail "MOD MANAGER: dopo ANNULLA il file non è identico all'originale"
        mods_name_is "$pid" mods "$f.disabled" && fail "MOD MANAGER: dopo ANNULLA resta anche il file «.disabled»"
      else
        shot "mod manager: annulla senza effetto"; fail "MOD MANAGER: «ANNULLA: Disattivato…» non riattiva il file sul telefono"
      fi
    else
      shot "mod manager: annulla non trovato"; fail "MOD MANAGER: dopo la disattivazione non c'è il pulsante «ANNULLA: Disattivato…»"
    fi
    # Spento e riacceso con l'interruttore. Il file sul telefono cambia prima che l'app tolga la schermata di avanzamento
    # dell'operazione precedente: un tocco che arriva in quel momento finisce sulla schermata (prova 16). Si aspetta che l'interruttore
    # risulti di nuovo nello stato atteso e, se il file non cambia, si riprova il tocco.
    mods_toggle_to() { # nome-atteso-sul-telefono
      local t
      for t in 1 2 3; do
        sleep 1.5
        $UI top; $UI tap-switch 0 > /dev/null
        if mods_until 10 mods_name_is "$pid" mods "$1"; then return 0; fi
      done
      return 1
    }
    mods_toggle_to "$f.disabled" || fail "MOD MANAGER: la seconda disattivazione non è arrivata sul telefono"
    if mods_toggle_to "$f"; then
      [ "$(mods_sha "$pid" mods "$f")" = "$fab_sha" ] || fail "MOD MANAGER: dopo la riattivazione il file non è identico all'originale"
    else
      fail "MOD MANAGER: l'interruttore non riattiva il file sul telefono"
    fi
  else
    shot "mod manager: nessun interruttore"; fail "MOD MANAGER: l'interruttore del file non si trova (come nodo selezionabile)"
  fi

  # ================================================================== rimozione e annulla
  mods_open
  if $UI tap "RIMUOVI"; then
    if $UI wait "Rimuovere dal profilo?" 10; then
      shot "mod manager: conferma rimozione"
      expect "Rimuovere dal profilo?" "Esempio Fabric" "RIMUOVI" "ANNULLA"
      $UI tap "ANNULLA"; sleep 1.5
      mods_name_is "$pid" mods "$f" || fail "MOD MANAGER: annullando la domanda di rimozione il file è sparito"
      mods_open; $UI tap "RIMUOVI" > /dev/null; $UI wait "Rimuovere dal profilo?" 10 > /dev/null
      $UI tap-last "RIMUOVI"
      if mods_until 30 eval '! mods_name_is "$pid" mods "$f"'; then
        [ "$(mods_count "$pid" mods)" = 0 ] || fail "MOD MANAGER: dopo la rimozione restano file nella cartella mods"
        mods_wait "Nessun file di tipo" 15 || fail "MOD MANAGER: dopo la rimozione l'elenco non dice «Nessun file di tipo…»"
        shot "mod manager: rimossa"
        $UI top
        if $UI tap "ANNULLA: Rimosso"; then
          if mods_until 30 mods_name_is "$pid" mods "$f"; then
            [ "$(mods_sha "$pid" mods "$f")" = "$fab_sha" ] || fail "MOD MANAGER: dopo aver annullato la rimozione il file non è identico all'originale"
          else
            fail "MOD MANAGER: «ANNULLA: Rimosso…» non rimette il file sul telefono"
          fi
        else
          fail "MOD MANAGER: dopo la rimozione non c'è il pulsante «ANNULLA: Rimosso…»"
        fi
      else
        shot "mod manager: rimozione senza effetto"; fail "MOD MANAGER: «RIMUOVI» non toglie il file dal telefono"
      fi
    else
      shot "mod manager: rimozione senza domanda"; fail "MOD MANAGER: RIMUOVI non chiede conferma"
    fi
  else
    fail "MOD MANAGER: pulsante RIMUOVI non trovato"
  fi

  # ================================================================== caricatore sbagliato: bloccata, e propone un altro profilo
  mods_add Mod_Forge_Esempio.jar
  if mods_wait "Non installabile" 45; then
    $UI scan "CREA UN PROFILO PER QUESTO FILE" 8 || fail "MOD MANAGER: per una mod Forge in un profilo Fabric manca «CREA UN PROFILO PER QUESTO FILE»"
    sleep 0.5; shot "mod manager: forge bloccata"
    no_action "INSTALLA" || fail "MOD MANAGER: per una mod Forge in un profilo Fabric compare un pulsante INSTALLA (doveva essere bloccata)"
    mods_has mods "$forge_sha" && fail "MOD MANAGER: la mod Forge è finita nel profilo Fabric"
    if $UI tap "CREA UN PROFILO PER QUESTO FILE"; then
      if $UI wait "Nuovo profilo" 15; then
        sleep 0.5; shot "mod manager: profilo forge proposto"
        expect "Nuovo profilo" "Valori proposti dal file"
        snap; seen "Forge 1.20.1" || note "MOD MANAGER: il nome proposto «Forge 1.20.1» non compare nella lettura dell'interfaccia"
        $UI tap "CREA PROFILO" > /dev/null
        if mods_wait "Forge 1.20.1" 25; then
          # Il file in attesa viene ricontrollato con il nuovo profilo attivo: ora è pronto.
          if mods_wait "Pronto" 20; then
            $UI tap-exact "INSTALLA" > /dev/null
            if mods_until 40 mods_has mods "$forge_sha"; then
              pid2=$(mods_id_with mods "$forge_sha")
              [ "$pid2" != "$pid" ] || fail "MOD MANAGER: la mod Forge è finita nello stesso profilo della mod Fabric"
              mods_find "$pid" mods "$forge_sha" > /dev/null && fail "MOD MANAGER: la mod Forge è comparsa anche nel profilo Fabric"
              mods_find "$pid2" mods "$fab_sha" > /dev/null && fail "MOD MANAGER: la mod Fabric è comparsa anche nel profilo Forge"
              [ "$(mods_count "$pid" mods)" = 1 ] || fail "MOD MANAGER: il profilo Fabric dovrebbe avere ancora un solo file"
              log "due profili indipendenti: $pid (Fabric) e $pid2 (Forge)"
            else
              shot "mod manager: forge non installata"; fail "MOD MANAGER: la mod Forge non è stata installata nel profilo Forge"
            fi
          else
            shot "mod manager: forge ancora bloccata"; fail "MOD MANAGER: dopo aver creato il profilo Forge la mod non risulta «Pronta»"
          fi
        else
          shot "mod manager: profilo forge non creato"; fail "MOD MANAGER: il profilo Forge non compare dopo CREA PROFILO"
        fi
      else
        shot "mod manager: profilo forge non proposto"; fail "MOD MANAGER: «CREA UN PROFILO PER QUESTO FILE» non apre il modulo del profilo"
      fi
    fi
  else
    shot "mod manager: forge non bloccata"; fail "MOD MANAGER: una mod Forge in un profilo Fabric non risulta «Non installabile»"
  fi

  # ---- cambio di profilo: ognuno mostra solo i suoi file
  mods_clear_pending
  mods_open
  if [ -n "$pid2" ]; then
    if $UI tap "CAMBIA"; then
      if $UI wait "Scegli il profilo" 10; then
        sleep 0.5; shot "mod manager: scelta del profilo"
        expect "Scegli il profilo" "Fabric 1.20.1" "Forge 1.20.1" "CHIUDI"
        $UI tap "Fabric 1.20.1 ·" > /dev/null
        if mods_wait "Fabric · Minecraft 1.20.1" 15; then
          $UI top; sleep 0.5; shot "mod manager: profilo Fabric scelto"
          $UI scan "Esempio Fabric" 8 || fail "MOD MANAGER: scelto il profilo Fabric non si vede la sua mod"
          $UI scan "Esempio Forge" 3 && fail "MOD MANAGER: nel profilo Fabric compare la mod del profilo Forge"
        else
          shot "mod manager: cambio profilo senza effetto"; fail "MOD MANAGER: scegliendo il profilo Fabric la pagina non lo mostra"
        fi
      else
        shot "mod manager: scelta profilo non aperta"; fail "MOD MANAGER: CAMBIA non apre la scelta del profilo"
      fi
    else
      fail "MOD MANAGER: con due profili il pulsante CAMBIA non si trova"
    fi
  fi

  # ================================================================== dipendenza mancante: serve conferma, poi la verifica del profilo la segnala
  mods_add Mod_Con_Dipendenza.jar
  if mods_wait "Serve conferma" 45; then
    $UI scan "Mancano nel profilo" 8 || fail "MOD MANAGER: manca l'elenco «Mancano nel profilo»"
    $UI scan "libesempio" 8 || fail "MOD MANAGER: la dipendenza mancante «libesempio» non è nominata"
    sleep 0.5; shot "mod manager: dipendenza mancante"
    if $UI tap "INSTALLA COMUNQUE"; then
      if $UI wait "Installare comunque?" 10; then
        sleep 0.5; shot "mod manager: conferma installazione"
        expect "Installare comunque?" "libesempio" "ANNULLA" "INSTALLA COMUNQUE"
        $UI tap "ANNULLA"; sleep 1.5
        mods_has mods "$dep_sha" && fail "MOD MANAGER: annullando la conferma la mod è stata installata lo stesso"
        mods_open; $UI tap "INSTALLA COMUNQUE" > /dev/null; $UI wait "Installare comunque?" 10 > /dev/null
        $UI tap-last "INSTALLA COMUNQUE"
        mods_until 40 mods_has mods "$dep_sha" || { shot "mod manager: installa comunque senza effetto"; fail "MOD MANAGER: dopo la conferma la mod non è nel profilo"; }
      else
        shot "mod manager: nessuna conferma"; fail "MOD MANAGER: «INSTALLA COMUNQUE…» non apre la domanda di conferma"
      fi
    else
      fail "MOD MANAGER: per una mod con dipendenza mancante manca il pulsante «INSTALLA COMUNQUE…»"
    fi
  else
    shot "mod manager: dipendenza non rilevata"; fail "MOD MANAGER: una mod che richiede una libreria assente non risulta «Serve conferma»"
  fi
  mods_clear_pending
  mods_open
  if $UI tap "VERIFICA PROFILO"; then
    if mods_wait "Verifica del profilo" 25; then
      $UI scan "libesempio" 8 || fail "MOD MANAGER: la verifica del profilo non nomina la libreria mancante «libesempio»"
      $UI scan "Controllati 2 file attivi" 6 || fail "MOD MANAGER: la verifica doveva controllare 2 file attivi"
      shot "mod manager: verifica con problema"
    else
      shot "mod manager: verifica senza esito"; fail "MOD MANAGER: «VERIFICA PROFILO» non mostra alcun esito"
    fi
  else
    fail "MOD MANAGER: pulsante VERIFICA PROFILO non trovato"
  fi
  # La libreria che mancava: dopo averla messa il profilo è pulito.
  mods_add Libreria_Esempio.jar
  if mods_wait "Pronto" 45; then
    $UI tap-exact "INSTALLA" > /dev/null
    mods_until 40 mods_has mods "$lib_sha" || { shot "mod manager: libreria non installata"; fail "MOD MANAGER: la libreria non è stata installata"; }
  else
    shot "mod manager: libreria non pronta"; fail "MOD MANAGER: la libreria di prova non risulta «Pronta»"
  fi
  mods_clear_pending
  mods_open
  if $UI tap "VERIFICA PROFILO"; then
    if mods_wait "Nessun problema trovato" 25; then
      $UI scan "Controllati 3 file attivi" 6 || fail "MOD MANAGER: la verifica doveva controllare 3 file attivi"
      shot "mod manager: verifica pulita"
    else
      shot "mod manager: verifica ancora con problemi"; fail "MOD MANAGER: dopo aver installato la libreria la verifica non dice «Nessun problema trovato»"
    fi
  fi

  # ================================================================== raccolta di mod: ogni .jar diventa un file per conto suo
  mods_add Raccolta_Mod.zip
  if mods_wait "mods/Uno.jar" 45; then
    $UI scan "mods/Due.jar" 8 || fail "MOD MANAGER: dalla raccolta manca mods/Due.jar"
    $UI scan "mods/Tre.jar" 8 || fail "MOD MANAGER: dalla raccolta manca mods/Tre.jar"
    $UI top
    if $UI tap "INSTALLA I 3 FILE PRONTI"; then
      for sha in "$uno_sha" "$due_sha" "$tre_sha"; do
        mods_until 60 mods_has mods "$sha" || { shot "mod manager: raccolta incompleta"; fail "MOD MANAGER: una delle tre mod della raccolta non è nel profilo (SHA-256 ${sha:0:16}…)"; }
      done
      [ "$(mods_count "$pid" mods)" = 6 ] || fail "MOD MANAGER: dopo la raccolta il profilo Fabric dovrebbe avere 6 mod, ne ha $(mods_count "$pid" mods)"
      shot "mod manager: raccolta installata"
    else
      shot "mod manager: raccolta senza pulsante"; fail "MOD MANAGER: con tre file pronti manca «INSTALLA I 3 FILE PRONTI»"
    fi
  else
    shot "mod manager: raccolta non letta"; fail "MOD MANAGER: la raccolta Raccolta_Mod.zip non mostra le mod che contiene"
  fi

  # ================================================================== resource pack Java: va nella sua cartella e nella sua scheda
  mods_clear_pending   # le tre schede della raccolta, già installate
  mods_add Texture_Java.zip
  if mods_wait "Texture_Java.zip" 45 && mods_wait "Pronto" 15; then
    $UI tap-exact "INSTALLA" > /dev/null
    if mods_until 40 mods_has resourcepacks "$tex_sha"; then
      mods_clear_pending
      $UI scan "Resource pack (1)" 8 || fail "MOD MANAGER: la scheda «Resource pack (1)» non mostra il conteggio"
      $UI tap "Resource pack (1)" > /dev/null; sleep 1.5
      $UI has "Nessun file di tipo" && fail "MOD MANAGER: la scheda «Resource pack» è vuota dopo l'installazione"
      shot "mod manager: resource pack"
      $UI tap "Mod (6)" > /dev/null; sleep 1
    else
      shot "mod manager: resource pack non installato"; fail "MOD MANAGER: il resource pack non è nella cartella resourcepacks del profilo"
    fi
  else
    shot "mod manager: resource pack non pronto"; fail "MOD MANAGER: il resource pack Java non risulta «Pronto»"
  fi

  # ================================================================== esportazione in ZIP (selettore di salvataggio di sistema)
  mods_open
  adb shell "rm -f /sdcard/Download/Fabric_1.20.1*.zip" > /dev/null 2>&1
  if $UI tap "ESPORTA I FILE ATTIVI (ZIP)"; then
    sleep 3; shot "mod manager: salva lo ZIP"
    if $UI tap "Save" || $UI tap "Salva"; then
      if mods_wait "Esportati" 40; then
        $UI scan "Esportati 7 file" 6 || fail "MOD MANAGER: l'esportazione doveva contare 7 file (6 mod e un resource pack)"
        sleep 0.5; shot "mod manager: esportato"
        out=$(adb shell "ls /sdcard/Download/ | grep '^Fabric_1.20.1.*zip$' | head -n 1" | tr -d '\r')
        if [ -n "$out" ]; then
          adb pull "/sdcard/Download/$out" "$OUT/esportato.zip" > /dev/null 2>&1
          if python3 -I ci/verify_export.py "$OUT/esportato.zip" mods:"$fab_sha" mods:"$dep_sha" mods:"$lib_sha" mods:"$uno_sha" mods:"$due_sha" mods:"$tre_sha" resourcepacks:"$tex_sha" > "$OUT/esportato.txt" 2>&1; then
            log "ZIP esportato corretto: $(head -n 1 "$OUT/esportato.txt" | cut -c1-300)"
          else
            fail "MOD MANAGER: lo ZIP esportato non corrisponde ai file del profilo: $(tr '\n' '|' < "$OUT/esportato.txt" | cut -c1-600)"
          fi
        else
          fail "MOD MANAGER: l'app dice di aver esportato, ma in Download non c'è nessun Fabric_1.20.1*.zip"
        fi
      else
        shot "mod manager: esportazione senza esito"; fail "MOD MANAGER: dopo «Save» l'app non mostra l'esito dell'esportazione"
      fi
    else
      shot "mod manager: selettore di salvataggio"; note "MOD MANAGER: nel selettore di salvataggio non trovo il pulsante «Save»: esportazione non provata (dipende dalla versione del selettore di sistema)"
      back; sleep 1
    fi
  else
    fail "MOD MANAGER: pulsante ESPORTA I FILE ATTIVI (ZIP) non trovato"
  fi

  # ================================================================== dal DOWNLOAD MANAGER al MOD MANAGER
  if dl_start_server; then
    dl_add "$DL_BASE/mod.jar"
    if dl_wait "Completato" 60; then
      if $UI tap "AGGIUNGI AL MOD MANAGER"; then
        if mods_wait "mod.jar" 45; then
          sleep 1; shot "mod manager: arrivato dai download"
          mods_wait "Pronto" 15 || fail "MOD MANAGER: la mod arrivata dai download non risulta «Pronta»"
          $UI tap-exact "INSTALLA" > /dev/null
          mods_until 40 mods_has mods "$mod_sha" || { shot "mod manager: mod scaricata non installata"; fail "MOD MANAGER: la mod arrivata dai download non è stata installata"; }
          mods_clear_pending
        else
          shot "mod manager: nulla dai download"; fail "MOD MANAGER: «AGGIUNGI AL MOD MANAGER» non porta il file nel MOD MANAGER"
        fi
      else
        shot "download: manca il passaggio al mod manager"; fail "DOWNLOAD MANAGER: per un .jar completato manca «AGGIUNGI AL MOD MANAGER»"
      fi
      dl_remove_card > /dev/null 2>&1
    else
      shot "download: mod.jar non completato"; fail "DOWNLOAD MANAGER: il download di mod.jar non si completa entro 60 s"
    fi
    kill $DL_PID 2>/dev/null; wait $DL_PID 2>/dev/null; DL_PID=""
  else
    fail "MOD MANAGER: il server di prova non parte (il passaggio dai download non è stato provato)"
  fi

  # ================================================================== i dati restano dopo la chiusura dell'app
  adb shell am force-stop $PKG; sleep 1.5
  adb shell am start -W -n $PKG/.MainActivity > /dev/null 2>&1; sleep 3
  if mods_open; then
    mods_wait "Java richiesto dal gioco" 20 > /dev/null
    sleep 0.5; shot "mod manager: dopo la chiusura dell'app"
    expect "Fabric 1.20.1" "Java 17"
    $UI scan "Mod (7)" 8 || fail "MOD MANAGER: dopo la chiusura dell'app il profilo Fabric non ha più le 7 mod (6 + quella dei download)"
    $UI scan "Resource pack (1)" 8 || fail "MOD MANAGER: dopo la chiusura dell'app il resource pack non è più nel profilo"
  else
    fail "MOD MANAGER: dopo la chiusura dell'app la pagina non si riapre"
  fi
  [ "$(mods_ids | grep -c .)" = 2 ] || fail "MOD MANAGER: sul telefono dovrebbero esserci 2 profili, ce ne sono $(mods_ids | grep -c .)"

  # ================================================================== eliminazione di un profilo: sparisce con tutti i suoi file
  if [ -n "$pid2" ] && mods_open && $UI tap "CAMBIA"; then
    $UI wait "Scegli il profilo" 10 > /dev/null
    $UI tap "Forge 1.20.1 ·" > /dev/null
    if mods_wait "Forge · Minecraft 1.20.1" 15; then
      $UI top
      if $UI tap "ELIMINA"; then
        if $UI wait "Eliminare il profilo?" 10; then
          shot "mod manager: elimina profilo"
          expect "Eliminare il profilo?" "Forge 1.20.1" "Questa operazione non si può annullare" "ELIMINA PROFILO" "ANNULLA"
          $UI tap "ANNULLA"; sleep 1.5
          [ "$(mods_ids | grep -c .)" = 2 ] || fail "MOD MANAGER: annullando l'eliminazione il profilo è sparito"
          $UI top; $UI tap "ELIMINA" > /dev/null; $UI wait "Eliminare il profilo?" 10 > /dev/null
          $UI tap "ELIMINA PROFILO"
          if mods_until 30 eval '[ "$(mods_ids | grep -c .)" = 1 ]'; then
            [ "$(mods_ids | head -n 1)" = "$pid" ] || fail "MOD MANAGER: dopo aver eliminato il profilo Forge è rimasto quello sbagliato"
            mods_has mods "$forge_sha" && fail "MOD MANAGER: eliminato il profilo, il file della mod Forge è ancora sul telefono"
            mods_has mods "$fab_sha" || fail "MOD MANAGER: eliminando il profilo Forge è sparito un file del profilo Fabric"
            shot "mod manager: profilo eliminato"
          else
            shot "mod manager: profilo non eliminato"; fail "MOD MANAGER: «ELIMINA PROFILO» non toglie il profilo dal telefono"
          fi
        else
          shot "mod manager: eliminazione senza domanda"; fail "MOD MANAGER: ELIMINA non chiede conferma"
        fi
      else
        fail "MOD MANAGER: pulsante ELIMINA non trovato"
      fi
    else
      fail "MOD MANAGER: non riesco a scegliere il profilo Forge per eliminarlo"
    fi
  fi
  go_dash
}
