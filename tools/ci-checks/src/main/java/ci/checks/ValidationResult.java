package ci.checks;

import java.util.List;

/** Rezultat jedne provere: da li je prošla i lista poruka o greškama. */
public record ValidationResult(boolean isValid, List<String> errors) {

    public ValidationResult {
        errors = List.copyOf(errors);
    }

    public static ValidationResult ok() {
        return new ValidationResult(true, List.of());
    }

    public static ValidationResult fail(String... errors) {
        return new ValidationResult(false, List.of(errors));
    }

    public static ValidationResult fromErrors(List<String> errors) {
        return errors.isEmpty() ? ok() : new ValidationResult(false, errors);
    }
}
