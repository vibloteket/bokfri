<p align="center">
  <a href="https://bokfri.viblo.se/">
    <img src="website/assets/logo.svg" alt="Bokfri" width="320">
  </a>
</p>

# Bokfri

Bokfri är ett fritt och kostnadsfritt bokföringsprogram för svenska föreningar
och småföretag. Programmet installeras på din egen dator: det kräver inget
abonnemang eller konto och bokföringsdata lagras inte i någon molntjänst.

**[Webbplats](https://bokfri.viblo.se/) ·
[Ladda ner](https://bokfri.viblo.se/download/) ·
[Användarhjälp](https://bokfri.viblo.se/help/) ·
[Rapportera problem](https://github.com/vibloteket/bokfri/issues)**

Bokfri bygger vidare på Fribok och JFS Accounting, som har använts i över 20 år, 
men har en egen identitet och versionshistorik med start vid Bokfri 1.0.

> [!NOTE]
> Summary in English in the end of the README.

![Bokfri – skapa en verifikation](website/assets/screenshot.png)

## Funktioner

- verifikationer, bokföringsår och automatkonteringar
- sex medföljande BAS 2026-kontoplaner för olika företags- och föreningsformer
- import och export av SIE-filer samt import av BGMax
- fakturor, kreditfakturor, offerter och order
- kund-, leverantörs- och artikelregister
- resultat- och balansrapporter, huvudbok och momsredovisning
- import och export av bland annat kontoplaner, kunder och artiklar
- lokal säkerhetskopiering och återställning

Bokfri är anpassat för svenska förhållanden. Andra språk i gränssnittet innebär
inte att programmet är anpassat till andra länders bokförings- eller
skatteregler.

## Ladda ner

Färdiga paket byggs för:

- **Windows** – MSI
- **macOS** – DMG
- **Linux** – AppImage

Den senaste publicerade versionen finns på
[bokfri.viblo.se/download](https://bokfri.viblo.se/download/) och under
[GitHub Releases](https://github.com/vibloteket/bokfri/releases).

## Hjälp, support och kontakt

- [Användarhjälp](https://bokfri.viblo.se/help/)
- [Rapportera ett problem eller föreslå en förbättring](https://github.com/vibloteket/bokfri/issues)
- Kontakt: [vb@viblo.se](mailto:vb@viblo.se)

Beskriv operativsystem, Bokfri-version och stegen för att återskapa problemet i
en felrapport. Utvecklingsbyggen visar även Git-commit i versionsinformationen.
Publicera aldrig bokföringsdata, personuppgifter eller andra känsliga uppgifter i
en öppen felrapport.

## Bygga från källkod

### Krav

- JDK 21 eller senare
- Apache Maven 3.6.3 eller senare

### Bygg och testa

```sh
mvn clean install
```

Det skapar bland annat den körbara allt-i-ett-JAR-filen i `target/`.
JAR-filens exakta namn följer versionen i `pom.xml`. Den aktuella versionen kan
köras från projektroten med:

```sh
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar
```

### Kommandoradsgränssnitt

Samma JAR innehåller ett headless kommandoradsgränssnitt. Utan argument startar
Bokfri GUI:t; med ett argument som `--help`, `context` eller `voucher` körs CLI:t
direkt, utan ett extra `cli`-underkommando:

```sh
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar version
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar context create demo-2026 \
  --data-dir "$HOME/.local/share/bokfri" --company-id 1 --year-id 17
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar context use demo-2026
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar company list
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar year list
```

Använd `--format json` för maskinläsbar utdata och `--config FIL` för en
isolerad config, exempelvis för CI eller en agent. Globala val kan placeras
före eller efter kommandot, till exempel både `bokfri --format json company list`
och `bokfri company list --format json`.

Företag och räkenskapsår kan skapas från JSON efter att en tillgänglig
kontoplan har valts:

```sh
bokfri account-plan list
bokfri company create --file company.json
bokfri --company-id 42 year create --file year.json
```

```json
{"name": "Exempel AB"}
```

```json
{
  "from": "2026-01-01",
  "to": "2026-12-31",
  "accountPlanId": 7
}
```

Ingående balans använder positiva belopp för debet och negativa för kredit:

```json
{"balances":[
  {"account":1930,"amount":"100000.00"},
  {"account":2081,"amount":"-100000.00"}
]}
```

```sh
bokfri opening-balance validate --file opening-balance.json
bokfri opening-balance set --dry-run --file opening-balance.json
bokfri opening-balance set --file opening-balance.json
bokfri opening-balance show
```

Utgående balans från ett tidigare år kan förhandsgranskas och föras över till
ett nytt år; endast balanskonton överförs:

```sh
bokfri opening-balance carry-forward --from-year-id 17
bokfri opening-balance carry-forward --from-year-id 17 --commit
```

Kunder kan valideras och skapas från JSON. Endast `number` och `name` är
obligatoriska; `schemaVersion` är frivilligt och tolkas som version 1 när det
utelämnas. Utdata är text som standard och `--format json` väljer maskinformat:

```json
{
  "number": "1001",
  "name": "Exempel AB",
  "email": "faktura@exempel.se"
}
```

```sh
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar customer validate --file customer.json
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar customer create --dry-run --file customer.json
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar customer create --file customer.json
```

Produkter kan valideras och skapas från JSON. Produktnummer och beskrivning är
obligatoriska; pris, momssats, enhet och konton ärvs från företagets befintliga
produktstandarder när de utelämnas:

```json
{
  "number": "CONSULTING",
  "description": "Konsultarbete",
  "sellingPrice": "1200.00",
  "vatRate": "25",
  "salesAccount": 3001
}
```

```sh
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar product validate --file product.json
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar product create --dry-run --file product.json
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar product create --file product.json
```

Kundfakturor kan på motsvarande sätt valideras, förhandsgranskas och skapas
från JSON. Kunden måste finnas och varje rad kan referera till en befintlig
produkt; produktens pris, momskod, konto och enhet ärvs när de utelämnas:

```json
{
  "customerNumber": "1001",
  "date": "2026-08-05",
  "rows": [
    {"productNumber": "CONSULTING", "quantity": 10}
  ]
}
```

```sh
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar invoice validate --file invoice.json
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar invoice create --dry-run --file invoice.json
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar invoice create --file invoice.json
```

Skapandet sparar en obokförd och oskickad faktura. En befintlig faktura kan
renderas headless till PDF utan att dess status ändras:

```sh
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar invoice pdf 42 \
  --output faktura-42.pdf
```

Befintliga filer skrivs inte över utan `--overwrite`. `--language sv-SE` är
standard och kan anges explicit.

Bokföring följer Bokfris befintliga periodbaserade fakturajournal, inte ett
separat bokföringskommando per faktura. Utan `--commit` visas bara journalen och
den komprimerade verifikationen:

```sh
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar invoice journal \
  --from 2026-08-01 --to 2026-08-31
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar invoice journal \
  --from 2026-08-01 --to 2026-08-31 --commit
```

Vid commit markeras periodens obokförda kundfakturor som bokförda,
journalnumret räknas upp och en gemensam verifikation sparas.

Leverantörer kan valideras och skapas före leverantörsfakturorna:

```json
{
  "number": "S100",
  "name": "Leverantör AB",
  "email": "faktura@leverantor.se",
  "bankgiro": "555-1234"
}
```

```sh
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar supplier validate --file supplier.json
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar supplier create --dry-run --file supplier.json
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar supplier create --file supplier.json
```

Utbetalningsnumret tilldelas automatiskt när det utelämnas. Valuta,
betalningsvillkor, leveransvillkor och kontaktperson ärvs från företaget.

Leverantörsfakturor skapas mot en befintlig leverantör och bokförs sedan med
periodens leverantörsfakturajournal:

```sh
bokfri supplier-invoice validate --file supplier-invoice.json
bokfri supplier-invoice create --dry-run --file supplier-invoice.json
bokfri supplier-invoice create --file supplier-invoice.json
bokfri supplier-invoice journal --from 2026-08-01 --to 2026-08-31
bokfri supplier-invoice journal --from 2026-08-01 --to 2026-08-31 --commit
```

Bokförda leverantörsfakturor kan betalas och journalföras med `outpayment`:

```sh
bokfri outpayment validate --file outpayment.json
bokfri outpayment create --dry-run --file outpayment.json
bokfri outpayment create --file outpayment.json
bokfri outpayment journal --from 2026-08-01 --to 2026-08-31 --commit
```

Kundinbetalningar registreras mot bokförda fakturor och journalförs på samma
sätt:

```json
{
  "date": "2026-08-20",
  "text": "Bankinbetalningar 2026-08-20",
  "rows": [{"invoiceNumber": 42, "amount": "15000.00"}]
}
```

```sh
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar inpayment create --file inpayment.json
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar inpayment journal \
  --from 2026-08-01 --to 2026-08-31
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar inpayment journal \
  --from 2026-08-01 --to 2026-08-31 --commit
```

Delbetalningar stöds; beloppet får inte överstiga fakturans återstående saldo.

Finansiella rapporter använder det valda räkenskapsåret som standard. Periodrapporter
omfattar hela året, medan balansräkning och kontosaldo använder årets sista dag:

```sh
bokfri trial-balance
bokfri income-statement
bokfri general-ledger --account 1930
bokfri balance-sheet
bokfri account balance 1930
```

Ange både `--from` och `--to` för en annan period, eller `--date` för ett annat
datum. Värdena måste ligga inom valt räkenskapsår.

Momsrapport och momsavräkning kan beräknas från periodens bokförda
verifikationer. Utan datum omfattar rapporten hela det valda räkenskapsåret;
för en delperiod måste både `--from` och `--to` anges. Avräkningen kräver alltid
en explicit period och är endast en preview tills `--commit` anges:

```sh
bokfri vat report
bokfri vat report --from 2026-07-01 --to 2026-09-30
bokfri vat settle --from 2026-07-01 --to 2026-09-30
bokfri vat settle --from 2026-07-01 --to 2026-09-30 --commit
```

Utskick är ett separat framtida kommando.

Kunder, produkter och kundfakturor kan läsas maskinellt:

```sh
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar customer list
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar customer show 1001
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar product list
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar product show CONSULTING
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar invoice list
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar invoice show 42
```

Manuella verifikationer kan valideras, förhandsgranskas och skapas från JSON:

```json
{
  "schemaVersion": 1,
  "date": "2026-08-02",
  "description": "Webbhotell",
  "rows": [
    {"account": 6540, "debit": "199.20"},
    {"account": 2641, "debit": "49.80"},
    {"account": 1930, "credit": "249.00"}
  ]
}
```

```sh
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar account list
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar voucher list
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar voucher show 42
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar voucher validate --file voucher.json
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar voucher create --dry-run --file voucher.json
java -jar target/bokfri-1.0.1-jar-with-dependencies.jar voucher create --file voucher.json
```

`validate` och `--dry-run` skriver aldrig bokföringsdata. `create` tilldelar
nästa verifikationsnummer först efter att samma validering som GUI:t har
passerat. Belopp anges som JSON-tal, helst decimalsträngar för exakt precision.

Några andra användbara kommandon:

```sh
mvn test                 # kör enhets- och integrationstester
mvn checkstyle:check     # kontrollerar kodstil
mvn spotbugs:check       # kör statisk analys
```

Plattformspaketen skapas med `jpackage` via Maven-profilerna och byggs normalt av
GitHub Actions på respektive operativsystem. Se
[`.github/workflows/ci.yml`](.github/workflows/ci.yml) för den aktuella
byggprocessen.

## Flytta data från JFS Accounting, Fribok eller äldre Bokfri

1. Skapa en fullständig säkerhetskopia i den gamla installationen.
2. Behåll originalinstallationen och säkerhetskopian tills innehållet har
   kontrollerats i Bokfri.
3. Installera och starta Bokfri.
4. Återställ säkerhetskopian och kontrollera företag, bokföringsår,
   verifikationer och rapporter.

Direkt migrering från det mycket gamla databasformatet `bookkeeper.db` från
tiden före HSQLDB stöds inte. Sådana data måste först flyttas med en historisk
JFS/Fribok-version som kan läsa formatet och därefter överföras med vanlig
säkerhetskopiering och återställning.

## Projektets bakgrund

Bokfri är en vidareutveckling av [Fribok](https://fribok.org/) och
[JFS Accounting](https://sourceforge.net/projects/jfsaccounting/). Projektet
moderniserar den befintliga Java-kodbasen. Bokfri börjar sin egen publika
versionsserie vid 1.0.0.

Planerat tekniskt arbete finns i [`MODERNIZATION.md`](MODERNIZATION.md), och
användarnära förändringar dokumenteras i [`CHANGELOG.md`](CHANGELOG.md).

Fler tankar bakom Bokfri finns i Victor ”viblo” Blomqvists inlägg i
SourceForge-tråden
[Moving to GitHub](https://sourceforge.net/p/jfsaccounting/discussion/874230/thread/8f5a9aa8/).

## Licens och erkännanden

Bokfri är fri programvara under GNU General Public License version 3. Se
[`COPYING`](COPYING) för fullständiga licensvillkor och [`CREDITS.md`](CREDITS.md)
för externa resurser och underlag.

## English Summary
Bokfri is a free, open-source desktop accounting application for Swedish
associations and small businesses. It runs locally on Windows, macOS, and Linux
and supports vouchers, invoices, Swedish BAS account plans, reports, and
SIE/BGMax import and export. The user interface may be translated, but the
accounting functionality is intended for Sweden.
