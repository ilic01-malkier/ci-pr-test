package ci.checks;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * CLI koji poziva pipeline.
 *
 * <pre>
 *   java -cp out ci.checks.Main title "&lt;naslov PR-a&gt;"     (bez argumenta čita env PR_TITLE)
 *   java -cp out ci.checks.Main migrations [--base origin/dev] [--folder db/migration]
 * </pre>
 *
 * Izlazni kod: 0 = provera prošla, 1 = provera pala, 2 = pogrešna upotreba / greška alata.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        // UTF-8 izlaz nezavisno od locale-a runner-a (da se č, ć, š ispravno prikažu).
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        System.exit(run(args));
    }

    static int run(String[] args) {
        if (args.length == 0) {
            return usage();
        }

        try {
            return switch (args[0]) {
                case "title" -> checkTitle(args.length > 1 ? args[1] : System.getenv("PR_TITLE"));
                case "migrations" -> checkMigrations(
                        option(args, "--base", "origin/dev"),
                        option(args, "--folder", "db/migration"));
                default -> usage();
            };
        } catch (Exception e) {
            System.out.println("::error::Greška u CI alatu: " + e.getMessage());
            return 2;
        }
    }

    private static int checkTitle(String title) {
        return report("Format naslova PR-a", new PrTitleValidator().validate(title));
    }

    private static int checkMigrations(String baseRef, String folder) throws IOException, InterruptedException {
        List<String> devFiles = Arrays.stream(
                        git("-c", "core.quotePath=false", "ls-tree", "-r", "--name-only", baseRef).split("\n"))
                .filter(s -> !s.isBlank())
                .toList();

        String diff = git("-c", "core.quotePath=false", "diff", "--name-status", "-M", baseRef + "...HEAD");
        List<FileChange> changes = MigrationOrderValidator.parseNameStatus(diff);

        return report("Redosled migracija", new MigrationOrderValidator(folder).validate(devFiles, changes));
    }

    private static int report(String checkName, ValidationResult result) {
        if (result.isValid()) {
            System.out.println("OK - " + checkName);
            return 0;
        }
        for (String error : result.errors()) {
            // Format "::error" GitHub Actions prikazuje kao anotaciju na PR-u.
            System.out.println("::error title=" + checkName + "::" + error);
        }
        return 1;
    }

    private static String git(String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(arguments));

        Process process = new ProcessBuilder(command).start();
        CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> readAll(process.getErrorStream()));
        String stdout = readAll(process.getInputStream());
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new IllegalStateException(String.join(" ", command) + " nije uspeo: " + stderr.join().trim());
        }
        return stdout;
    }

    private static String readAll(InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String option(String[] args, String name, String defaultValue) {
        int index = Arrays.asList(args).indexOf(name);
        return index >= 0 && index + 1 < args.length ? args[index + 1] : defaultValue;
    }

    private static int usage() {
        System.err.println("Upotreba: Main title \"<naslov>\" | Main migrations [--base origin/dev] [--folder db/migration]");
        return 2;
    }
}
