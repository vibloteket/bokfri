# Bokfri 1.1.0

Bokfri 1.1.0 gör Bokfris centrala bokföringsflöden tillgängliga både i
desktopprogrammet och från kommandoraden. Releasen innehåller också säkrare
versionshantering av databaser och säkerhetskopior, SIE-import och -export,
förbättrad decimalhantering samt många nya kontroller och automatiserade tester.

## Viktigaste ändringarna

- ett omfattande kommandoradsgränssnitt för företag, bokföringsår, konton,
  verifikationer, kunder, leverantörer, produkter, fakturor, betalningar,
  kreditfakturor, moms, rapporter, SIE och säkerhetskopior
- text- och JSON-utdata, JSON Schema för indata samt validering och förhandsvisning
  före operationer som ändrar bokföringen
- samma valda företag och bokföringsår används av desktopprogrammet och CLI;
  välj med `bokfri company use` och `bokfri year use`
- kundfakturor måste ha ett giltigt försäljningskonto från det valda
  bokföringsåret, både i desktopprogrammet och CLI
- faktura-, order- och offertrader kan använda decimalantal, exempelvis `0,5`
  timmar; lagerförda produkter kräver fortfarande heltal
- pengabelopp från SIE passerar inte längre genom binära flyttal, och monetär
  CLI-utdata visas konsekvent med två decimaler
- SIE-import med oföränderlig källfil, förhandsvisning och importhistorik samt
  SIE-export i typerna 1, 2, 3 och 4E
- fullständiga säkerhetskopior kan skapas, listas, verifieras och återställas från
  CLI med förhandsvisning och skyddad ersättning
- finansiella rapporter visar nu rader, perioder, saldon och kontrollsummor i
  text- och JSON-format
- installationspaketet för Windows installerar kommandot `bokfri` på PATH och
  tar bort det korrekt vid avinstallation

## Databas och uppgradering

Bokfri 1.1.0 skriver databasformat 2. När en databas i det äldre format 1 öppnas
krävs uttryckligt godkännande. Bokfri skapar och verifierar då först en fullständig
säkerhetskopia och migrerar därefter databasen. Från CLI kan formatet kontrolleras
och migreras separat med:

```text
bokfri database status
bokfri database migrate
```

Nya säkerhetskopior har ett versionshanterat JSON-manifest. Bokfri 1.1.0 kan
fortfarande verifiera och återställa äldre säkerhetskopior från Bokfri 1.0.1.

## Viktigt före uppgradering

1. Skapa en fullständig säkerhetskopia i Bokfri 1.0.1.
2. Behåll originalinstallationen och säkerhetskopian tills innehållet har
   kontrollerats i Bokfri 1.1.0.
3. Kontrollera företag, bokföringsår, verifikationer och viktiga rapporter efter
   migreringen.
4. Använd en kopia av data om du först vill prova migreringen utan att påverka
   originalet.

Direkt migrering från det mycket gamla formatet `bookkeeper.db` stöds inte. Det
måste först migreras genom en historisk JFS/Fribok-version som kan läsa formatet.

## Kända begränsningar

- Paketen är inte kodsignerade. Windows SmartScreen och macOS Gatekeeper kan
  därför visa en varning vid installation eller första start.
- Kommandoradsgränssnittet täcker många centrala arbetsflöden men inte samtliga
  funktioner i desktopprogrammet.
- BAS-konton och kontonamn kommer från BAS 2026. Momskoder har i många fall
  ärvts från äldre Bokfri-planer där dessa varit entydiga och bör kontrolleras
  mot den egna verksamheten.
- Vissa teckenberoende SRU-kopplingar kan inte representeras av Bokfris nuvarande
  enkla SRU-fält och har därför lämnats tomma.

En fullständig teknisk ändringslista finns i
[CHANGELOG.md](https://github.com/vibloteket/bokfri/blob/v1.1.0/CHANGELOG.md).
Kontrollsummor för installationsfilerna finns i `SHA256SUMS`.

Problem och förbättringsförslag kan rapporteras på
[GitHub Issues](https://github.com/vibloteket/bokfri/issues). Publicera aldrig
bokföringsdata, personuppgifter eller andra känsliga uppgifter i en öppen
felrapport.
