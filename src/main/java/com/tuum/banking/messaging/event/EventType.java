package com.tuum.banking.messaging.event;

/**
 * Event types and the routing keys they publish under on the {@code banking.events}
 * topic exchange. Keeping the key beside the type stops the two drifting apart.
 */
public enum EventType {

    ACCOUNT_CREATED("account.created"),
    TRANSACTION_CREATED("transaction.created"),
    BALANCE_UPDATED("balance.updated");

    private final String routingKey;

    EventType(String routingKey) {
        this.routingKey = routingKey;
    }

    public String routingKey() {
        return routingKey;
    }
}
