package com.bank.account.scheduled;

import com.bank.account.Account;
import com.bank.account.AccountRepository;
import com.bank.account.CustomerTransfers;
import com.bank.account.scheduled.ScheduledPaymentDtos.ScheduledPaymentResponse;
import com.bank.account.scheduled.ScheduledPaymentDtos.SetUp;
import com.bank.common.ConflictException;
import com.bank.common.ResourceNotFoundException;
import com.bank.common.events.DomainEvents;
import com.bank.common.security.Callers;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Monthly payments a customer sets up once. Each is sent as one of the customer's own transfers, so it
 * counts towards, and is held to, their daily transfer limit like any other. Staff can set them up and
 * cancel them for a customer too; they are still the customer's transfers.
 *
 * <p>A customer only ever sees their own: anyone else's answers 404, as if it did not exist.
 */
@Service
@Transactional
public class ScheduledPaymentService {

    /** Active scheduled payments a customer may have at once. */
    static final int MAX_ACTIVE_PER_CUSTOMER = 10;

    private final ScheduledPaymentRepository payments;
    private final AccountRepository accounts;
    private final CustomerTransfers transfers;
    private final DomainEvents events;
    private final BigDecimal dailyLimit;
    private final int giveUpAfterDays;
    private final Clock clock;

    ScheduledPaymentService(ScheduledPaymentRepository payments, AccountRepository accounts,
            CustomerTransfers transfers, DomainEvents events,
            @Value("${bank.transfers.customer-daily-limit}") BigDecimal dailyLimit,
            @Value("${bank.scheduled-payments.give-up-after-days}") int giveUpAfterDays,
            @Value("${bank.transfers.time-zone}") ZoneId timeZone) {
        this.payments = payments;
        this.accounts = accounts;
        this.transfers = transfers;
        this.events = events;
        this.dailyLimit = dailyLimit;
        this.giveUpAfterDays = giveUpAfterDays;
        this.clock = Clock.system(timeZone);
    }

