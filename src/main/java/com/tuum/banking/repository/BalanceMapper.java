package com.tuum.banking.repository;

import com.tuum.banking.model.entity.Balance;
import com.tuum.banking.model.enums.Currency;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface BalanceMapper {

    void insert(Balance balance);

    List<Balance> findByAccountId(@Param("accountId") Long accountId);

    /**
     * Reads the balance row under a {@code SELECT ... FOR UPDATE} row lock.
     *
     * <p>This is the serialization point for concurrent transactions on the same
     * (account, currency). Callers <strong>must</strong> be inside a transaction —
     * outside one the lock would be released immediately by autocommit.
     *
     * @return the locked balance, or {@code null} if the account holds no balance
     * in that currency
     */
    Balance findByAccountIdAndCurrencyForUpdate(@Param("accountId") Long accountId,
                                                @Param("currency") Currency currency);

    /**
     * @return number of rows updated; 1 on success
     */
    int updateAmount(@Param("id") Long id, @Param("availableAmount") BigDecimal availableAmount);
}
