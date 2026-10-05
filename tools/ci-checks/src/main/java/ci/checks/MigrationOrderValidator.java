package ci.checks;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provera redosleda migracija (zahtevi MG1 i MG2) za Flyway.
 *
 * <p>Klasa ne poziva git direktno: dobija listu fajlova sa dev grane i listu
 * izmena iz PR-a, pa se lako testira bez repozitorijuma.
 *
 * <p>Migracija je versioned Flyway fajl u folderu "db/migration" (ili podfolderu),
 * npr. {@code V20240115103000__add_page_layout.sql} ili {@code V3_1__fix.sql}.
 * Repeatable ({@code R__...}) i undo ({@code U...}) migracije se ignorišu.
 * Flyway sve lokacije tretira kao jedan niz verzija, pa se poredi sa najnovijom
 * migracijom bilo gde u repozitorijumu.
 */
public final class MigrationOrderValidator {

    private static final Pattern MIGRATION_FILE =
            Pattern.compile("^V(?<version>\\d+(?:[._]\\d+)*)__(?<desc>.+)\\.(?:sql|java)$");

    private final String migrationsFolder;

    public MigrationOrderValidator() {
        this("db/migration");
    }

    public MigrationOrderValidator(String migrationsFolder) {
        this.migrationsFolder = trimSlashes(migrationsFolder.replace('\\', '/'));
    }

    /**
     * @param devFiles  svi fajlovi na dev grani (git ls-tree -r --name-only origin/dev)
     * @param prChanges izmene u PR-u (git diff --name-status origin/dev...HEAD)
     */
    public ValidationResult validate(Collection<String> devFiles, Collection<FileChange> prChanges) {
        List<Migration> devMigrations = new ArrayList<>();
        for (String file : devFiles) {
            parse(file).ifPresent(devMigrations::add);
        }

        Set<String> devPaths = new HashSet<>();
        devMigrations.forEach(m -> devPaths.add(m.path()));

        Optional<Migration> latestDev = devMigrations.stream()
                .max(Comparator.comparing(Migration::versionParts, MigrationOrderValidator::compareVersions));

        // TreeSet: deterministički redosled poruka i bez duplikata.
        Set<String> errors = new TreeSet<>();

        for (FileChange change : prChanges) {
            // MG2: postojeće migracije sa dev grane ne smeju se menjati, brisati ni preimenovati.
            String touchedExisting = switch (change.status()) {
                case 'M', 'D', 'T' -> change.path();
                case 'R' -> change.oldPath();
                default -> null;
            };

            if (touchedExisting != null && devPaths.contains(normalize(touchedExisting))) {
                String action = switch (change.status()) {
                    case 'D' -> "briše";
                    case 'R' -> "preimenuje";
                    default -> "menja";
                };
                errors.add("[MG2] PR " + action + " migraciju koja već postoji na dev grani: " + touchedExisting
                        + ". Postojeće migracije se ne smeju menjati; dodajte novu migraciju.");
            }

            // MG1: svaka nova migracija mora biti novija od najnovije na dev grani.
            boolean isAddition = change.status() == 'A' || change.status() == 'R' || change.status() == 'C';
            if (!isAddition || devPaths.contains(normalize(change.path())) || latestDev.isEmpty()) {
                continue;
            }

            Optional<Migration> added = parse(change.path());
            if (added.isPresent()
                    && compareVersions(added.get().versionParts(), latestDev.get().versionParts()) <= 0) {
                errors.add("[MG1] Migracija " + added.get().fileName() + " nije novija od najnovije migracije na dev"
                        + " grani (" + latestDev.get().fileName() + "). Preimenujte je u verziju veću od V"
                        + latestDev.get().version() + " posle rebase-a na dev.");
            }
        }

        return ValidationResult.fromErrors(new ArrayList<>(errors));
    }

    /** Parsira izlaz komande "git diff --name-status" (polja odvojena tabom). */
    public static List<FileChange> parseNameStatus(String gitOutput) {
        List<FileChange> result = new ArrayList<>();

        for (String rawLine : gitOutput.split("\n")) {
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            if (line.isEmpty()) {
                continue;
            }

            String[] parts = line.split("\t");
            char status = parts[0].charAt(0); // "R100" -> 'R'

            if ((status == 'R' || status == 'C') && parts.length >= 3) {
                result.add(new FileChange(status, parts[2], parts[1]));
            } else if (parts.length >= 2) {
                result.add(new FileChange(status, parts[1]));
            }
        }

        return result;
    }

    /**
     * Poredi verzije kao Flyway: deo po deo numerički, deo koji nedostaje = 0
     * (1.10 &gt; 1.9, 1.0 == 1). BigInteger: nema prekoračenja za duge timestamp-ove.
     */
    static int compareVersions(List<BigInteger> a, List<BigInteger> b) {
        int length = Math.max(a.size(), b.size());
        for (int i = 0; i < length; i++) {
            BigInteger x = i < a.size() ? a.get(i) : BigInteger.ZERO;
            BigInteger y = i < b.size() ? b.get(i) : BigInteger.ZERO;
            int cmp = x.compareTo(y);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private Optional<Migration> parse(String path) {
        String normalized = normalize(path);
        int lastSlash = normalized.lastIndexOf('/');
        String dir = lastSlash >= 0 ? normalized.substring(0, lastSlash) : "";
        String fileName = normalized.substring(lastSlash + 1);

        if (!("/" + dir + "/").contains("/" + migrationsFolder + "/")) {
            return Optional.empty();
        }

        Matcher matcher = MIGRATION_FILE.matcher(fileName);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        String version = matcher.group("version");
        List<BigInteger> parts = new ArrayList<>();
        for (String part : version.split("[._]")) {
            parts.add(new BigInteger(part));
        }
        return Optional.of(new Migration(normalized, fileName, version, parts));
    }

    private static String normalize(String path) {
        return path.replace('\\', '/');
    }

    private static String trimSlashes(String s) {
        return s.replaceAll("^/+|/+$", "");
    }

    private record Migration(String path, String fileName, String version, List<BigInteger> versionParts) {
    }
}
