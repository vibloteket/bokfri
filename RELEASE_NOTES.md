# Bokfri 1.2.0

Bokfri 1.2.0 moderniserar rapporter, kalkylbladsutbyte och den inbyggda
databasmotorn. Versionen förbättrar också visningen på högupplösta skärmar och
gör fler rapport- och registerflöden tillgängliga från kommandoraden.

## Viktigaste ändringarna

- rapportmotorn har uppgraderats till JasperReports 7 och använder nu inbäddade,
  plattformsoberoende rapportfonter för reproducerbara PDF-filer
- rapportförhandsvisningen är skarp på HiDPI-skärmar och Windows-gränssnittets
  text behåller läsbar storlek vid 125, 150 procent och högre bildskärmsskalning
- PDF är nu det gemensamma presentations- och arkivformatet för rapporter;
  äldre HTML-, RTF- och rapport-XLS-exporter har tagits bort
- CLI:t kan exportera ett betydligt större urval av ekonomirapporter, register,
  fakturadokument, betalningsflöden och påminnelser till PDF
- kontoplaner, kunder, artiklar, leverantörer, verifikationer och
  verifikationsmallar kan importeras och exporteras som `.xlsx` från både GUI
  och CLI
- gamla `.xls`-flöden har ersatts med `.xlsx`; JExcelAPI och Apache POI har
  ersatts av den mindre fastexcel-lösningen
- den inbyggda HSQLDB-motorn uppgraderas säkert från den äldre 1.8-katalogen
  genom en verifierad stagingkopia och en automatisk rollback-säkerhetskopia
- historiska fakturarader utan lagrad momskod och fakturor utan sparad
  12-procentig momssats kan åter öppnas och skrivas ut korrekt

## Databas och uppgradering

Bokfri 1.2.0 använder HSQLDB 2.5.0. Vid första starten med en äldre Bokfri- eller
Fribok-datakatalog:

1. skapar Bokfri en verifierad rollback-säkerhetskopia,
2. uppgraderar en separat stagingkopia av databasen,
3. öppnar och verifierar den uppgraderade kopian,
4. aktiverar den först när kontrollerna har lyckats.

Originalkatalogen behålls i säkerhetskopieringsområdet. Avsluta andra Bokfri-
och Fribok-processer före uppgraderingen och behåll både den automatiska
säkerhetskopian och den gamla installationen tills innehållet har kontrollerats.
En databas som har uppgraderats till HSQLDB 2.5 ska inte öppnas med en äldre
Bokfri- eller Fribok-version.

Bokfris logiska dataformat är fortfarande format 2. Vid uppgradering från
Bokfri 1.1.1 är det alltså databasmotorns lagringsformat som migreras, inte
bokföringsmodellens dataformat.

## Ändrade och borttagna format

- strukturerat registerutbyte använder nu `.xlsx` i stället för `.xls`
- de äldre specialflödena för kund-/artikel-XML och E-butik.se har tagits bort
- rapportförhandsvisningen exporterar PDF; strukturerad Excel-export sker via de
  dedikerade registerflödena

Ta gärna en separat säkerhetskopia innan uppgradering och verifiera kritiska
import- och exportintegrationer mot de nya formaten.

## Kända begränsningar

- Paketen är inte kodsignerade. Windows SmartScreen och macOS Gatekeeper kan
  därför visa en varning vid installation eller första start.
- Ikonerna består fortfarande huvudsakligen av fasta PNG-bilder och kan se
  mindre skarpa ut vid fraktionell bildskärmsskalning; detta påverkar inte text,
  rapporter eller exporterade PDF-filer.
- Kommandoradsgränssnittet täcker många centrala arbetsflöden men inte samtliga
  funktioner i desktopprogrammet.

En fullständig teknisk ändringslista finns i
[CHANGELOG.md](https://github.com/vibloteket/bokfri/blob/v1.2.0/CHANGELOG.md).
Kontrollsummor för installationsfilerna finns i `SHA256SUMS`.

Problem och förbättringsförslag kan rapporteras på
[GitHub Issues](https://github.com/vibloteket/bokfri/issues). Publicera aldrig
bokföringsdata, personuppgifter eller andra känsliga uppgifter i en öppen
felrapport.
