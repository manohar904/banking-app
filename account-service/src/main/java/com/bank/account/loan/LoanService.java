package com.bank.account.loan;

import com.bank.account.Account;
import com.bank.account.AccountRepository;
import com.bank.account.AccountTransaction;
import com.bank.account.AccountTransactionRepository;
import com.bank.account.InsufficientFundsException;
import com.bank.account.RetiredCustomers;
import com.bank.account.TransactionType;
import com.bank.account.loan.LoanDtos.LoanOffer;
import com.bank.account.loan.LoanDtos.LoanQuote;
import com.bank.account.loan.LoanDtos.LoanResponse;
import com.bank.common.ConflictException;
import com.bank.common.ResourceNotFoundException;
import com.bank.common.events.DomainEvents;
import com.bank.common.security.Callers;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Customers ask for loans; staff approve or reject them. Approving pays the money into the
 * customer's account, and repayments take it back from there, each in the same database
 * transaction as the loan's own change, so the loan and the balance always agree.
 *
 * <p>A customer only ever sees their own loans: anyone else's answers 404, as if it did not exist.
 */
@Service
@Transactional
public class LoanService {

    /** Loans being repaid at once. A customer may also have only one request waiting at a time. */
    static final int MAX_ACTIVE_PER_CUSTOMER = 3;

    private final LoanRepository loans;
    private final LoanRepaymentRepository repayments;
    private final AccountRepository accounts;
    private final AccountTransactionRepository ledger;
    /** The per-customer lock that opening an account and customer transfers take. */
    private final RetiredCustomers customerLock;
    private final DomainEvents events;
    private final LoanTerms terms;
    private final Clock clock;

    LoanService(LoanRepository loans, LoanRepaymentRepository repayments, AccountRepository accounts,
            AccountTransactionRepository ledger, RetiredCustomers customerLock, DomainEvents events, LoanTerms terms,
            @Value("${bank.transfers.time-zone}") ZoneId timeZone) {
        this.loans = loans;
        this.repayments = repayments;
        this.accounts = accounts;
        this.ledger = ledger;
        this.customerLock = customerLock;
        this.events = events;
        this.terms = terms;
        this.clock = Clock.system(timeZone);
    }

    @Transactional(readOnly = true)
    public LoanOffer offer() {
        return new LoanOffer(terms.annualRate(), terms.minAmount(), terms.maxAmount(), terms.minMonths(),
                terms.maxMonths());
    }

    @Transactional(readOnly = true)
    public LoanQuote quote(BigDecimal amount, int months) {
        requireWithinTerms(amount, months);
        BigDecimal total = LoanTerms.totalRepayable(amount, terms.annualRate(), months);
        return new LoanQuote(amount, months, terms.annualRate(),
                LoanTerms.monthlyPayment(amount, terms.annualRate(), months), total, total.subtract(amount));
    }

    public LoanResponse apply(Authentication caller, Long accountId, BigDecimal amount, int months, String purpose) {
        Long customerId = Callers.customerIdOf(caller);
        Account account = accounts.findById(accountId)
                .filter(found -> found.getCustomerId().equals(customerId))
                .orElseThrow(() -> new ResourceNotFoundException("Account " + accountId + " not found."));
        requireWithinTerms(amount, months);
        // Held until this commits, so two requests sent at the same moment are checked one after the other.
        customerLock.lock(customerId);
        if (loans.countByCustomerIdAndStatus(customerId, LoanStatus.REQUESTED) > 0) {
            throw new ConflictException("You already have a loan request waiting. Please wait for our answer.");
        }
        if (loans.countByCustomerIdAndStatus(customerId, LoanStatus.ACTIVE) >= MAX_ACTIVE_PER_CUSTOMER) {
            throw new ConflictException("You can have at most " + MAX_ACTIVE_PER_CUSTOMER
                    + " loans at a time. Please repay one first.");
        }
        Loan loan = loans.saveAndFlush(new Loan(customerId, accountId, amount.setScale(2), terms.annualRate(), months,
                purpose.strip(), clock.instant()));
        events.record("LOAN_REQUESTED", auditId(loan), Map.of("customerId", customerId, "amount", loan.getPrincipal(),
                "months", months, "accountId", accountId));
        return response(loan, account.getAccountNumber(), null);
    }

