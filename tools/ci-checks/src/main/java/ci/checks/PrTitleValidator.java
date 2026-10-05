package ci.checks;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provera formata naslova PR-a (zahtevi TI-1, TI-2, TI-4).
 * Naslov mora počinjati Jira ključem CMS-&lt;broj&gt;, opciono praćenim
 * opisom posle ':' ili '-', npr. "CMS-1234: Dodat layout za page builder".
 */
public final class PrTitleValidator {

    /** Regex iz zahteva TI-2. U Javi \d podrazumevano znači samo 0-9. */
    public static final String PATTERN = "^CMS-(\\d+)(\\s*[:\\-]\\s*.+)?$";

    public static final String EXPECTED_FORMAT =
            "CMS-<broj>[: opis], npr. \"CMS-1234: Dodat layout za page builder\"";

    private static final Pattern TITLE_PATTERN = Pattern.compile(PATTERN);

    public ValidationResult validate(String title) {
        if (title == null || title.isBlank()) {
            return ValidationResult.fail("Naslov PR-a je prazan. Očekivani format: " + EXPECTED_FORMAT);
        }

        if (!TITLE_PATTERN.matcher(title).matches()) {
            return ValidationResult.fail(
                    "Naslov PR-a \"" + title + "\" nije u ispravnom formatu. Očekivani format: " + EXPECTED_FORMAT);
        }

        return ValidationResult.ok();
    }

    /** Vraća Jira ključ (npr. "CMS-1234") ako je naslov ispravan, inače null. */
    public String extractJiraKey(String title) {
        if (title == null) {
            return null;
        }
        Matcher matcher = TITLE_PATTERN.matcher(title);
        return matcher.matches() ? "CMS-" + matcher.group(1) : null;
    }
}
