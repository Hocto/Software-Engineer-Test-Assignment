package com.tuum.banking.controller;

import com.tuum.banking.model.dto.AccountResponse;
import com.tuum.banking.model.dto.CreateAccountRequest;
import com.tuum.banking.model.dto.ErrorResponse;
import com.tuum.banking.service.AccountService;
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

@RestController
@RequestMapping("/accounts")
@Tag(name = "Accounts", description = "Account and balance management")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an account with a zero balance in each requested currency")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created"),
            @ApiResponse(responseCode = "400", description = "Invalid currency or failed validation",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public AccountResponse createAccount(@Valid @RequestBody CreateAccountRequest request) {
        return accountService.createAccount(request);
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Fetch an account with its balances")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account found"),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public AccountResponse getAccount(@PathVariable Long accountId) {
        return accountService.getAccount(accountId);
    }
}
