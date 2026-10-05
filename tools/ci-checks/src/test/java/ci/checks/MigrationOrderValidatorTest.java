package ci.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MigrationOrderValidatorTest {

    private static final String DIR = "src/main/resources/db/migration";

    private static final List<String> DEV_FILES = List.of(
            "README.md",
            "src/main/java/rs/cms/PageService.java",
            DIR + "/V20240101090000__initial.sql",
            DIR + "/V20240310120000__add_pages.sql",
            DIR + "/R__refresh_views.sql");

    private final MigrationOrderValidator validator = new MigrationOrderValidator();

    private static FileChange added(String path) {
        return new FileChange('A', path);
    }

    private static FileChange modified(String path) {
        return new FileChange('M', path);
    }

    private static FileChange deleted(String path) {
        return new FileChange('D', path);
    }

    private static FileChange renamed(String from, String to) {
        return new FileChange('R', to, from);
    }

    private static String single(ValidationResult result) {
        assertEquals(1, result.errors().size(), () -> "Očekivana jedna greška, dobijeno: " + result.errors());
        return result.errors().get(0);
    }

    // ---------- MG1: nove migracije moraju biti novije od najnovije na dev ----------

    @Test
    void novaMigracijaNovijaOdDevProlazi() {
        ValidationResult result = validator.validate(DEV_FILES, List.of(
                added(DIR + "/V20240501080000__add_layout.sql"),
                modified(DIR + "/R__refresh_views.sql"),        // repeatable se sme menjati
                modified("src/main/java/rs/cms/PageService.java")));

        assertTrue(result.isValid(), () -> String.join("\n", result.errors()));
    }

    @Test
    void novaMigracijaStarijaOdNajnovijeNaDevPada() {
        // Npr. migracija napravljena pre nego što je add_pages merge-ovan na dev.
        String error = single(validator.validate(DEV_FILES, List.of(
                added(DIR + "/V20240201000000__add_layout.sql"))));

        assertTrue(error.startsWith("[MG1]"));
        assertTrue(error.contains("V20240201000000__add_layout.sql"));
        assertTrue(error.contains("V20240310120000__add_pages.sql"));
    }

    @Test
    void novaMigracijaSaIstomVerzijomKaoNajnovijaPada() {
        String error = single(validator.validate(DEV_FILES, List.of(
                added(DIR + "/V20240310120000__other.sql"))));

        assertTrue(error.startsWith("[MG1]"));
    }

    @Test
    void viseNovihMigracijaPadaSamoStarija() {
        String error = single(validator.validate(DEV_FILES, List.of(
                added(DIR + "/V20240501080000__good.sql"),
                added(DIR + "/V20240105000000__bad.sql"))));

        assertTrue(error.contains("bad"));
    }

    @Test
    void prvaMigracijaKadaDevNemaMigracijaProlazi() {
        assertTrue(validator.validate(List.of("README.md"), List.of(
                added(DIR + "/V1__initial.sql"))).isValid());
    }

    @Test
    void verzijeSePoredeNumerickiPoDelovima() {
        List<String> dev = List.of(DIR + "/V1_9__a.sql");

        // 1.10 > 1.9 (tekstualno bi bilo obrnuto)
        assertTrue(validator.validate(dev, List.of(added(DIR + "/V1_10__b.sql"))).isValid());
        assertTrue(validator.validate(dev, List.of(added(DIR + "/V2__b.sql"))).isValid());
        assertFalse(validator.validate(dev, List.of(added(DIR + "/V1_2__b.sql"))).isValid());
        assertFalse(validator.validate(dev, List.of(added(DIR + "/V1.9.0__b.sql"))).isValid()); // 1.9.0 == 1.9
    }

    @Test
    void migracijeUPodfolderimaCineJedanNiz() {
        // Flyway skenira lokaciju rekurzivno; sve migracije su jedan niz verzija.
        List<String> dev = List.of(DIR + "/2024/V20240310120000__add_pages.sql");

        String error = single(validator.validate(dev, List.of(
                added(DIR + "/2023/V20230101000000__late.sql"))));

        assertTrue(error.startsWith("[MG1]"));
    }

    @Test
    void javaMigracijeSeTakodjeProveravaju() {
        String error = single(validator.validate(DEV_FILES, List.of(
                added("src/main/java/db/migration/V20240102000000__Backfill.java"))));

        assertTrue(error.startsWith("[MG1]"));
    }

    @Test
    void fajlVanMigrationFolderaSeIgnorise() {
        assertTrue(validator.validate(DEV_FILES, List.of(
                added("scripts/V1__seed.sql"))).isValid());
    }

    @Test
    void podesivFolderMigracija() {
        MigrationOrderValidator custom = new MigrationOrderValidator("sql/migrations");

        assertFalse(custom.validate(
                List.of("app/sql/migrations/V5__x.sql"),
                List.of(added("app/sql/migrations/V4__y.sql"))).isValid());
    }

    // ---------- MG2: postojeće migracije se ne smeju menjati ni brisati ----------

    @Test
    void izmenaPostojeceMigracijePada() {
        String error = single(validator.validate(DEV_FILES, List.of(
                modified(DIR + "/V20240101090000__initial.sql"))));

        assertTrue(error.startsWith("[MG2]"));
        assertTrue(error.contains("menja"));
    }

    @Test
    void brisanjePostojeceMigracijePada() {
        String error = single(validator.validate(DEV_FILES, List.of(
                deleted(DIR + "/V20240310120000__add_pages.sql"))));

        assertTrue(error.startsWith("[MG2]"));
        assertTrue(error.contains("briše"));
    }

    @Test
    void preimenovanjePostojeceMigracijePada() {
        ValidationResult result = validator.validate(DEV_FILES, List.of(
                renamed(DIR + "/V20240310120000__add_pages.sql", DIR + "/V20240310120000__add_pages_v2.sql")));

        assertFalse(result.isValid());
        assertTrue(result.errors().stream().anyMatch(e -> e.startsWith("[MG2]") && e.contains("preimenuje")));
    }

    @Test
    void istovremenoMg1IMg2PrijavljujeObeGreske() {
        ValidationResult result = validator.validate(DEV_FILES, List.of(
                modified(DIR + "/V20240310120000__add_pages.sql"),
                added(DIR + "/V20240102000000__late.sql")));

        assertEquals(2, result.errors().size());
        assertTrue(result.errors().stream().anyMatch(e -> e.startsWith("[MG1]")));
        assertTrue(result.errors().stream().anyMatch(e -> e.startsWith("[MG2]")));
    }

    @Test
    void prBezMigracijaProlazi() {
        assertTrue(validator.validate(DEV_FILES, List.of(modified("README.md"))).isValid());
    }

    // ---------- Parsiranje izlaza "git diff --name-status" ----------

    @Test
    void parseNameStatusParsiraSveTipoveIzmena() {
        String output = "A\tsrc/a.sql\n"
                + "M\tsrc/b.sql\r\n"
                + "D\tsrc/c.sql\n"
                + "R087\tsrc/old.sql\tsrc/new.sql\n"
                + "\n";

        assertEquals(List.of(
                new FileChange('A', "src/a.sql"),
                new FileChange('M', "src/b.sql"),
                new FileChange('D', "src/c.sql"),
                new FileChange('R', "src/new.sql", "src/old.sql")),
                MigrationOrderValidator.parseNameStatus(output));
    }

    @Test
    void parseNameStatusPrazanIzlazVracaPraznuListu() {
        assertTrue(MigrationOrderValidator.parseNameStatus("").isEmpty());
    }

    @Test
    void compareVersionsDopunjujeNulama() {
        assertEquals(0, MigrationOrderValidator.compareVersions(v(1), v(1, 0, 0)));
        assertTrue(MigrationOrderValidator.compareVersions(v(1, 10), v(1, 9)) > 0);
    }

    private static List<BigInteger> v(long... parts) {
        List<BigInteger> list = new ArrayList<>();
        for (long p : parts) {
            list.add(BigInteger.valueOf(p));
        }
        return list;
    }
}
