package com.tuum.banking.repository;

import com.tuum.banking.model.entity.Transaction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TransactionMapper {

    /**
     * Inserts the transaction and writes the generated id back onto {@code transaction}.
     */
    void insert(Transaction transaction);

    List<Transaction> findByAccountId(@Param("accountId") Long accountId);
}
