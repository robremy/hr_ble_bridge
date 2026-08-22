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

## Twee APK-varianten: standard en withPwa

Elke build levert twee losse APK's op (zie Releases), die naast elkaar
geïnstalleerd kunnen worden (aparte package-namen):

- **`app-standard-debug.apk`** — de gewone bridge-only build zoals voorheen.
  De PWA blijft je bezoeken via GitHub Pages
  (`robremy.github.io/HBmonitor`).
- **`app-withpwa-debug.apk`** — bundelt de PWA-bestanden (uit de
  `HBmonitor`-repo, automatisch opgehaald en gekopieerd door
  `build-apk.yml`, met een expliciete `test -f`-controle die de build laat
  falen als er een bestand ontbreekt) en serveert ze vanaf de bridge zelf
  op `http://<bridge-ip>:8787/`. Bedoeld voor apparaten waar Chrome's Local
  Network Access-permissieprompt vastloopt (bevestigd: permissiestatus
  blijft op `"prompt"` hangen zonder ooit een popup te tonen) — omdat de
  PWA dan op hetzelfde private-netwerk-origin draait als de bridge, komt
  LNA nooit in beeld.
  `HrHttpServer.serveAsset()` serveert generiek elk bestand onder
  `assets/www/` (met padvalidatie tegen `..`/path traversal) — dus een
  nieuw bestand dat `index.html` later aantrekt (bv. een `style.css`) werkt
  vanzelf mee, zonder dat `HrHttpServer.kt` aangepast hoeft te worden.

Kanttekening: alleen `127.0.0.1`/`localhost` telt voor Chrome als "secure
context". Een LAN-IP zoals `192.168.1.66` niet — dus op een TWEEDE telefoon
die de PWA via het LAN-IP bezoekt werkt de bridge-verbinding wel, maar
registreert de service worker niet (geen offline-cache, geen "toevoegen aan
beginscherm"). Op de brugtelefoon zelf, via `http://127.0.0.1:8787/`, blijft
dat allemaal gewoon werken omdat loopback wél als secure context geldt.

**Belangrijk voor updates:** de PWA-bestanden in `app-withpwa-debug.apk`
worden bij elke bouw vers uit de `HBmonitor`-repo gekopieerd — dus een
PWA-wijziging komt pas op dit apparaat terecht na een NIEUWE build +
herinstallatie van deze APK, niet automatisch zoals bij GitHub Pages (die
direct de laatste `main`-branch serveert). Na elke PWA-push dus opnieuw de
workflow laten draaien (of gewoon wachten tot de volgende
`hr_ble_bridge`-push 'm meeneemt) en de nieuwe `app-withpwa-debug.apk`
installeren.

### Testen na installatie van app-withpwa-debug.apk

Op de brugtelefoon zelf, in Chrome:

1. `http://127.0.0.1:8787/` → moet de HBmonitor-PWA tonen (niet een lege
   pagina of een JSON-foutmelding).
2. Rechtstreeks elk statisch bestand controleren:
   - `https://127.0.0.1:8787/features.js`
   - `https://127.0.0.1:8787/sw.js`
   - `https://127.0.0.1:8787/manifest.webmanifest`
   - `https://127.0.0.1:8787/icon-192.png`
3. De bridge-endpoints (`/api/health` etc.) moeten los daarvan gewoon
   blijven werken zoals voorheen.

## HTTPS met self-signed certificaat

De server (beide flavors) draait sinds deze versie over **HTTPS** met een
automatisch gegenereerd self-signed certificaat, niet meer over plain HTTP.

**Waarom:** Chrome behandelt alleen `127.0.0.1`/`localhost` als "secure
context" bij plain HTTP. Een LAN-IP (`192.168.1.66`) telt daarbij niet mee
— met als gevolg dat `navigator.storage.persist()`, de screen wake lock en
de service worker op een tweede telefoon of een Android TV (bereikt via het
LAN-IP) allemaal stilletjes "niet ondersteund" waren. Een TLS-verbinding
telt voor Chrome wél als secure context zodra de gebruiker eenmalig de
certificaatwaarschuwing wegklikt — dat werkt daarna op élk toestel, zonder
per-toestel `chrome://flags`-workarounds (die op een TV met
afstandsbediening sowieso niet praktisch zijn).

**Hoe het werkt (`SelfSignedCertManager.kt`):**
- Bij de eerste keer opstarten wordt een RSA-keypair + zelfondertekend
  X.509-certificaat gegenereerd (via BouncyCastle, want Android/ART heeft
  zelf geen certificate builder) en opgeslagen als PKCS12-keystore in de
  eigen app-opslag (`context.filesDir`) — dus niet gedeeld, niet in de APK
  gebakken.
- Het certificaat bevat `localhost`, `127.0.0.1` én het huidige LAN-IP als
  Subject Alternative Names.
- Als het LAN-IP later verandert (DHCP-adreswissel), detecteert de app dit
  bij de volgende opstart en genereert automatisch een nieuw certificaat
  met het bijgewerkte IP — geen handmatige actie nodig.

**Wat dit betekent bij gebruik:**
- Elk toestel moet **eenmalig** de certificaatwaarschuwing van Chrome
  wegklikken bij het eerste bezoek aan `https://<bridge-ip>:8787/`
  ("Geavanceerd" → "Doorgaan naar [adres] (onveilig)"). Daarna onthoudt
  Chrome die uitzondering voor dat toestel.
- Op een Android TV moet dit met de afstandsbediening/D-pad gebeuren —
  minder soepel dan met een muis, maar te doen.
- De PWA's `BRIDGE_URL` in `HBmonitor`/`index.html` gebruikt sinds de
  bijbehorende PWA-update standaard `https://` i.p.v. `http://` als prefix.

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
