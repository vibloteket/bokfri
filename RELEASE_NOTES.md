# Bokfri 1.0.0

Bokfri 1.0.0 är den första officiella utgåvan under namnet Bokfri och börjar
projektets egen versionsserie. Programmet bygger vidare på Fribok och JFS
Accounting, men har fått en moderniserad plattform, egen distribution och en ny
visuell identitet.

## Nytt i Bokfri 1.0

- sex medföljande BAS 2026-kontoplaner för svenska företag och föreningar
- paket för Windows, macOS och Linux
- Java 21 och moderniserat bygg- och testsystem
- webbaserad svensk användarhjälp
- förbättrade rapportförhandsvisningar och plattformsoberoende datakataloger
- fortsatt stöd för verifikationer, fakturor, rapporter, SIE, BGMax samt backup
  och återställning

En fullständig teknisk ändringslista finns i
[CHANGELOG.md](https://github.com/vibloteket/bokfri/blob/v1.0.0/CHANGELOG.md).

## Viktigt före uppgradering

1. Skapa en fullständig säkerhetskopia i den gamla installationen.
2. Behåll originalinstallationen och säkerhetskopian tills innehållet har
   kontrollerats i Bokfri.
3. Efter återställning, kontrollera företag, bokföringsår, verifikationer och
   viktiga rapporter.

Direkt migrering från det mycket gamla formatet `bookkeeper.db` stöds inte. Det
måste först migreras genom en historisk JFS/Fribok-version som kan läsa formatet.

## Kända begränsningar

- Paketen är inte kodsignerade. Windows SmartScreen och macOS Gatekeeper kan
  därför visa en varning vid installation eller första start.
- BAS-konton och kontonamn kommer från BAS 2026. Momskoder har i många fall
  ärvts från äldre Bokfri-planer där dessa varit entydiga och bör kontrolleras
  mot den egna verksamheten.
- Vissa teckenberoende SRU-kopplingar kan inte representeras av Bokfris nuvarande
  enkla SRU-fält och har därför lämnats tomma.

Kontrollsummor för installationsfilerna finns i `SHA256SUMS`.

Problem och förbättringsförslag kan rapporteras på
[GitHub Issues](https://github.com/vibloteket/bokfri/issues). Publicera aldrig
bokföringsdata, personuppgifter eller andra känsliga uppgifter i en öppen
felrapport.
