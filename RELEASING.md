# Skapa en Bokfri-release

Bokfri använder semantisk versionering. En tagg med formen `vX.Y.Z` startar
GitHub Actions, som testar och bygger paketen för Windows, macOS och Linux,
skapar kontrollsummor och publicerar en GitHub Release.

## 1. Förbered releasen

1. Kontrollera att `main` är uppdaterad och ren:
   ```sh
   git switch main
   git pull --no-rebase origin main
   git status
   ```
2. Välj version utifrån `CHANGELOG.md`. Bakåtkompatibla rättningar ger normalt
   en patchrelease, exempelvis `1.0.0` → `1.0.1`.
3. Gå igenom alla commits sedan föregående tagg och komplettera
   `CHANGELOG.md`:
   ```sh
   git log --reverse vFÖRRA_VERSION..HEAD
   git diff --stat vFÖRRA_VERSION..HEAD
   ```
4. Flytta innehållet under `Unreleased` till en daterad versionsrubrik och
   lämna en ny, tom `Unreleased`-sektion överst.
5. Skriv om `RELEASE_NOTES.md` för den nya versionen. Ta med användarnära
   nyheter, viktiga rättningar, uppgraderingsråd och kända begränsningar.
6. Ta bort `-SNAPSHOT` från versionen i `pom.xml` och uppdatera fasta
   versionshänvisningar i `README.md` och `website/download/index.html`.
7. Sök efter gamla eller inkonsekventa versionsnummer:
   ```sh
   grep -RInE 'FÖRRA_VERSION|NY_VERSION|SNAPSHOT' \
     --include='*.md' --include='*.xml' --include='*.html' \
     --include='*.yml' --include='*.yaml' .
   ```
8. Kör verifieringen:
   ```sh
   mvn clean install
   mvn checkstyle:check
   mvn spotbugs:check
   ```
9. Granska diffen och skapa release-commiten:
   ```sh
   git diff --check
   git diff
   git add CHANGELOG.md RELEASE_NOTES.md RELEASING.md pom.xml README.md \
     website/download/index.html
   git commit -m "Prepare Bokfri NY_VERSION release"
   ```

## 2. Publicera

Publicera inte en release förrän release-commiten och testerna är granskade.
Taggen måste matcha Maven-versionen exakt och får inte peka på en
`-SNAPSHOT`-version.

1. Skapa en signerad eller annoterad tagg lokalt:
   ```sh
   git tag -a vNY_VERSION -m "Bokfri NY_VERSION"
   ```
2. Pusha commit och tagg atomiskt, så att webbplatsen inte annonserar versionen
   innan releasebygget kan starta:
   ```sh
   git push --atomic origin main vNY_VERSION
   ```
3. Följ workflowet **Java CI** på GitHub. Det ska:
   - köra testerna på Ubuntu, Windows och macOS,
   - bygga `Bokfri-x86_64.AppImage`, `Bokfri.msi` och `Bokfri.dmg`,
   - kontrollera att alla paket finns och skapa `SHA256SUMS`,
   - skapa releasen från `RELEASE_NOTES.md`.
4. Kontrollera den publicerade GitHub-releasen, dess fyra filer och att
   nedladdningsknapparna på `https://bokfri.viblo.se/download/` fungerar.
5. Installera och starta minst de paket som det finns tillgängliga testmiljöer
   för. Kontrollera särskilt start, versionsvisning och att en säkerhetskopia
   kan öppnas utan att originaldata skrivs över.

## 3. Starta nästa utvecklingsversion

Efter att releasen är verifierad:

1. ändra `pom.xml` till nästa planerade `X.Y.Z-SNAPSHOT`,
2. uppdatera JAR-exemplet i `README.md`,
3. behåll en tom `Unreleased`-sektion i `CHANGELOG.md`,
4. skapa och pusha en separat commit, exempelvis:
   ```sh
   git commit -am "Start 1.0.2 development"
   git push origin main
   ```

Om release-workflowet misslyckas ska taggen inte flyttas eller återanvändas efter
att den blivit publik. Rätta felet och skapa en ny patchversion i stället.