    /** A customer gets their own; staff one customer's. Active ones first, then cancelled, oldest first. */
    @Transactional(readOnly = true)
    public List<ScheduledPaymentResponse> list(Authentication caller, Long customerId) {
        Long owner = Callers.isStaff(caller) ? customerId : Callers.customerIdOf(caller);
        if (owner == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say whose payments: customerId=.");
        }
        List<ScheduledPayment> found = payments.findByCustomerIdOrderById(owner);
        Map<Long, String> numbers = accounts.findAllById(found.stream().map(ScheduledPayment::getFromAccountId)
                        .collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Account::getId, Account::getAccountNumber));
        return found.stream()
                .sorted((a, b) -> a.getStatus() == b.getStatus() ? 0
                        : a.getStatus() == ScheduledPayment.Status.ACTIVE ? -1 : 1)
                .map(payment -> ScheduledPaymentResponse.from(payment, numbers.get(payment.getFromAccountId())))
                .toList();
    }

    public ScheduledPaymentResponse setUp(Authentication caller, SetUp request) {
        Account from = accounts.findById(request.fromAccountId())
                .filter(account -> Callers.canViewCustomer(caller, account.getCustomerId()))
                .orElseThrow(() -> new ResourceNotFoundException("Account " + request.fromAccountId() + " not found."));
        Account to = accounts.findByAccountNumber(request.toAccountNumber())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No account has the number " + request.toAccountNumber() + "."));
        if (from.getId().equals(to.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An account cannot pay itself.");
        }
        BigDecimal amount = request.amount().setScale(2);
        if (amount.compareTo(dailyLimit) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A scheduled payment can be at most the daily transfer limit of " + dailyLimit.toPlainString() + ".");
        }
        Long customerId = from.getCustomerId();
        if (payments.countByCustomerIdAndStatus(customerId, ScheduledPayment.Status.ACTIVE) >= MAX_ACTIVE_PER_CUSTOMER) {
            throw new ConflictException("A customer can have at most " + MAX_ACTIVE_PER_CUSTOMER
                    + " scheduled payments. Cancel one first.");
        }
        String reference = request.reference() == null || request.reference().isBlank() ? null
                : request.reference().strip();
        ScheduledPayment saved = payments.saveAndFlush(new ScheduledPayment(customerId, from.getId(),
                to.getAccountNumber(), amount, request.dayOfMonth(), reference, LocalDate.now(clock), clock.instant()));
        events.record("SCHEDULED_PAYMENT_SET_UP", auditId(saved), Map.of("customerId", customerId,
                "accountId", from.getId(), "amount", amount, "dayOfMonth", saved.getDayOfMonth()));
        return ScheduledPaymentResponse.from(saved, from.getAccountNumber());
    }

    public ScheduledPaymentResponse cancel(Authentication caller, Long id) {
        ScheduledPayment payment = payments.findForUpdate(id)
                .filter(found -> Callers.canViewCustomer(caller, found.getCustomerId()))
                .orElseThrow(() -> new ResourceNotFoundException("Scheduled payment " + id + " not found."));
        if (payment.getStatus() == ScheduledPayment.Status.ACTIVE) {
            payment.cancel(clock.instant());
            payments.saveAndFlush(payment);
            events.record("SCHEDULED_PAYMENT_CANCELLED", auditId(payment),
                    Map.of("customerId", payment.getCustomerId()));
        }
        return ScheduledPaymentResponse.from(payment,
                accounts.findById(payment.getFromAccountId()).map(Account::getAccountNumber).orElse(null));
    }

    public List<Long> dueIds() {
        return payments.findIdsDueBy(LocalDate.now(clock));
    }

    /**
     * Sends the payment if it is still due. Any problem, such as too little money, is thrown and rolls
     * everything back; the caller then records it with {@link #recordProblem} in a new transaction.
     */
    public boolean sendIfDue(Long id) {
        LocalDate today = LocalDate.now(clock);
        ScheduledPayment payment = payments.findForUpdate(id).orElse(null);
        if (payment == null || !payment.isDue(today)) {
            return false;
        }
        transfers.transfer(payment.getCustomerId(), payment.getFromAccountId(), payment.getToAccountNumber(),
                payment.getAmount());
        payment.sent(today);
        payments.saveAndFlush(payment);
        events.record("SCHEDULED_PAYMENT_SENT", auditId(payment), Map.of("customerId", payment.getCustomerId(),
                "amount", payment.getAmount()));
        return true;
    }

    /** Notes why the payment couldn't be sent. The customer is told once for each payment day. */
    public void recordProblem(Long id, String why) {
        LocalDate today = LocalDate.now(clock);
        ScheduledPayment payment = payments.findForUpdate(id).orElse(null);
        if (payment == null || !payment.isDue(today)) {
            return;
        }
        LocalDate due = payment.getNextPaymentOn();
        boolean tell = payment.failed(why, today, giveUpAfterDays);
        payments.saveAndFlush(payment);
        if (tell) {
            events.record("SCHEDULED_PAYMENT_MISSED", auditId(payment), Map.of("customerId", payment.getCustomerId(),
                    "amount", payment.getAmount(), "due", due.toString()));
            accounts.findById(payment.getFromAccountId()).ifPresent(account -> {
                String number = account.getAccountNumber();
                Map<String, Object> data = new HashMap<>();
                data.put("customerId", payment.getCustomerId());
                data.put("amount", payment.getAmount());
                data.put("accountEnding", number.substring(number.length() - 4));
                events.record("SCHEDULED_PAYMENT_NOT_SENT", account.getId(), data);
            });
        }
    }

    /** Account and payment ids can be the same number, so the audit log names these "scheduled-<id>". */
    static String auditId(ScheduledPayment payment) {
        return "scheduled-" + payment.getId();
    }
}
