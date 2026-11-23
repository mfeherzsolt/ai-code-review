# AI code review

Ez a repó egy AI alapú code reviewert (`reviewer/`) és egy demo alkalmazást (`demo-countdown/`) tartalmaz.
A reviewer a Copilot CLI-t használja, a szabályai az [agent.md](agent.md) fájlban vannak.

A review kétféleképpen fut:

- **CI-ban**: a `.github/workflows/review.yml` minden pull requestre lefuttatja, és inline kommentekként posztolja.
- **Lokálisan**: a `scripts/local-review.sh` push előtt, a saját gépen, a GitHub használata nélkül.

A projekt bemutatója: [Markdown (Marp)](docs/presentation.md), [HTML](docs/presentation.html).

## scripts/local-review.sh

Push előtti ellenőrzés: lefuttatja ugyanazt a reviewert a commitolt változásokra, és csak akkor pushol, ha nincs találat.

```bash
scripts/local-review.sh [base-ág]    # alap: main
```

### Előfeltételek

- Java 21 és Maven (`mvn`).
- A Copilot CLI (`copilot`), bejelentkezve. Ha nincs a `PATH`-on, a `COPILOT_CMD` változóval adható meg.

### Lépések

1. **Ellenőrzés**: ha van nem commitolt, követett fájlváltozás, a script a 2-es kóddal kilép, mert a review a commitokat nézi.
2. **Reviewer építése**: `mvn package -DskipTests` a `reviewer/` modulon, így mindig a friss kód fut.
3. **Demo app fordítása**: `mvn test-compile` a `demo-countdown/` modulon. A kimenet bekerül a promptba, mint a CI-ban.
   Ha a fordítás elbukik, a script nem áll le: a hiba a fordítási kimenetbe kerül, és a modell `critical` találatként jelzi.
4. **Review**: a reviewer a `git diff <base>...HEAD` kimenetét kapja, fájlonként feldarabolva, és csak a változott sorokat
   nézi. A törölt fájlok kimaradnak.
5. **Riport mentése**: lásd lent.
6. **Döntés** a reviewer kilépési kódja alapján:

| Kód | Jelentés | A script viselkedése |
|-----|----------|----------------------|
| 0 | nincs találat | pusholja az ágat (`git push origin <ág>`) |
| 3 | van `MIN_SEVERITY`-t elérő találat | nem pushol, 3-mal kilép |
| más | a reviewer hibával leállt | nem pushol, 1-gyel kilép |

Az 1-es kód a JVM kezeletlen kivételének kódja, ezért a találat külön kódot (3) kapott (`Main.EXIT_FINDINGS`).

### Környezeti változók

| Változó | Hatás |
|---------|-------|
| `NO_PUSH=1` | csak review, a script nem pushol |
| `MIN_SEVERITY` | a találatok küszöbe: `nit`, `minor`, `major` vagy `critical` (alap: `minor`) |
| `COPILOT_CMD` | a `copilot` CLI útvonala |
| `COPILOT_MODEL` | a használt modell |
| `RULES_FILE` | a szabályfájl (alap: `agent.md`) |

Példa push nélküli futtatásra:

```bash
NO_PUSH=1 MIN_SEVERITY=minor scripts/local-review.sh main
```

### Riportok

A riport modulonként készül, minden olyan modulhoz, amelyben fájl változott:

- `<modul>/target/local-review/latest.md`, például `reviewer/target/local-review/latest.md`
- a modulba nem tartozó fájlok (`agent.md`, `scripts/`, `.github/`) riportja: `target/local-review/latest.md`

Modul az a legfelső könyvtár, amelyben van `pom.xml`. A riport az összegzést és az adott modul inline kommentjeit
tartalmazza. A mentés csak kiegészítő: ha nem sikerül, a script figyelmeztet, de a review lefut.

A CI ugyanezt a `<modul>/target/review/latest.md` helyre menti, és `review-report` artifactként tölti fel.
A `target/` gitignore-ozott.

### Találatok és küszöb

- A `MIN_SEVERITY` alatti kommentek nem találatok: egy összecsukott listába kerülnek az összegzésben.
- A küszöböt elérő, de a diffen kívüli sorra mutató komment is találat, az összegzésben jelenik meg.
- Az ismeretlen súlyosságú komment `minor`-nak számít.
- A "nincs teendő" jellegű, önmagát cáfoló kommenteket a reviewer eldobja.
- Az `agent.md` "Üzleti döntések" szakasza felsorolja, mit ne jelezzen hibának a reviewer.

## Tesztek

```bash
mvn -f reviewer/pom.xml test
mvn -f demo-countdown/pom.xml test
```
