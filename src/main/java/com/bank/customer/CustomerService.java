package com.bank.customer;

import com.bank.auth.AppUserRepository;
import com.bank.common.ConflictException;
import com.bank.common.ResourceNotFoundException;
import com.bank.customer.dto.CustomerRequest;
import com.bank.customer.dto.CustomerResponse;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final AppUserRepository userRepository;

    public CustomerResponse create(CustomerRequest request) {
        String email = normalizeEmail(request.email());
        if (customerRepository.existsByEmail(email)) {
            throw new ConflictException("A customer with this email already exists.");
        }
        Customer customer = new Customer(request.firstName(), request.lastName(), email);
        return CustomerResponse.from(customerRepository.saveAndFlush(customer));
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
        // The login uses the same email. Flushed so a clash with a staff login is reported as a 409.
        userRepository.findByCustomerId(id).ifPresent(user -> {
            user.changeEmail(email);
            userRepository.saveAndFlush(user);
        });
        return CustomerResponse.from(saved);
    }

    /** A customer who still has accounts is protected by the foreign key and gets a 409. Their login goes too. */
    public void delete(Long id) {
        customerRepository.delete(findCustomer(id));
    }

    private Customer findCustomer(Long id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer " + id + " not found."));
    }

    private static String normalizeEmail(String email) {
        return email.toLowerCase(Locale.ROOT);
    }
}
