package com.tuum.banking.controller;

import com.tuum.banking.model.dto.CreateTransactionRequest;
import com.tuum.banking.model.dto.ErrorResponse;
import com.tuum.banking.model.dto.TransactionResponse;
import com.tuum.banking.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/accounts/{accountId}/transactions")
@Tag(name = "Transactions", description = "Money movements against an account balance")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a transaction and update the matching balance")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Transaction created"),
            @ApiResponse(responseCode = "400", description = "Invalid currency, direction, amount or description",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Insufficient funds",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public TransactionResponse createTransaction(@PathVariable Long accountId,
                                                 @Valid @RequestBody CreateTransactionRequest request) {
        return transactionService.createTransaction(accountId, request);
    }

    @GetMapping
    @Operation(summary = "List an account's transactions in insertion order")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transactions returned"),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public List<TransactionResponse> getTransactions(@PathVariable Long accountId) {
        return transactionService.getTransactions(accountId);
    }
}
