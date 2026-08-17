package com.tuum.banking.model.enums;

import java.math.BigDecimal;
import java.util.function.BinaryOperator;

/**
 * Direction of a transaction, carrying the arithmetic it implies.
 *
 * <p>The operation lives here rather than as a conditional in the service. That is not
 * tidiness: a {@code direction == IN ? add : subtract} branch elsewhere can be left unwritten
 * when a constant is added, and the compiler will not object — the new direction silently
 * takes the {@code else} arm and money moves the wrong way. Declaring the operation as a
 * constructor argument makes supplying it a condition of the enum compiling at all.
 */
public enum Direction {

    /** Credits the balance. */
    IN(BigDecimal::add),

    /** Debits the balance. */
    OUT(BigDecimal::subtract);

    private final BinaryOperator<BigDecimal> operation;

    Direction(BinaryOperator<BigDecimal> operation) {
        this.operation = operation;
    }

    /**
     * Applies this direction's movement to a balance.
     *
     * <p>Returns the resulting amount without judging it — rejecting an overdraft needs the
     * account context the caller holds, so that decision stays in the service.
     *
     * @param balance the current available amount
     * @param amount  the transaction amount, always positive
     * @return the balance after the movement; may be negative for {@link #OUT}
     */
    public BigDecimal applyTo(BigDecimal balance, BigDecimal amount) {
        return operation.apply(balance, amount);
    }
}
