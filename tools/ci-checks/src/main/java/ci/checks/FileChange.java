package ci.checks;

/**
 * Jedna izmena fajla iz "git diff --name-status".
 *
 * @param status  A (dodat), M (izmenjen), D (obrisan), R (preimenovan), C (kopiran), T (promena tipa)
 * @param path    putanja fajla (za R/C nova putanja)
 * @param oldPath stara putanja za R/C, inače null
 */
public record FileChange(char status, String path, String oldPath) {

    public FileChange(char status, String path) {
        this(status, path, null);
    }
}
