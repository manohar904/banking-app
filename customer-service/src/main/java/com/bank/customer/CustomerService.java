package com.bank.customer;

import com.bank.common.ConflictException;
import com.bank.common.ResourceNotFoundException;
import com.bank.common.events.DomainEvents;
import com.bank.customer.dto.CustomerRequest;
import com.bank.customer.dto.CustomerResponse;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@Transactional
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final AccountServiceClient accountService;
    private final AuthServiceClient authService;
    private final DomainEvents events;
    private final TransactionTemplate transactions;

    public CustomerResponse create(CustomerRequest request) {
        String email = normalizeEmail(request.email());
        if (customerRepository.existsByEmail(email)) {
            throw new ConflictException("A customer with this email already exists.");
        }
        Customer customer = new Customer(request.firstName(), request.lastName(), email);
        Customer saved = customerRepository.saveAndFlush(customer);
        // Ids only: names and emails stay out of Kafka and Elasticsearch.
        events.record("CUSTOMER_CREATED", saved.getId(), Map.of());
        return CustomerResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public CustomerResponse get(Long id) {
        return CustomerResponse.from(findCustomer(id));
    }

    @Transactional(readOnly = true)
    public Page<CustomerResponse> list(Pageable pageable) {
        return customerRepository.findAll(pageable).map(CustomerResponse::from);
    }

    public CustomerResponse update(Long id, CustomerRequest request) {
        Customer customer = findCustomer(id);
        String email = normalizeEmail(request.email());
        if (customerRepository.existsByEmailAndIdNot(email, id)) {
            throw new ConflictException("A customer with this email already exists.");
        }
        customer.update(request.firstName(), request.lastName(), email);
        Customer saved = customerRepository.saveAndFlush(customer);
        // The login uses the same email, and lives in another service. Called after the flush, so
        // this row stays locked and a clash with a staff login rolls this update back as a 409.
        // Called even if the email is unchanged, so saving again repairs a login left out of step.
        authService.changeLoginEmail(id, email);
        events.record("CUSTOMER_UPDATED", id, Map.of());
        return CustomerResponse.from(saved);
    }

    /**
     * The steps span three databases, so they cannot share a transaction. Each is safe to repeat and
     * the customer's row goes last, so after a failure the same delete can simply be tried again.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void delete(Long id) {
        findCustomer(id);
        // Answers 409 if the customer still has accounts, and blocks new ones from here on.
        accountService.retireCustomer(id);
        authService.deleteLogin(id);
        transactions.executeWithoutResult(status -> {
            customerRepository.deleteById(id);
            events.record("CUSTOMER_DELETED", id, Map.of());
        });
    }

    private Customer findCustomer(Long id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer " + id + " not found."));
    }

    private static String normalizeEmail(String email) {
        return email.toLowerCase(Locale.ROOT);
    }
}
