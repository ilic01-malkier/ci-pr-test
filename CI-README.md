# CI provere za PR-ove ka `dev` grani (Java, GitHub Actions)

## Struktura

```
.github/workflows/pr-dev.yml                      # pipeline (GitHub Actions)
tools/ci-checks/
  pom.xml                                         # samo za testove alata (JUnit 5)
  src/main/java/ci/checks/
    PrTitleValidator.java                         # klasa 1: format naslova PR-a (TI-1, TI-2, TI-4)
    MigrationOrderValidator.java                  # klasa 2: redosled Flyway migracija (MG1, MG2)
    ValidationResult.java, FileChange.java        # pomoćni record-i
    Main.java                                     # CLI koji pipeline poziva
  src/test/java/ci/checks/                        # JUnit 5 testovi za obe klase
```

Pretpostavke: Maven, Java 17+ i Flyway migracije u `src/main/resources/db/migration`
(npr. `V20240115103000__add_page_layout.sql`).

| Zahtev | Gde je rešen |
|---|---|
| TR-1..TR-5 | `on.pull_request` u workflow-u |
| TR-6 | `concurrency` + `cancel-in-progress` |
| TI-1, TI-2, TI-4 | `PrTitleValidator` |
| TI-3 (opciono) | korak "1b" (curl na Jira REST API) |
| UP-1..UP-3 | korak "2" (`git merge-base --is-ancestor`) |
| MG1, MG2 | `MigrationOrderValidator` |
| MG3 | ne primenjuje se (odnosi se samo na EF Core) |
| MG4 (opciono) | korak "3c" + `services: postgres` (zakomentarisano) |
| BT-1..BT-4 | koraci "4", "5" i objava rezultata |
| MR1..MR4 | podešavanja na GitHub-u (ispod) |

Kako `MigrationOrderValidator` radi:
- verzije poredi kao Flyway, deo po deo numerički (`V1_10` je novija od `V1_9`);
- proverava `.sql` i Java (`.java`) migracije, i u podfolderima `db/migration`;
- `R__` (repeatable) migracije ignoriše, jer one smeju da se menjaju.

## Podešavanje

1. Kopirati `.github/` i `tools/` u root repozitorijuma.
2. U `pr-dev.yml` proveriti `JAVA_VERSION` i `MIGRATIONS_FOLDER`.
3. Ako koristite Maven Wrapper, zameniti `mvn` sa `./mvnw`. Za Gradle: `./gradlew build -x test` i `./gradlew test`,
   a putanje izveštaja su `**/build/test-results/test/TEST-*.xml`.
4. (Opciono, TI-3) Settings → Secrets and variables → Actions:
   variable `JIRA_BASE_URL` (npr. `https://firma.atlassian.net`), secrets `JIRA_EMAIL` i `JIRA_API_TOKEN`.
5. (Opciono, MG4) odkomentarisati `services:` i korak "3c" i prilagoditi bazu.

## Pravila za merge (sekcija 4)

Settings → Branches → Add branch protection rule za `dev`:

- **Require a pull request before merging** (MR1); opciono *Require approvals: 1* (MR4)
- **Require status checks to pass** → izabrati `PR checks` i `Rezultati testova` (MR2)
- **Require branches to be up to date before merging** (MR3)
- **Do not allow bypassing the above settings**, da ni administratori ne mogu da push-uju direktno

Status check se pojavljuje u listi tek nakon što se pipeline izvrši bar jednom.

## Testiranje

Unit testovi alata:

```
mvn -f tools/ci-checks/pom.xml test
```

Ručno, lokalno (iz root-a repozitorijuma):

```
javac -encoding UTF-8 -d .ci-tools $(find tools/ci-checks/src/main/java -name '*.java')
java -cp .ci-tools ci.checks.Main title "CMS-1234: Opis"
git fetch origin && java -cp .ci-tools ci.checks.Main migrations --base origin/dev
```

Testiranje celog pipeline-a: otvoriti probne PR-ove ka `dev`:

| Scenario | Očekivano |
|---|---|
| Naslov `Popravka buga` | pada na koraku 1, poruka prikazuje format |
| Promena naslova u `CMS-1: Popravka` | pipeline se sam ponovo pokreće (TR-3) i prolazi korak 1 |
| Grana napravljena pre poslednjeg commita na `dev` | pada na koraku 2, poruka predlaže rebase |
| Migracija sa verzijom manjom od poslednje na `dev` | pada na koraku 3 sa `[MG1]` |
| Izmena postojeće migracije | pada na koraku 3 sa `[MG2]` |
| Namerno pokvaren test | pada na koraku 5, izveštaj vidljiv na PR-u |
| Dva brza push-a zaredom | prvo izvršavanje se otkazuje (TR-6) |
| PR ka nekoj drugoj grani | pipeline se ne pokreće (TR-5) |
