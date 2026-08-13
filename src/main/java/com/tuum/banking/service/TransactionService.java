package com.tuum.banking.service;

import com.tuum.banking.exception.AccountNotFoundException;
import com.tuum.banking.exception.BalanceNotFoundException;
import com.tuum.banking.exception.InsufficientFundsException;
import com.tuum.banking.messaging.EventPublisher;
import com.tuum.banking.messaging.event.BalanceUpdatedEvent;
import com.tuum.banking.messaging.event.EventType;
import com.tuum.banking.messaging.event.TransactionCreatedEvent;
import com.tuum.banking.model.dto.CreateTransactionRequest;
import com.tuum.banking.model.dto.TransactionResponse;
import com.tuum.banking.model.entity.Balance;
import com.tuum.banking.model.entity.Transaction;
import com.tuum.banking.model.enums.Direction;
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
    private final EventPublisher eventPublisher;

    public TransactionService(AccountMapper accountMapper, BalanceMapper balanceMapper,
                              TransactionMapper transactionMapper, EventPublisher eventPublisher) {
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
        // 1. Distinguish "no such account" (404) from "account holds no such currency" (400).
        if (!accountMapper.existsById(accountId)) {
            throw new AccountNotFoundException(accountId);
        }

        // 2. Serialize concurrent writers on this exact balance row.
        Balance balance = balanceMapper.findByAccountIdAndCurrencyForUpdate(accountId, request.currency());
        if (balance == null) {
            throw new BalanceNotFoundException(accountId, request.currency());
        }

        BigDecimal previousAmount = balance.getAvailableAmount();
        BigDecimal newAmount = applyDirection(previousAmount, request.amount(), request.direction());

        if (newAmount.signum() < 0) {
            throw new InsufficientFundsException(accountId, request.currency(), previousAmount, request.amount());
        }

        // 3. Update the balance and append the ledger row inside the same transaction.
        balanceMapper.updateAmount(balance.getId(), newAmount);

        Transaction transaction = new Transaction();
        transaction.setAccountId(accountId);
        transaction.setAmount(request.amount());
        transaction.setCurrency(request.currency());
        transaction.setDirection(request.direction());
        transaction.setDescription(request.description());
        transaction.setBalanceAfter(newAmount);
        transactionMapper.insert(transaction);

        // 4. Queue events; they leave the process only once this transaction commits.
        eventPublisher.publishAfterCommit(EventType.TRANSACTION_CREATED, new TransactionCreatedEvent(
                transaction.getId(), accountId, transaction.getAmount(), transaction.getCurrency(),
                transaction.getDirection(), transaction.getDescription(), transaction.getBalanceAfter()));

        eventPublisher.publishAfterCommit(EventType.BALANCE_UPDATED, new BalanceUpdatedEvent(
                balance.getId(), accountId, balance.getCurrency(), previousAmount, newAmount));

        return TransactionResponse.from(transaction);
    }

    @Transactional(readOnly = true)
    public List<TransactionResponse> getTransactions(Long accountId) {
        if (!accountMapper.existsById(accountId)) {
            throw new AccountNotFoundException(accountId);
        }
        return transactionMapper.findByAccountId(accountId).stream()
                .map(TransactionResponse::from)
                .toList();
    }

    private static BigDecimal applyDirection(BigDecimal current, BigDecimal amount, Direction direction) {
        return direction == Direction.IN ? current.add(amount) : current.subtract(amount);
    }
}
