package com.tuum.banking.model.entity;

import java.time.OffsetDateTime;

/**
 * Persistence view of the {@code account} table.
 *
 * <p>A mutable POJO rather than a record: MyBatis populates it through setters and
 * writes the generated key back into {@link #setId(Long)} after insert.
 */
public class Account {

    private Long id;
    private Long customerId;
    private String country;
    private OffsetDateTime createdAt;

    public Account() {
    }

    public Account(Long customerId, String country) {
        this.customerId = customerId;
        this.country = country;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
