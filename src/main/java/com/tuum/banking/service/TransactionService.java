package com.tuum.banking.service;

import com.tuum.banking.exception.AccountNotFoundException;
import com.tuum.banking.exception.CurrencyNotHeldException;
import com.tuum.banking.exception.InsufficientFundsException;
import com.tuum.banking.messaging.DomainEventPublisher;
import com.tuum.banking.messaging.event.BalanceUpdatedEvent;
import com.tuum.banking.messaging.event.EventType;
import com.tuum.banking.messaging.event.TransactionCreatedEvent;
import com.tuum.banking.model.Money;
import com.tuum.banking.model.dto.CreateTransactionRequest;
import com.tuum.banking.model.dto.TransactionResponse;
import com.tuum.banking.model.entity.Balance;
import com.tuum.banking.model.entity.Transaction;
import com.tuum.banking.repository.AccountMapper;
import com.tuum.banking.repository.BalanceMapper;
import com.tuum.banking.repository.TransactionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class TransactionService {

    private final AccountMapper accountMapper;
    private final BalanceMapper balanceMapper;
    private final TransactionMapper transactionMapper;
    private final DomainEventPublisher eventPublisher;

    public TransactionService(AccountMapper accountMapper, BalanceMapper balanceMapper,
                              TransactionMapper transactionMapper, DomainEventPublisher eventPublisher) {
        this.accountMapper = accountMapper;
        this.balanceMapper = balanceMapper;
        this.transactionMapper = transactionMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Applies a transaction to the account's balance in the given currency.
     *
     * <p>Correctness under concurrency rests on step 2: the balance row is read under
     * {@code SELECT ... FOR UPDATE}, so any competing transaction on the same
     * (account, currency) blocks until this one commits. Without that lock, two
     * concurrent withdrawals could each read the same starting amount and both pass the
     * funds check — the classic lost update, and an overdraft.
     *
     * <p>The whole method is one transaction, so the balance update and the transaction
     * row commit together or not at all: the ledger can never disagree with the balance.
     */
    @Transactional
    public TransactionResponse createTransaction(Long accountId, CreateTransactionRequest request) {
        // 1. Distinguish "no such account" (404) from "account holds no such currency" (422).
        requireAccountExists(accountId);

        // 2. Serialize concurrent writers on this exact balance row.
        Balance balance = balanceMapper.findByAccountIdAndCurrencyForUpdate(accountId, request.currency());
        if (balance == null) {
            throw new CurrencyNotHeldException(accountId, request.currency());
        }

        // Normalize once, then use the same value for the arithmetic, the persisted row and
        // the response — otherwise POST echoes the caller's scale while GET returns the
        // column's, and the same transaction serializes two different ways.
        BigDecimal amount = Money.normalize(request.amount());

        BigDecimal previousAmount = balance.getAvailableAmount();
        BigDecimal newAmount = request.direction().applyTo(previousAmount, amount);

        if (newAmount.signum() < 0) {
            throw new InsufficientFundsException(accountId, request.currency(), previousAmount, amount);
        }

        // 3. Update the balance and append the ledger row inside the same transaction.
        balanceMapper.updateAmount(balance.getId(), newAmount);

        Transaction transaction = newTransaction(accountId, request, amount, newAmount);
        transactionMapper.insert(transaction);

        // 4. Queue events; they leave the process only once this transaction commits.
        //    Built after the insert, so both carry the database-generated id.
        eventPublisher.publishAfterCommit(EventType.TRANSACTION_CREATED,
                TransactionCreatedEvent.from(transaction));
        eventPublisher.publishAfterCommit(EventType.BALANCE_UPDATED,
                BalanceUpdatedEvent.of(balance, previousAmount, newAmount));

        return TransactionResponse.from(transaction);
    }

    @Transactional(readOnly = true)
    public List<TransactionResponse> getTransactions(Long accountId) {
        requireAccountExists(accountId);
        return transactionMapper.findByAccountId(accountId).stream()
                .map(TransactionResponse::from)
                .toList();
    }

    private void requireAccountExists(Long accountId) {
        if (!accountMapper.existsById(accountId)) {
            throw new AccountNotFoundException(accountId);
        }
    }

    /**
     * Assembles the ledger row. {@code amount} is the movement, {@code balanceAfter} the
     * resulting balance — both {@code BigDecimal}, so the named setters below are what keep
     * the two from being transposed.
     */
    private static Transaction newTransaction(Long accountId, CreateTransactionRequest request,
                                              BigDecimal amount, BigDecimal balanceAfter) {
        Transaction transaction = new Transaction();
        transaction.setAccountId(accountId);
        transaction.setAmount(amount);
        transaction.setCurrency(request.currency());
        transaction.setDirection(request.direction());
        transaction.setDescription(request.description());
        transaction.setBalanceAfter(balanceAfter);
        return transaction;
    }
}
