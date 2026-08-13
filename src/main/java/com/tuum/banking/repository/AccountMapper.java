package com.tuum.banking.repository;

import com.tuum.banking.model.entity.Account;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AccountMapper {

    /**
     * Inserts the account and writes the generated id back onto {@code account}.
     */
    void insert(Account account);

    Account findById(@Param("id") Long id);

    boolean existsById(@Param("id") Long id);
}
