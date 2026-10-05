package rs.probni;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class KalkulatorTest {
    @Test
    void sabiranje() {
        assertEquals(5, new Kalkulator().saberi(2, 3));
    }
}
