package com.tuum.banking.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * One definition of how this service represents money.
 *
 * <p>Scale 2 matches the ISO-4217 minor unit of every supported currency — EUR, SEK, GBP
 * and USD all subdivide into hundredths — and matches the {@code NUMERIC(19,2)} columns, so
 * a value keeps the same scale whether it was just parsed from a request or just read back
 * from Postgres. Without that, the same transaction serialises differently depending on
 * which endpoint returned it.
 *
 * <p>Supporting a currency with a different minor unit (JPY at 0, KWD at 3) would need a
 * schema migration and a per-currency scale lookup rather than this constant.
 */
public final class Money {

    public static final int SCALE = 2;

    /** A zero amount already at the canonical scale. */
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE);

    private Money() {
    }

    /**
     * Brings an amount to the canonical scale.
     *
     * <p>{@code HALF_UP} rather than {@code UNNECESSARY} deliberately: request validation
     * ({@code @Digits(fraction = 2)}) already rejects over-precise input at the edge, so in
     * practice this only ever pads. Should that guard ever be loosened, rounding is a far
     * better failure mode than an {@link ArithmeticException} surfacing as a 500.
     */
    public static BigDecimal normalize(BigDecimal amount) {
        return amount.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
