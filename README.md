# HR BLE Bridge

Kleine, losstaande Android-app die de BLE-verbinding met de Xiaomi Smart Band
10 vasthoudt als **foreground service** — dat overleeft Android's
achtergrondbeperkingen, in tegenstelling tot een Chrome-tab op de
achtergrond.

## Werking

1. Je koppelt de band eenmalig via Android's eigen Bluetooth-instellingen
   (net als op het scherm dat je liet zien: "wil koppelen").
2. In deze app kies je het gekoppelde apparaat uit de lijst.
3. De foreground service verbindt via GATT met de standaard Bluetooth
   Heart Rate Service (`0x180D`) / Measurement characteristic (`0x2A37`) —
   dezelfde UUID's die de bestaande PWA al gebruikt.
4. Elke meting en elk verbindingsevent wordt append-only weggeschreven naar
   gedeelde opslag:
   - `/storage/emulated/0/HBmonitor/hr_stream.jsonl`
     → `{"ts":"2026-07-26T14:28:45","bpm":76,"contact":1}`
   - `/storage/emulated/0/HBmonitor/hr_events.jsonl`
     → `{"ts":"2026-07-26T14:28:49","bericht":"BLE verbonden met ..."}`

Dit is dezelfde map (`HBmonitor`) als waar de Termux sync-server
(`hr_sync_server.py`) zijn SQLite-bestand verwacht — dus Termux kan deze
JSONL-bestanden direct uitlezen ("tailen") en in dezelfde SQLite-database
zetten. Die tailer-stap is nog niet gebouwd; dat is het volgende
puzzelstukje.

## Permissies die de app nodig heeft

| Permissie | Waarvoor |
|---|---|
| `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` | verbinden met het gekoppelde apparaat |
| `ACCESS_FINE_LOCATION` (alleen <Android 12) | oudere Android-versies eisen dit voor BLE |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` | service blijft draaien op de achtergrond |
| `POST_NOTIFICATIONS` | verplichte statusnotificatie tonen |
| `MANAGE_EXTERNAL_STORAGE` ("alle bestanden") | wegschrijven naar `/HBmonitor/` zodat Termux erbij kan |

De app vraagt deze in twee stappen aan (knop 1 en 2 in de app zelf).

## Bouwen (zonder PC, alleen met Termux)

**Belangrijk:** ik kan dit Android-project hier niet zelf compileren — mijn
omgeving heeft geen Android SDK/Gradle-toegang. Omdat jij geen PC hebt, is
de handigste route: **laat GitHub Actions het bouwen in de cloud**, en
download daarna de kant-en-klare APK rechtstreeks op je telefoon. Dit
project bevat al een workflow (`.github/workflows/build-apk.yml`) die dat
automatisch regelt.

### Stap 1 - maak een (nieuwe, lege) GitHub-repo aan

Doe dit via de GitHub-app of github.com in Chrome op je telefoon:
`github.com/new` -> naam bv. `hr-ble-bridge` -> **Create repository**
(leeg laten, geen README aanvinken).

### Stap 2 - Personal Access Token aanmaken

Termux kan niet inloggen via een browser-popup, dus heb je een token
nodig om te kunnen pushen:
`github.com/settings/tokens` -> **Generate new token (classic)** ->
scope `repo` aanvinken -> token kopiëren (je ziet 'm maar één keer).

### Stap 3 - project pushen vanuit Termux

```bash
pkg install git -y
cd ~/storage/shared/Download        # of waar je de zip hebt uitgepakt
unzip HrBleBridge.zip
cd HrBleBridge

git init
git add .
git commit -m "Eerste versie HR BLE bridge"
git branch -M main
git remote add origin https://github.com/<jouw-gebruikersnaam>/hr-ble-bridge.git
git push -u origin main
```

Bij `git push` vraagt Termux om een gebruikersnaam en wachtwoord — vul bij
wachtwoord het **token** uit stap 2 in (niet je echte GitHub-wachtwoord).

### Stap 4 - laat het bouwen

Zodra je pusht, start de workflow vanzelf. Volg de voortgang op
`github.com/<jouw-gebruikersnaam>/hr-ble-bridge/actions` (duurt meestal
3-6 minuten). Wil je het handmatig opnieuw laten bouwen zonder te pushen?
Ga naar het tabblad **Actions** -> **Build APK** -> **Run workflow**.

### Stap 5 - APK downloaden en installeren

Ga naar `github.com/<jouw-gebruikersnaam>/hr-ble-bridge/releases` in
Chrome op je telefoon, tik op het `.apk`-bestand onder de nieuwste
release. Chrome download 'm en biedt aan om te installeren (de eerste
keer moet je Chrome toestemming geven voor "apps van deze bron
installeren" — Android vraagt dit vanzelf).

### Alternatief: Android Studio (als je toch ooit bij een PC bent)

1. Open de map (`HrBleBridge/`) in Android Studio.
2. Laat Gradle syncen.
3. **Build -> Build Bundle(s) / APK(s) -> Build APK(s)**.

## Logbestand (debuggen zonder logcat/root)

De app schrijft al zijn belangrijke stappen én elke crash weg naar:

```
/storage/emulated/0/HBmonitor/bridge_debug.log
```

Bekijken kan op twee manieren:
- **In de app zelf:** knop "Toon logbestand" (laatste 200 regels).
- **Vanuit Termux:**
  ```bash
  cat ~/storage/shared/HBmonitor/bridge_debug.log
  ```



- **Niet getest** — ik heb geen Android-toestel/emulator beschikbaar in
  deze omgeving, dus dit is ongecompileerde/ongeteste broncode op basis van
  de standaard Android BLE- en foreground-service-API's. Test dit eerst
  met de band in de buurt en check de Logcat-uitvoer.
- De reconnect-logica is simpel (vaste 5 sec. vertraging); als de band
  langere tijd buiten bereik is, blijft de service wel proberen, maar
  zonder exponentiële backoff.
- MIUI/Xiaomi-telefoons zijn berucht streng met achtergrond-apps —
  zonder de app expliciet uit te zonderen van batterijoptimalisatie
  (Instellingen → Apps → HR BLE Bridge → Batterij → Onbeperkt) kan
  het systeem de service alsnog een keer killen.
- Het schrijven gebeurt nu bij elke losse meting (elke ~1-2 sec.) direct
  naar bestand — voor de testfase prima, maar op termijn wil je dit
  wellicht bufferen om schijf-I/O te beperken.
