package com.tuum.banking.repository;

import com.tuum.banking.AbstractIntegrationTest;
import com.tuum.banking.model.Money;
import com.tuum.banking.model.entity.Account;
import com.tuum.banking.model.entity.Balance;
import com.tuum.banking.model.enums.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the batched balance insert against real Postgres.
 *
 * <p>The service unit tests mock this mapper, so they say nothing about whether the SQL is
 * valid or whether MyBatis writes generated keys back into the list. Both are properties of
 * the mapper XML rather than of any Java code, and only a real database can confirm them.
 */
class BalanceMapperIT extends AbstractIntegrationTest {

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private BalanceMapper balanceMapper;

    private Long newAccountId() {
        Account account = new Account(1L, "EE");
        accountMapper.insert(account);
        return account.getId();
    }

    @Test
    @DisplayName("inserts every balance in one statement and assigns each a generated id")
    void insertAllPersistsEveryRow() {
        Long accountId = newAccountId();
        List<Balance> balances = List.of(
                new Balance(accountId, Currency.EUR, Money.ZERO),
                new Balance(accountId, Currency.USD, Money.ZERO),
                new Balance(accountId, Currency.SEK, Money.ZERO));

        balanceMapper.insertAll(balances);

        // Nothing reads these ids today, but the mapper claims to populate them; an untested
        // claim in SQL is one that quietly stops being true.
        assertThat(balances).allSatisfy(balance -> assertThat(balance.getId()).isNotNull());
        assertThat(balances).extracting(Balance::getId).doesNotHaveDuplicates();

        assertThat(balanceMapper.findByAccountId(accountId))
                .extracting(balance -> balance.getCurrency().name())
                .containsExactlyInAnyOrder("EUR", "USD", "SEK");
    }

    @Test
    @DisplayName("a single-element batch is still valid SQL")
    void insertAllHandlesOneRow() {
        Long accountId = newAccountId();
        List<Balance> balances = List.of(new Balance(accountId, Currency.GBP, Money.ZERO));

        balanceMapper.insertAll(balances);

        assertThat(balances.getFirst().getId()).isNotNull();
        assertThat(balanceMapper.findByAccountId(accountId)).hasSize(1);
    }

    @Test
    @DisplayName("balances are stored at the canonical money scale")
    void balancesStoreAtScaleTwo() {
        Long accountId = newAccountId();
        balanceMapper.insertAll(List.of(new Balance(accountId, Currency.EUR, Money.ZERO)));

        assertThat(balanceMapper.findByAccountId(accountId).getFirst().getAvailableAmount().scale())
                .isEqualTo(Money.SCALE);
    }
}
