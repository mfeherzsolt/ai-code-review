---
marp: true
theme: default
paginate: true
title: AI code review – Copilot CLI-vel, CI-ban és push előtt
style: |
  section { font-size: 28px; }
  section.lead { text-align: center; }
  section h1 { color: #0b5cad; }
  table { font-size: 22px; }
  code { font-size: 0.85em; }
  .warn { color: #b42318; font-weight: bold; }
---

<!-- _class: lead -->
<!-- _paginate: false -->

# AI code review

Copilot CLI-vel, a CI-ban és push előtt lokálisan

**És amit a review-loopról tanultunk**

---

# Miről lesz szó

1. Mi ez a projekt, és mit old meg
2. Hogyan épül fel (CI és lokális mód)
3. A reviewer belülről
4. A szabályfájl: `agent.md`
5. Zajszűrés és kapuzás
6. **Demó: szándékosan elrontott kód**
7. **A review-loop: amibe mi is belefutottunk**
8. **Hogyan adjunk szabályokat körültekintően**

---

# A projekt

A repó két modult tartalmaz:

| Modul | Szerep |
|-------|--------|
| `reviewer/` | Java 21 + Maven alapú AI code reviewer |
| `demo-countdown/` | demo alkalmazás (visszaszámláló), ezen próbáljuk ki a reviewert |

- A reviewer **nem külön LLM-szolgáltatót** hív, hanem a **GitHub Copilot CLI-t** (`copilot -p`).
- A viselkedést egyetlen, emberi nyelven írt fájl adja: **`agent.md`**.
- Ugyanaz a kód fut a CI-ban és a saját gépen.

---

# Két futtatási mód

```text
        ┌──────────────── CI (pull request) ────────────────┐
PR  ──► │ review.yml → java -jar ai-reviewer.jar            │ ──► inline PR kommentek
        └───────────────────────────────────────────────────┘

        ┌──────────── Lokális (push előtt) ─────────────────┐
commit ►│ scripts/local-review.sh → LOCAL_BASE=main         │ ──► riport + push / nem push
        └───────────────────────────────────────────────────┘
```

- **CI**: minden PR-ra (`opened`, `synchronize`, `reopened`), a GitHub API-ból veszi a diffet, inline kommenteket posztol.
- **Lokális**: `git diff main...HEAD`, semmi nem megy a GitHubra, és **találat esetén nem pushol**.

---

# A reviewer belülről

```text
diff ─► prompt ─► Copilot CLI ─► JSON ─► szűrés ─► riport / PR komment
 │         ▲
 │         └── fordítási kimenet (mvn test-compile)
 └── fájlonként, "L<sorszám>| <sor>" formában
```

- `DiffUtil`: a patch soronkénti számozása, a kommentelhető (új/kontextus) sorok gyűjtése.
- `LocalDiff` / `GitHubClient`: a változások forrása.
- `CopilotClient`: a modell hívása.
- `Main`: prompt összeállítás, válasz feldolgozás, szűrés, riportmentés, kilépési kód.
- A prompt legfeljebb 60 000 karakter; ami nem fér be, kimarad (jelezve).

---

# Copilot CLI mint LLM backend

```bash
copilot -p "<szabályok + diff + fordítási kimenet>" -s --no-ask-user [--model <modell>]
```

- A meglévő **Copilot-előfizetést** használja, külön API-kulcs nélkül.
- CI-ban a `COPILOT_GITHUB_TOKEN` (fine-grained PAT, "Copilot Requests") hitelesít.
- **Eszközengedélyt nem adunk át**: a CLI nem írhat fájlt, nem futtathat parancsot, csak szöveget ad vissza.
- A prompt kéri: ne használj eszközöket, csak a bemenetet dolgozd fel.
- 5 perces időkorlát; hibás kilépési kódnál a futás hibával áll le.

---

# A szabályfájl: `agent.md`

A reviewer "programja" nem kód, hanem **prózában írt szabályok**:

| # | Szabály | Súlyosság |
|---|---------|-----------|
| 1 | Fordíthatóság | `critical` |
| 2 | Túlbonyolítás (KISS, YAGNI) | `major` |
| 3 | Elavult / hibára hajlamos idő API | `major` |
| 4 | Magic number-ek | `minor` |
| 5 | Hibakezelés | `major` |
| 6 | Tesztek | `major` |
| 7 | Olvashatóság | csak az összegzésben |
| 8 | Formázás (`HH:mm:ss`) | `minor` |

Plusz: **Üzleti döntések** (mit ne jelezzen) és **Mit NE csinálj**.

---

# Kötött kimenet

A modell kizárólag egy JSON objektumot adhat vissza:

```json
{
  "summary": "2-4 mondatos összegzés",
  "comments": [
    { "file": "...", "line": 12, "severity": "major",
      "message": "Mi a probléma és miért számít",
      "suggestion": "javasolt kód egy sorban, vagy null" }
  ]
}
```

- A `line` csak olyan sor lehet, ami a diffben szerepel.
- A `suggestion` GitHubon "suggested change" blokk lesz.
- Érvénytelen JSON esetén a hiba tartalmazza a nyers választ is a hibakereséshez.

---

# Zajszűrés a kódban

A modell nem determinisztikus, ezért a **kód is védekezik**:

- **`MIN_SEVERITY`** (alap: `minor`): az alatti kommentek nem inline kerülnek ki, hanem egy összecsukott listába.
- Ismeretlen súlyosság → `minor`; ismeretlen küszöb → **hiba** (ne kapcsolódjon ki csendben a szűrés).
- Az önmagát cáfoló kommentek ("elfogadható", "nincs teendő") **eldobásra kerülnek**; a "nem elfogadható" marad.
- A diffen kívüli sorra mutató, de küszöböt elérő komment **az összegzésben** jelenik meg.
- Legfeljebb 10 komment, a legfontosabbak elöl.

---

# Push előtti kapu: `local-review.sh`

```bash
scripts/local-review.sh [base-ág]     # alap: main
NO_PUSH=1 MIN_SEVERITY=minor scripts/local-review.sh main
```

1. Nem commitolt változás → kilép (2)
2. Reviewer építése, demo app fordítása (a hiba a promptba kerül)
3. Review a `base...HEAD` diffre
4. Riport mentése: `<modul>/target/local-review/latest.md`
5. Döntés a kilépési kód alapján

| Kód | Jelentés | Script |
|-----|----------|--------|
| 0 | nincs találat | `git push` |
| 3 | van találat | nem pushol |
| más | a reviewer hibázott | nem pushol |

---

<!-- _class: lead -->

# Demó

Szándékosan elrontott kód, és a reviewer reakciója

---

# Demó (1/4): a hiba

A működő `Countdown.java`-ból **egyetlen ellenőrzést** veszünk ki (ág: `demo/bad-countdown`, commit: `e63cd5b`):

```diff
         } catch (NumberFormatException e) {
             throw usage("A másodpercnek egész számnak kell lennie (pl. 30).");
         }
-        if (seconds <= 0) {
-            throw usage("A másodpercnek pozitívnak kell lennie: " + seconds + ".");
-        }
         if (seconds > MAX_TOTAL.getSeconds()) {
```

- A kód **lefordul**, szintaktikailag hibátlan: a fordító nem szól.
- A hiba csak a szabály ismeretében látszik: a `0` és a negatív bemenet átcsúszik a validáción.
- Szabály: **5. Hibakezelés** (`major`), a felhasználói bemenetet validálni kell.

---

# Demó (2/4): lokális review, push előtt

```bash
git commit -am "Countdown: a másodperc pozitivitás-ellenőrzésének eltávolítása"
NO_PUSH=1 scripts/local-review.sh main
```

```text
== Reviewer építése
== Demo app fordítása
== Review: main...demo/bad-countdown
Lokális review: 1 minor vagy annál súlyosabb találat.
== Találatok vannak, nem pushol.          (kilépési kód: 3)
```

A kapu működik: **a push megállt**, a riport a `demo-countdown/target/local-review/latest.md` fájlba került.

---

# Demó (3/4): amit a reviewer talált

```text
[demo-countdown/.../Countdown.java:76]
[major] A 0 és a negatív másodperc érték validálása megszűnt, így ezek a
bemenetek már nem kapnak hibaüzenetet. [...] Az ellenőrzést vissza kell tenni.
```

```suggestion
        if (seconds <= 0 || seconds > MAX_TOTAL.getSeconds()) {
```

- **Pontosan 1 találat**: a valódi hibára, a megfelelő sorra.
- Nem talált ki mellé semmit, nem volt `nit`, nem volt stíluskommentár.
- Konkrét `suggestion` is jött (GitHubon "suggested change" gomb).

---

# Demó (4/4): javítás és újabb review

A javítás: az ellenőrzés visszatétele (commit: `3f2423b`).

```bash
git commit -am "Countdown: a másodperc pozitivitás-ellenőrzésének visszaállítása"
NO_PUSH=1 scripts/local-review.sh e63cd5b     # csak a javító commit diffje
```

```text
A PR a másodperc paraméter validálását egészíti ki a nulla vagy negatív
értékek elutasításával. A változás helyes. Nincs javítandó észrevétel.
Lokális review: 0 minor vagy annál súlyosabb találat.     (kilépési kód: 0)
```

- **Nincs találat → a script pusholhat.** A kör egy lépésben lezárult: hiba, javítás, zöld.
- A `main...HEAD` diff itt üres lett volna (a javítás pontosan a `main` állapotát adja vissza),
  ezért a javítást az előző commithoz képest néztük.

---

<!-- _class: lead -->

# A review-loop

---

# Mi a review-loop?

> A review találatot jelez → javítunk → a review **újabb** találatot jelez
> → javítunk → ... és a kör nem záródik le.

- Ha a review **kapu** (nem pushol, amíg van találat), akkor a loop nem bosszantó, hanem **blokkoló**.
- Az LLM-nek nincs "kész" állapota: ha hagyjuk, **mindig talál valamit**.
- Egyetlen kör olcsó, de a körök száma nem korlátos.

---

# Amibe mi is belefutottunk (1/2): a countdown

A `demo-countdown` egy pár soros alkalmazás, mégis **17 commit** kellett a PR #1-hez.

A kód ide-oda mozgott:

```text
rendszeróra  ─►  nanoTime-alapú Clock  ─►  LongSupplier a System.nanoTime() fölött
(d3476bb)        (ba0ccb4)                 (c337235)
```

- A **2. szabály** (ne absztrahálj) és a **6. szabály** (legyen tesztelhető) egymásnak feszült.
- Minden javítás új észrevételt szült; a szabályt közben **kétszer is pontosítani kellett**
  (kivétel-lista a "NEM túlbonyolítás" alatt).
- Jöttek a `nit` kommentek is, amik végül maguk mondták: "elfogadható".

---

# Amibe mi is belefutottunk (2/2): a reviewer maga

A reviewer zajcsökkentése (PR #2) **újabb 18 commit** volt, és közben a reviewer a saját kódját is ugyanígy reviewelte.

Újra és újra felvetett dolgok, amik tudatos döntések voltak:

- a script minden futáskor újraépíti a reviewert → "gyorsítótár kellene"
- a diffen kívüli találat az összegzésben jelenik meg → "miért nem inline?"
- a CI artifact útvonala a gitignore-ozott `target`-re mutat → "hiba"

**Megoldás:** egy külön szakasz az `agent.md`-ben: **Üzleti döntések (nem hibák)**.

A kör ekkor záródott: az utolsó riport (`reviewer/target/local-review/latest.md`) már üres találatlistával jött.

---

# Miért alakul ki a loop?

| Ok | Hatás |
|----|-------|
| **Ellentmondó szabályok** | a javítás az egyik szabálynak megfelel, a másikat sérti |
| **Kivétel nélküli szabály** | a modell a tudatos döntést is hibának látja |
| **Nincs "kész" feltétel** | a modell kitölti a rendelkezésre álló helyet |
| **Stílus és ízlés jelölve** | végtelen utánfinomítás, `nit`-ek |
| **Kapuzó küszöb túl alacsony** | egy apróság is blokkolja a pusht |
| **Nem determinisztikus modell** | ugyanarra a kódra más-más észrevétel |
| **Kontextus nélküli kör** | a modell nem tudja, mit döntöttünk el az előző körben |

---

<!-- _class: lead -->

# Hogyan adjunk szabályokat körültekintően

---

# A szabályírás alapelvei (1/2)

1. **Egy szabály, egy cél, egy súlyosság.** Ne legyen két szabály, ami ugyanazt kéri ellenkező irányból.
2. **Minden tiltáshoz tartozzon kivétel-lista.**
   Ha a `Clock` paraméter lehet jogos, írd le: "NEM túlbonyolítás: ...".
3. **Mondd meg, mi a "kész".**
   "Ha a kód a szabályok szerint rendben van, a `comments` lista lehet üres."
4. **Tilts ki egész kategóriákat.**
   Nincs `nit`; stílus csak az összegzésben, nem `comments` elemként.
5. **Rögzítsd a tudatos döntéseket** (Üzleti döntések), különben minden körben újra előjönnek.

---

# A szabályírás alapelvei (2/2)

6. **Konkrétan fogalmazz**, mérhető jelekkel:
   "3+ előfordulás", "egyetlen implementáció és egyetlen hívó", "`/ 3600`, `% 60`".
7. **Írd le, mit javasoljon:** nevezze meg a felesleges elemet és a kiváltó eszközt.
8. **Korlátozd a mennyiséget:** legfeljebb 10 komment, fontossági sorrendben.
9. **A küszöböt állítsd a bizalmadhoz:** `MIN_SEVERITY=minor` alatt semmi nem blokkol.
10. **A kimenetet is védd a kódban:** szűrő az önmagát cáfoló kommentekre, validált súlyosság.

---

# Példa: rossz és jó szabály

<span class="warn">Rossz</span> (kivétel és határ nélkül):

```text
Kerüld a felesleges absztrakciót.
Legyen a kód tesztelhető.
```

**Jobb** (az `agent.md`-ből, rövidítve):

```text
Túlbonyolítás: külön osztály/interface/factory egyetlen implementációhoz és egyetlen hívóhoz.
NEM túlbonyolítás: a tesztelhetőséget szolgáló behelyettesíthető függőség
(Clock, LongSupplier időforrás), ha a teszt ténylegesen használja;
a System.nanoTime() közvetlen használata.
Egy észrevételben nevezd meg a felesleges elemet és azt, ami kiváltaná.
```

---

# Ellenőrzőlista szabálymódosítás előtt

- [ ] Ütközik-e az új szabály egy meglévővel? (Olvasd át a *teljes* fájlt.)
- [ ] Van-e a tiltáshoz kivétel, és tudja-e a modell, mi számít annak?
- [ ] Benne van-e, hogy mikor **nem** kell semmit jelezni?
- [ ] Nem lesz-e belőle `nit` vagy stíluskommentár?
- [ ] Ha a modell elakad egy kérdésen: **ez szabályhiba, nem kódhiba?**
- [ ] Lefuttattad-e a reviewert a módosított szabállyal egy ismert, jó kódon, és üres listát ad?

**Tanács:** ha a review harmadszor is ugyanazt (vagy az ellenkezőjét) kéri, ne a kódot javítsd, hanem az `agent.md`-t.

---

# Tanulságok

- Az AI review **szabályfüggő**: a szabályfájl minősége többet számít, mint a modellé.
- A szabályok **kódként viselkednek**: ütközhetnek, lehet hibájuk, érdemes verziózni és "tesztelni".
- A kapu (push blokkolása) csak **jól beállított küszöbbel és kilépési feltétellel** működik.
- A kimenet védelme (szűrés, validálás) ugyanolyan fontos, mint a prompt.
- A loop jele: a javítások és a szabálypontosítások száma nő, a kód nem javul.

---

<!-- _class: lead -->
<!-- _paginate: false -->

# Köszönöm!

Kérdések?

`README.md` · `agent.md` · `scripts/local-review.sh` · `reviewer/`
