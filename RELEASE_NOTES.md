# Bokfri 1.0.1

Bokfri 1.0.1 är en underhållsrelease med rättningar för momsavräkning,
verifikationsmallar och fakturautskrifter. Den innehåller också praktiska
guider som hjälper nya användare från val av kontoplan till den första
verifikationen.

## Viktigaste ändringarna

- momsavräkning använder BAS 2026-kontona 1650 och 2650 och avrundar saldon på
  ett säkrare sätt
- standardkontot för ingående moms är nu 2641 och standardkontot för bank är
  1930
- äldre verifikationsmallar kan åter läsas in; mallar med samma namn ersätts
  först efter bekräftelse i stället för att dupliceras
- fakturor med många rader håller rader och summor åtskilda, även över flera
  sidor
- föråldrade QR-fält har tagits bort från fakturalayouten och OCR-informationen
  har fått tydligare svensk text och placering
- nya steg-för-steg-guider för att skapa företag och bokföringsår, registrera
  ingående balanser och bokföra den första verifikationen
- Java 21-bygget har städats från kvarvarande compiler- och konfigurationsvarningar

En fullständig teknisk ändringslista finns i
[CHANGELOG.md](https://github.com/vibloteket/bokfri/blob/v1.0.1/CHANGELOG.md).

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
