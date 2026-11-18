package com.bank.account.payee;

import com.bank.account.AccountRepository;
import com.bank.account.CustomerServiceClient;
import com.bank.account.payee.PayeeDtos.NewPayee;
import com.bank.account.payee.PayeeDtos.PayeeResponse;
import com.bank.common.ConflictException;
import com.bank.common.ResourceNotFoundException;
import com.bank.common.events.DomainEvents;
import com.bank.common.security.Callers;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * A customer's saved payees. The account number must belong to an account when it is saved, which
 * catches a mistyped number early. Staff can manage them for a customer too.
 *
 * <p>A customer only ever sees their own: anyone else's answers 404, as if it did not exist.
 */
@Service
@Transactional
public class PayeeService {

    static final int MAX_PER_CUSTOMER = 50;

    private final PayeeRepository payees;
    private final AccountRepository accounts;
    private final CustomerServiceClient customerService;
    private final DomainEvents events;
    private final Clock clock = Clock.systemUTC();

    PayeeService(PayeeRepository payees, AccountRepository accounts, CustomerServiceClient customerService,
            DomainEvents events) {
        this.payees = payees;
        this.accounts = accounts;
        this.customerService = customerService;
        this.events = events;
    }

    /** A customer gets their own; staff one customer's. Sorted by name. */
    @Transactional(readOnly = true)
    public List<PayeeResponse> list(Authentication caller, Long customerId) {
        return payees.findByCustomerIdOrderByNameAsc(owner(caller, customerId)).stream()
                .map(PayeeResponse::from).toList();
    }

    public PayeeResponse add(Authentication caller, NewPayee request) {
        Long customerId = owner(caller, request.customerId());
        if (Callers.isStaff(caller)) {
            customerService.requireExists(customerId);
        }
        String name = request.name().strip();
        String number = request.accountNumber();
        if (accounts.findByAccountNumber(number).isEmpty()) {
            throw new ResourceNotFoundException("No account has the number " + number + ". Check it and try again.");
        }
        if (payees.existsByCustomerIdAndNameIgnoreCase(customerId, name)) {
            throw new ConflictException("You already have a payee called " + name + ".");
        }
        if (payees.existsByCustomerIdAndAccountNumber(customerId, number)) {
            throw new ConflictException("This account number is already saved as a payee.");
        }
        if (payees.countByCustomerId(customerId) >= MAX_PER_CUSTOMER) {
            throw new ConflictException("A customer can have at most " + MAX_PER_CUSTOMER + " payees. Remove one first.");
        }
        Payee saved = payees.saveAndFlush(new Payee(customerId, name, number, clock.instant()));
        // The name stays out of events, like other free text.
        events.record("PAYEE_ADDED", auditId(saved), Map.of("customerId", customerId));
        return PayeeResponse.from(saved);
    }

    public void remove(Authentication caller, Long id) {
        Payee payee = payees.findById(id)
                .filter(found -> Callers.canViewCustomer(caller, found.getCustomerId()))
                .orElseThrow(() -> new ResourceNotFoundException("Payee " + id + " not found."));
        payees.delete(payee);
        events.record("PAYEE_REMOVED", auditId(payee), Map.of("customerId", payee.getCustomerId()));
    }

    private static Long owner(Authentication caller, Long customerId) {
        Long owner = Callers.isStaff(caller) ? customerId : Callers.customerIdOf(caller);
        if (owner == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say whose payees: customerId.");
        }
        return owner;
    }

    /** Payee and account ids can be the same number, so the audit log names these "payee-<id>". */
    private static String auditId(Payee payee) {
        return "payee-" + payee.getId();
    }
}
