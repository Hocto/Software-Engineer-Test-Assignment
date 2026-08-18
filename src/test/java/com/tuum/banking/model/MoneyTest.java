package com.tuum.banking.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the one place the service defines what "an amount" means.
 *
 * <p>Scale is asserted with {@code toPlainString}, not {@code isEqualByComparingTo}:
 * {@code BigDecimal} comparison ignores scale, which is precisely the property that let a
 * scale bug reach the API unnoticed.
 */
class MoneyTest {

    @Test
    @DisplayName("ZERO is initialised at the canonical scale")
    void zeroCarriesCanonicalScale() {
        // ZERO is built by calling normalize during class initialisation; this asserts that
        // resolves correctly rather than yielding a null or an unscaled zero.
        assertThat(Money.ZERO).isNotNull();
        assertThat(Money.ZERO.toPlainString()).isEqualTo("0.00");
        assertThat(Money.ZERO.scale()).isEqualTo(Money.SCALE);
        assertThat(Money.ZERO.signum()).isZero();
    }

    @ParameterizedTest(name = "{0} normalises to {1}")
    @CsvSource({
            "40,        40.00",
            "40.5,      40.50",
            "250.75,    250.75",
            "0,         0.00",
            "0.1,       0.10",
            "999999.99, 999999.99"
    })
    @DisplayName("amounts within scale are padded, never altered in value")
    void padsWithoutChangingValue(String input, String expected) {
        BigDecimal normalized = Money.normalize(new BigDecimal(input));

        assertThat(normalized.toPlainString()).isEqualTo(expected);
        assertThat(normalized).isEqualByComparingTo(new BigDecimal(input));
    }

    @Test
    @DisplayName("over-precise input rounds rather than throwing")
    void roundsRatherThanThrowing() {
        // Unreachable through the API — @Digits(fraction = 2) rejects this at the edge with a
        // 400. Asserted anyway because the alternative, setScale(UNNECESSARY), would surface
        // as a 500 if that guard were ever loosened.
        assertThat(Money.normalize(new BigDecimal("10.555")).toPlainString()).isEqualTo("10.56");
        assertThat(Money.normalize(new BigDecimal("10.554")).toPlainString()).isEqualTo("10.55");
    }
}
