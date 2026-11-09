package com.bank.account;

import com.bank.account.dto.AccountResponse;
import com.bank.account.dto.CreateAccountRequest;
import com.bank.account.dto.MoneyRequest;
import com.bank.account.dto.TransactionResponse;
import com.bank.account.dto.TransferRequest;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse created = accountService.create(request);
        return ResponseEntity.created(URI.create("/api/accounts/" + created.id())).body(created);
    }

    @PreAuthorize("@access.canViewCustomer(authentication, #customerId)")
    @GetMapping
    public PagedModel<AccountResponse> list(
            @RequestParam(required = false) Long customerId,
            @PageableDefault(sort = "id") Pageable pageable) {
        return new PagedModel<>(accountService.list(customerId, pageable));
    }

    @PreAuthorize("@access.canViewAccount(authentication, #id)")
    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable Long id) {
        return accountService.get(id);
    }

    @PostMapping("/{id}/deposit")
    public AccountResponse deposit(@PathVariable Long id, @Valid @RequestBody MoneyRequest request) {
        return accountService.deposit(id, request.amount());
    }

    @PostMapping("/{id}/withdraw")
    public AccountResponse withdraw(@PathVariable Long id, @Valid @RequestBody MoneyRequest request) {
        return accountService.withdraw(id, request.amount());
    }

    /** Answers with the account the money left. */
    @PostMapping("/{id}/transfer")
    public AccountResponse transfer(@PathVariable Long id, @Valid @RequestBody TransferRequest request) {
        return accountService.transfer(id, request.toAccountNumber(), request.amount());
    }

    /** Newest entries first unless a sort parameter says otherwise. */
    @PreAuthorize("@access.canViewAccount(authentication, #id)")
    @GetMapping("/{id}/transactions")
    public PagedModel<TransactionResponse> listTransactions(
            @PathVariable Long id,
            @PageableDefault(sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return new PagedModel<>(accountService.listTransactions(id, pageable));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        accountService.delete(id);
    }
}