    /** A customer gets their own; staff everyone's, or one customer's, optionally only some statuses. */
    @Transactional(readOnly = true)
    public Page<LoanResponse> list(Authentication caller, Long customerId, Set<LoanStatus> statuses,
            Pageable pageable) {
        Long owner = Callers.isStaff(caller) ? customerId : Callers.customerIdOf(caller);
        Set<LoanStatus> wanted = statuses.isEmpty() ? EnumSet.allOf(LoanStatus.class) : statuses;
        Page<Loan> page = owner == null
                ? loans.findByStatusIn(wanted, pageable)
                : loans.findByCustomerIdAndStatusIn(owner, wanted, pageable);
        Map<Long, String> numbers = accounts.findAllById(page.map(Loan::getAccountId).toSet()).stream()
                .collect(Collectors.toMap(Account::getId, Account::getAccountNumber));
        return page.map(loan -> response(loan, numbers.get(loan.getAccountId()), null));
    }

    @Transactional(readOnly = true)
    public LoanResponse get(Authentication caller, Long id) {
        Loan loan = loans.findById(id).filter(found -> mayUse(caller, found)).orElseThrow(() -> notFound(id));
        return detail(loan);
    }

    /** Staff only. Pays the loan into the customer's account. */
    public LoanResponse approve(Long id) {
        Loan loan = loans.findForUpdate(id).orElseThrow(() -> notFound(id));
        loan.approve(clock.instant(), LocalDate.now(clock));
        Account account = accountOf(loan);
        account.deposit(loan.getPrincipal());
        Account saved = accounts.saveAndFlush(account);
        ledger.save(AccountTransaction.forLoan(saved, TransactionType.LOAN_PAYOUT, loan.getPrincipal(), loan.getId()));
        loans.saveAndFlush(loan);
        events.record("LOAN_APPROVED", auditId(loan), Map.of("customerId", loan.getCustomerId(),
                "amount", loan.getPrincipal()));
        events.record("LOAN_PAID_OUT", saved.getId(), moneyMoved(saved, loan.getPrincipal(), loan.getId()));
        return detail(loan);
    }

    /** Staff only. The note, if any, is shown to the customer. */
    public LoanResponse reject(Long id, String note) {
        Loan loan = loans.findForUpdate(id).orElseThrow(() -> notFound(id));
        loan.reject(note == null || note.isBlank() ? null : note.strip(), clock.instant());
        loans.saveAndFlush(loan);
        events.record("LOAN_REJECTED", auditId(loan), Map.of("customerId", loan.getCustomerId()));
        return detail(loan);
    }

    /** Customers only, for their own request that staff haven't decided yet. */
    public LoanResponse cancel(Authentication caller, Long id) {
        Loan loan = findForUpdate(caller, id);
        loan.cancel(clock.instant());
        loans.saveAndFlush(loan);
        events.record("LOAN_CANCELLED", auditId(loan), Map.of("customerId", loan.getCustomerId()));
        return detail(loan);
    }

    /**
     * Takes this month's repayment from the loan's account, or with payOff everything still owed.
     * Not enough money in the account is a 422, and nothing changes.
     */
    public LoanResponse repay(Authentication caller, Long id, boolean payOff) {
        Loan loan = findForUpdate(caller, id);
        takeRepayment(loan, payOff);
        return detail(loan);
    }

    /** Active loans with a payment due today or earlier, for the automatic payments. */
    @Transactional(readOnly = true)
    public List<Long> dueLoanIds() {
        return loans.findIdsDueBy(LocalDate.now(clock));
    }

