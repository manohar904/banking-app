package com.bank.account.loan;

import com.bank.account.loan.LoanDtos.LoanApplication;
import com.bank.account.loan.LoanDtos.LoanOffer;
import com.bank.account.loan.LoanDtos.LoanQuote;
import com.bank.account.loan.LoanDtos.LoanRejection;
import com.bank.account.loan.LoanDtos.LoanResponse;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.net.URI;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Loans. Who may call what is in SecurityConfig; LoanService checks that customers keep to their own. */
@RestController
@RequestMapping("/api/loans")
@RequiredArgsConstructor
public class LoanController {

    private final LoanService loans;

    /** The interest rate and limits. */
    @GetMapping("/offer")
    public LoanOffer offer() {
        return loans.offer();
    }

    /** What a loan of this size and length would cost. */
    @GetMapping("/quote")
    public LoanQuote quote(@RequestParam BigDecimal amount, @RequestParam int months) {
        return loans.quote(amount, months);
    }

    /** Customers only. */
    @PostMapping
    public ResponseEntity<LoanResponse> apply(Authentication caller, @Valid @RequestBody LoanApplication application) {
        LoanResponse created = loans.apply(caller, application.accountId(), application.amount(),
                application.months(), application.purpose());
        return ResponseEntity.created(URI.create("/api/loans/" + created.id())).body(created);
    }

    /** Newest first. Staff may add customerId=; a customer always gets their own. */
    @GetMapping
    public PagedModel<LoanResponse> list(Authentication caller,
            @RequestParam(required = false) Long customerId,
            @RequestParam(name = "status", required = false) Set<LoanStatus> statuses,
            @PageableDefault(sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return new PagedModel<>(loans.list(caller, customerId, statuses == null ? Set.of() : statuses, pageable));
    }

    @GetMapping("/{id}")
    public LoanResponse get(Authentication caller, @PathVariable Long id) {
        return loans.get(caller, id);
    }

    @PostMapping("/{id}/approve")
    public LoanResponse approve(@PathVariable Long id) {
        return loans.approve(id);
    }

    @PostMapping("/{id}/reject")
    public LoanResponse reject(@PathVariable Long id, @Valid @RequestBody(required = false) LoanRejection rejection) {
        return loans.reject(id, rejection == null ? null : rejection.note());
    }

    @PostMapping("/{id}/cancel")
    public LoanResponse cancel(Authentication caller, @PathVariable Long id) {
        return loans.cancel(caller, id);
    }

    /** This month's repayment, from the loan's account. */
    @PostMapping("/{id}/repay")
    public LoanResponse repay(Authentication caller, @PathVariable Long id) {
        return loans.repay(caller, id, false);
    }

    /** Everything still owed, with no fee for paying early. */
    @PostMapping("/{id}/pay-off")
    public LoanResponse payOff(Authentication caller, @PathVariable Long id) {
        return loans.repay(caller, id, true);
    }
}
