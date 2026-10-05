package ci.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PrTitleValidatorTest {

    private final PrTitleValidator validator = new PrTitleValidator();

    @ParameterizedTest
    @ValueSource(strings = {
            "CMS-1234: Dodat layout za page builder",
            "CMS-1234 - Dodat layout",
            "CMS-1234-Dodat layout",
            "CMS-1234:Dodat layout",
            "CMS-1",
            "CMS-1234",
            "CMS-99999 : Popravka",
    })
    void ispravanNaslovProlazi(String title) {
        ValidationResult result = validator.validate(title);

        assertTrue(result.isValid());
        assertTrue(result.errors().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Dodat layout za page builder",   // nema Jira ključa
            "cms-1234: mala slova",           // prefiks mora velikim slovima
            "CMS1234: bez crtice",
            "CMS-: bez broja",
            "CMS-12a4: slovo u broju",
            "CMS-1234 Dodat layout",          // opis bez ':' ili '-'
            "CMS-1234:",                      // separator bez opisa
            "JIRA-1234: pogrešan projekat",
            "[CMS-1234] Dodat layout",
            " CMS-1234: razmak na početku",
            "Fix: CMS-1234",                  // ključ nije na početku
            "CMS-١٢٣: arapske cifre",         // \d prihvata samo 0-9
    })
    void neispravanNaslovPada(String title) {
        ValidationResult result = validator.validate(title);

        assertFalse(result.isValid());
        assertEquals(1, result.errors().size());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void prazanNaslovPada(String title) {
        ValidationResult result = validator.validate(title);

        assertFalse(result.isValid());
        assertTrue(result.errors().get(0).contains("prazan"));
    }

    @Test
    void porukaOGresciPrikazujeOcekivaniFormat() { // TI-4
        String error = validator.validate("Neki naslov").errors().get(0);

        assertTrue(error.contains("CMS-<broj>"));
        assertTrue(error.contains("CMS-1234: Dodat layout za page builder"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "CMS-1234: Dodat layout | CMS-1234",
            "CMS-7                  | CMS-7",
            "CMS-42 - Opis          | CMS-42",
    })
    void extractJiraKeyVracaKljuc(String title, String expectedKey) {
        assertEquals(expectedKey, validator.extractJiraKey(title));
    }

    @Test
    void extractJiraKeyZaNeispravanNaslovVracaNull() {
        assertNull(validator.extractJiraKey("Bez ključa"));
        assertNull(validator.extractJiraKey(null));
    }
}
