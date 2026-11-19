package com.bank.account;

import com.bank.account.dto.AccountResponse;
import com.bank.account.dto.CreateAccountRequest;
import com.bank.account.dto.MoneyRequest;
import com.bank.account.dto.TransactionResponse;
import com.bank.account.dto.TransferAllowanceResponse;
import com.bank.account.dto.TransferRequest;
import com.bank.account.savings.SavingsInterest;
import com.bank.account.statement.StatementCsv;
import com.bank.account.statement.StatementPdf;
import com.bank.common.security.Callers;
import jakarta.validation.Valid;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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
    private final CustomerTransfers customerTransfers;
    private final AccountStatements accountStatements;
    private final SavingsInterest savingsInterest;

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
        AccountResponse account = accountService.get(id);
        return account.type() == AccountType.SAVINGS
                ? account.withAnnualInterestRate(savingsInterest.annualRate()) : account;
    }

    /** For staff finding an account from its number. Customers see their own accounts in the list. */
    @PreAuthorize("hasRole('STAFF')")
    @GetMapping("/by-number/{accountNumber:\\d{12}}")
    public AccountResponse getByNumber(@PathVariable String accountNumber) {
        return accountService.getByNumber(accountNumber);
    }

    @PostMapping("/{id}/deposit")
    public AccountResponse deposit(@PathVariable Long id, @Valid @RequestBody MoneyRequest request) {
        return accountService.deposit(id, request.amount());
    }

    @PostMapping("/{id}/withdraw")
    public AccountResponse withdraw(@PathVariable Long id, @Valid @RequestBody MoneyRequest request) {
        return accountService.withdraw(id, request.amount());
    }

    /**
     * Answers with the account the money left. Staff may send from any account, without a limit; a
     * customer only from their own accounts, up to their daily limit.
     */
    @PreAuthorize("@access.canViewAccount(authentication, #id)")
    @PostMapping("/{id}/transfer")
    public AccountResponse transfer(@PathVariable Long id, @Valid @RequestBody TransferRequest request,
            Authentication authentication) {
        if (Callers.isStaff(authentication)) {
            return accountService.transfer(id, request.toAccountNumber(), request.amount());
        }
        return customerTransfers.transfer(Callers.customerIdOf(authentication), id, request.toAccountNumber(),
                request.amount());
    }

    /** How much the account's owner may still send by transfer today. */
    @PreAuthorize("@access.canViewAccount(authentication, #id)")
    @GetMapping("/{id}/transfer-allowance")
    public TransferAllowanceResponse transferAllowance(@PathVariable Long id) {
        return customerTransfers.allowance(id);
    }

    /** Newest entries first unless a sort parameter says otherwise. */
    @PreAuthorize("@access.canViewAccount(authentication, #id)")
    @GetMapping("/{id}/transactions")
    public PagedModel<TransactionResponse> listTransactions(
            @PathVariable Long id,
            @PageableDefault(sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return new PagedModel<>(accountService.listTransactions(id, pageable));
    }

    /** Money in and out for each of the last months (12 unless months= says otherwise), oldest first. */
    @PreAuthorize("@access.canViewAccount(authentication, #id)")
    @GetMapping("/{id}/summary")
    public List<AccountStatements.MonthSummary> summary(@PathVariable Long id,
            @RequestParam(defaultValue = "12") int months) {
        return accountStatements.summary(id, months);
    }

    /** One month's statement as a PDF, this month unless month=YYYY-MM says otherwise. */
    @PreAuthorize("@access.canViewAccount(authentication, #id)")
    @GetMapping(value = "/{id}/statement", produces = StatementPdf.CONTENT_TYPE)
    public ResponseEntity<byte[]> statement(@PathVariable Long id, @RequestParam(required = false) YearMonth month) {
        AccountStatements.File file = accountStatements.forMonth(id, month);
        return asDownload(file.name(), file.content());
    }

    /** The same month's entries as a CSV for spreadsheets, this month unless month=YYYY-MM says otherwise. */
    @PreAuthorize("@access.canViewAccount(authentication, #id)")
    @GetMapping(value = "/{id}/statement.csv", produces = StatementCsv.CONTENT_TYPE)
    public ResponseEntity<byte[]> statementCsv(@PathVariable Long id, @RequestParam(required = false) YearMonth month) {
        AccountStatements.File file = accountStatements.csvForMonth(id, month);
        return asDownload(file.name(), file.content(), new MediaType("text", "csv", StandardCharsets.UTF_8));
    }

    /** A PDF sent as an attachment, so the browser saves it under this name rather than showing it. */
    public static ResponseEntity<byte[]> asDownload(String name, byte[] content) {
        return asDownload(name, content, MediaType.APPLICATION_PDF);
    }

    /** Sent as an attachment, so the browser saves it under this name rather than showing it. */
    public static ResponseEntity<byte[]> asDownload(String name, byte[] content, MediaType type) {
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(content);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        accountService.delete(id);
    }
}
