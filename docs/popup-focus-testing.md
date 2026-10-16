# Testa kontolistor och fokus i Bokfri Dev

Denna testversion ändrar den gemensamma sökbara listan som används för bland annat konton. Förslagen ska inte ta tangentbordsfokus från inmatningsfältet. Musval, scrollning och knappar ska fortfarande fungera. Konton väljs med Enter eller dubbelklick, som tidigare.

## Säker testning

- Använd Bokfri Dev, inte ersättning av den vanliga Bokfri-installationen.
- Ta säkerhetskopia innan testet. Använd helst ett testföretag eller en separat kopia av bokföringen; du behöver inte spara någon verifikation för att testa fokus.
- Stäng alla Bokfri Dev-fönster innan varje jämförelse. Kör inte två instanser mot samma bokföring.
- Dev-programmet har eget appnamn och egen programinställningskatalog, men detta är inte en garanti för att vald bokföringsdata är en separat kopia.

## Mac: starta med diagnostik

Installera `Bokfri Dev.app` från test-DMG:n i `/Applications`. Öppna Terminal och kör:

```sh
open -n "/Applications/Bokfri Dev.app" --args --popup-focus-diagnostics
```

1. Öppna **Ny verifikation** och klicka i **Konto**, precis som när problemet uppstår.
2. Skriv ett helt kontonummer utan att klicka igen efter första siffran.
3. Kontrollera att listan filtreras och att alla siffror hamnar i fältet.
4. Testa pil upp/ned och Enter för att välja konto.
5. Testa att markera en rad med musen och välja med dubbelklick.
6. Testa att scrolla i listan och sedan fortsätta skriva.
7. Testa Escape, Tab och att klicka utanför listan.
8. Prova ett fristående listfält med nedåtpil: öppna med knappen och välj med mus respektive tangentbord.
9. Prova även varianten med extra redigeringsknapp: öppna menyn för Lägg till/Ändra/Ta bort. Testa ändringar enbart på testdata.

Notera exakt vilket fält som krånglar och vid vilket steg det händer. Spara ingen riktig verifikation bara för testets skull.

## Jämför med gamla beteendet

Avsluta Bokfri Dev helt och kör sedan:

```sh
open -n "/Applications/Bokfri Dev.app" --args --popup-focus-diagnostics --legacy-popup-focus
```

Upprepa samma inmatning. Det här läget använder den tidigare popuphanteringen. Båda lägena finns i samma testversion, så Java-version och övrig kod hålls lika. Flaggorna gäller endast den startade processen.

## Skicka tillbaka resultat

Loggarna ligger här (Finder → Gå → Gå till mapp):

```text
~/.bokfri/diagnostics/
```

Skicka `popup-focus-*.log` från båda körningarna och ange:

- vilken Bokfri Dev-byggnad som användes,
- om gammalt läge återskapade problemet,
- om nya läget löste det,
- eventuella problem med musval, scrollning eller tangenter,
- gärna en kort skärminspelning med bara testdata.

Loggarna innehåller macOS-/Java-version, Look & Feel samt komponenttyper och tidpunkter för fokusbyten, popupöppning och avslutad cellredigering. De loggar inte tangentvärden, fältinnehåll, kontonummer, företagsnamn eller bokföringstext. Loggning är avstängd vid vanlig start.

## Linux och Windows

Samma manuella testlista gäller. Starta GUI-programmet med `--popup-focus-diagnostics`, och lägg till `--legacy-popup-focus` för jämförelsen. Alternativt kan miljövariablerna `BOKFRI_POPUP_DIAGNOSTICS=1` och `BOKFRI_POPUP_LEGACY=1` användas. JVM-egenskaperna `bokfri.popup.diagnostics=true` och `bokfri.popup.legacy=true` är också tillgängliga.

## Automatiserade tester

Vanliga Maven-tester körs headless; Robot-testerna hoppas då över. Kör GUI-tester på ett riktigt skrivbord med:

```sh
mvn test -Dtest=SSTableComboBoxGuiTest -Dawt.headless=false
```

På Linux utan skärm:

```sh
xvfb-run -a mvn test -Dtest=SSTableComboBoxGuiTest -Dawt.headless=false
```

Workflow **Popup focus GUI regression** kör testerna på Linux, Windows och macOS med programmets vanliga plattforms-Look & Feel. Den kör också ett separat diagnostiskt skrivtest med gammalt beteende; det testets fel blockerar inte fixens tester. Artefakterna innehåller fokusloggar, testresultat och skärmbilder med enbart syntetiska testdata.

Ett grönt CI-test utesluter inte problemet på användarens dator. Om Robot saknar rättighet eller skrivbordssession på en runner ska det behandlas som ett testmiljöproblem, inte som bevis för eller emot fixen.

Workflow **Java CI** kan startas manuellt på testgrenen för att skapa installerbara paket. Alla icke-releasebyggen använder Dev-identitet; endast `v*`-taggar använder releaseidentiteten.
