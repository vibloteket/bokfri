# Bokfri 1.1.1

Bokfri 1.1.1 förbättrar felåterkopplingen i desktopprogrammet, gör CLI-utdata
tydligare och utökar PDF-exporten från kommandoraden. Installationspaketen är
också ungefär 30–35 procent mindre tack vare en minimerad Java-runtime och
renare paketering.

## Viktigaste ändringarna

- formulär i desktopprogrammet förklarar nu ofullständiga eller ogiltiga
  uppgifter vid sparförsök i stället för att enbart inaktivera OK-knappen
- oväntade GUI-fel visar kopierbar diagnostik och loggsökväg; loggmappen kan
  öppnas direkt från feldialogen och dialogen Om Bokfri
- `bokfri status` ger en samlad översikt över datakatalog, databasformat, valt
  företag och bokföringsår samt CLI-loggens sökväg
- oväntade CLI-fel skrivs med full diagnostik till `bokfri-cli.log`; `--verbose`
  kan även visa detaljerna i terminalen utan att störa vanliga JSON-flöden
- företag och bokföringsår markerar aktuellt val kompakt i listor, och
  bokföringsår visas med det senaste först
- `account list --filter TEXT` filtrerar konton efter nummer eller beskrivning
- texttabeller och rapporter använder konsekventa, innehållsanpassade kolumner
  med högerjusterade tal
- balansrapport, resultatrapport, huvudbok, verifikationslista och enskild
  verifikation kan exporteras till PDF från CLI med `--output`
- försäljningsrapport, följesedel och plocklista hanterar decimalantal korrekt
  och bevarar exakt decimalaritmetik för vikt och volym
- installationspaketen innehåller en minimerad Java-runtime och endast
  produktions-JAR:en; artefakterna är cirka 30–35 procent mindre än i 1.1.0

## Databas och kompatibilitet

Bokfri 1.1.1 använder samma databasformat 2 som Bokfri 1.1.0. Ingen ny
migrering krävs vid uppgradering från 1.1.0. Säkerhetskopiering före uppgradering
rekommenderas ändå som vanligt.

CLI-förenklingen ersätter de överlappande kommandona `paths`, `doctor`,
`company current` och `year current` med:

```text
bokfri status
```

## Kända begränsningar

- Paketen är inte kodsignerade. Windows SmartScreen och macOS Gatekeeper kan
  därför visa en varning vid installation eller första start.
- Kommandoradsgränssnittet täcker många centrala arbetsflöden men inte samtliga
  funktioner i desktopprogrammet.
- Windows systemtema kan visa ett större vänsterindrag i menyer än andra
  plattformar; Bokfri använder fortsatt standardbeteendet från Swing.

En fullständig teknisk ändringslista finns i
[CHANGELOG.md](https://github.com/vibloteket/bokfri/blob/v1.1.1/CHANGELOG.md).
Kontrollsummor för installationsfilerna finns i `SHA256SUMS`.

Problem och förbättringsförslag kan rapporteras på
[GitHub Issues](https://github.com/vibloteket/bokfri/issues). Publicera aldrig
bokföringsdata, personuppgifter eller andra känsliga uppgifter i en öppen
felrapport.