    /**
     * Takes one loan's due payment, if it is still due: someone may have paid it a moment ago. With too
     * little money in the account nothing is taken, and the customer is told once per missed payment;
     * the next run tries again.
     */
    public AutoPayment takeDuePayment(Long id) {
        LocalDate today = LocalDate.now(clock);
        Loan loan = loans.findForUpdate(id).orElse(null);
        if (loan == null || loan.getStatus() != LoanStatus.ACTIVE || loan.getNextPaymentDue().isAfter(today)) {
            return AutoPayment.NOT_DUE;
        }
        try {
            takeRepayment(loan, false);
            return AutoPayment.TAKEN;
        } catch (InsufficientFundsException e) {
            // Thrown before anything changed, so this transaction can still record the miss.
            if (loan.autoPaymentFailed()) {
                loans.saveAndFlush(loan);
                Account account = accountOf(loan);
                BigDecimal amount = loan.nextRepayment(false).amount();
                events.record("LOAN_PAYMENT_MISSED", auditId(loan), Map.of("customerId", loan.getCustomerId(),
                        "amount", amount, "due", loan.getNextPaymentDue().toString()));
                events.record("LOAN_PAYMENT_NOT_TAKEN", account.getId(), moneyMoved(account, amount, loan.getId()));
            }
            return AutoPayment.NOT_ENOUGH_MONEY;
        }
    }

    public enum AutoPayment {
        TAKEN,
        NOT_ENOUGH_MONEY,
        NOT_DUE
    }

    private void takeRepayment(Loan loan, boolean payOff) {
        LoanTerms.Repayment repayment = loan.nextRepayment(payOff);
        Account account = accountOf(loan);
        account.withdraw(repayment.amount());
        Account saved = accounts.saveAndFlush(account);
        ledger.save(AccountTransaction.forLoan(saved, TransactionType.LOAN_REPAYMENT, repayment.amount(), loan.getId()));
        repayments.save(new LoanRepayment(loan.getId(), repayment, clock.instant()));
        loan.repaid(repayment, clock.instant());
        loans.saveAndFlush(loan);
        events.record("LOAN_REPAID", auditId(loan), Map.of("customerId", loan.getCustomerId(),
                "amount", repayment.amount(), "interest", repayment.interest(),
                "remaining", repayment.remainingAfter()));
        events.record("LOAN_REPAYMENT_TAKEN", saved.getId(), moneyMoved(saved, repayment.amount(), loan.getId()));
        if (loan.getStatus() == LoanStatus.PAID_OFF) {
            events.record("LOAN_PAID_OFF", auditId(loan), Map.of("customerId", loan.getCustomerId()));
        }
    }

    private void requireWithinTerms(BigDecimal amount, int months) {
        String problem = terms.problemWith(amount, months);
        if (problem != null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, problem);
        }
    }

    private Loan findForUpdate(Authentication caller, Long id) {
        return loans.findForUpdate(id).filter(found -> mayUse(caller, found)).orElseThrow(() -> notFound(id));
    }

    private Account accountOf(Loan loan) {
        return accounts.findById(loan.getAccountId())
                .orElseThrow(() -> new ConflictException("The loan's account no longer exists."));
    }

    private LoanResponse detail(Loan loan) {
        String number = accounts.findById(loan.getAccountId()).map(Account::getAccountNumber).orElse(null);
        return response(loan, number, repayments.findByLoanIdOrderById(loan.getId()));
    }

    private LoanResponse response(Loan loan, String accountNumber, List<LoanRepayment> history) {
        return LoanResponse.from(loan, accountNumber, LocalDate.now(clock), history);
    }

    /** The same fields as deposits and withdrawals, so transaction emails can describe it. */
    private static Map<String, Object> moneyMoved(Account account, BigDecimal amount, Long loanId) {
        String number = account.getAccountNumber();
        return Map.of("amount", amount, "balanceAfter", account.getBalance(), "loanId", loanId,
                "customerId", account.getCustomerId(), "accountEnding", number.substring(number.length() - 4));
    }

    private static boolean mayUse(Authentication caller, Loan loan) {
        return Callers.canViewCustomer(caller, loan.getCustomerId());
    }

    /** Loan and account ids can be the same number, so the audit log and events name loans "loan-<id>". */
    static String auditId(Loan loan) {
        return "loan-" + loan.getId();
    }

    private static ResourceNotFoundException notFound(Long id) {
        return new ResourceNotFoundException("Loan " + id + " not found.");
    }

    /** For AccountService: an account can't be deleted while a loan on it is waiting or being repaid. */
    public boolean hasOpenLoan(Long accountId) {
        return loans.existsByAccountIdAndStatusIn(accountId, EnumSet.of(LoanStatus.REQUESTED, LoanStatus.ACTIVE));
    }
}
