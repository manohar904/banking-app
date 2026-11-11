package com.bank.customer.help;

import com.bank.common.ConflictException;
import com.bank.common.ResourceNotFoundException;
import com.bank.common.events.DomainEvents;
import com.bank.common.security.Callers;
import com.bank.customer.Customer;
import com.bank.customer.CustomerRepository;
import com.bank.customer.help.HelpDtos.HelpRequestDetail;
import com.bank.customer.help.HelpDtos.HelpRequestSummary;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customers ask the bank something; staff answer. A customer only ever sees their own questions:
 * anyone else's answers 404, as if it did not exist. Events and the audit log get ids only, never
 * the text, because events are copied to Kafka and Elasticsearch.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class HelpService {

    /** Enough for real questions, and stops one customer filling the staff's list. */
    static final int MAX_OPEN_PER_CUSTOMER = 5;

    private final HelpRequestRepository requests;
    private final HelpMessageRepository messages;
    private final CustomerRepository customers;
    private final DomainEvents events;
    private final Clock clock = Clock.systemUTC();

    public HelpRequestDetail open(Authentication caller, String subject, String message) {
        Long customerId = Callers.customerIdOf(caller);
        // Locked, so two questions sent at the same moment can't both pass the limit.
        Customer customer = customers.findForUpdate(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer " + customerId + " not found."));
        if (requests.countByCustomerIdAndStatusNot(customerId, HelpStatus.CLOSED) >= MAX_OPEN_PER_CUSTOMER) {
            throw new ConflictException("You already have " + MAX_OPEN_PER_CUSTOMER
                    + " open questions. Please wait for an answer, or close one you no longer need.");
        }
        Instant now = clock.instant();
        HelpRequest request = requests.saveAndFlush(new HelpRequest(customerId, subject.strip(), now));
        messages.saveAndFlush(new HelpMessage(request.getId(), false, userIdOf(caller), message.strip(), now));
        events.record("HELP_REQUEST_OPENED", request.getId(), Map.of("customerId", customerId));
        return detail(caller, request, customer);
    }

    @Transactional(readOnly = true)
    public Page<HelpRequestSummary> list(Authentication caller, Set<HelpStatus> statuses, Pageable pageable) {
        if (!Callers.isStaff(caller)) {
            return requests.findByCustomerId(Callers.customerIdOf(caller), pageable)
                    .map(request -> HelpRequestSummary.from(request, null));
        }
        Page<HelpRequest> page = requests.findByStatusIn(
                statuses.isEmpty() ? EnumSet.allOf(HelpStatus.class) : statuses, pageable);
        Map<Long, Customer> owners = customers.findAllById(page.map(HelpRequest::getCustomerId).toSet()).stream()
                .collect(Collectors.toMap(Customer::getId, Function.identity()));
        return page.map(request -> HelpRequestSummary.from(request, owners.get(request.getCustomerId())));
    }

    @Transactional(readOnly = true)
    public HelpRequestDetail get(Authentication caller, Long id) {
        HelpRequest request = requests.findById(id).filter(found -> mayUse(caller, found))
                .orElseThrow(() -> notFound(id));
        return detail(caller, request, null);
    }

    public HelpRequestDetail reply(Authentication caller, Long id, String message) {
        HelpRequest request = findForUpdate(caller, id);
        if (request.getStatus() == HelpStatus.CLOSED) {
            throw new ConflictException("This question is closed. Please ask a new one.");
        }
        boolean fromBank = Callers.isStaff(caller);
        Instant now = clock.instant();
        messages.saveAndFlush(new HelpMessage(id, fromBank, userIdOf(caller), message.strip(), now));
        request.messageSent(fromBank, now);
        events.record("HELP_MESSAGE_SENT", id, Map.of("fromBank", fromBank));
        return detail(caller, request, null);
    }

    /** Either side may close it. Closing again changes nothing. */
    public HelpRequestDetail close(Authentication caller, Long id) {
        HelpRequest request = findForUpdate(caller, id);
        if (request.getStatus() != HelpStatus.CLOSED) {
            request.close(clock.instant());
            events.record("HELP_REQUEST_CLOSED", id, Map.of());
        }
        return detail(caller, request, null);
    }

    private HelpRequest findForUpdate(Authentication caller, Long id) {
        return requests.findForUpdate(id).filter(found -> mayUse(caller, found)).orElseThrow(() -> notFound(id));
    }

    /** Staff see whose question it is; a customer knows who they are. */
    private HelpRequestDetail detail(Authentication caller, HelpRequest request, Customer owner) {
        if (owner == null && Callers.isStaff(caller)) {
            owner = customers.findById(request.getCustomerId()).orElse(null);
        }
        return HelpRequestDetail.from(request, Callers.isStaff(caller) ? owner : null,
                messages.findByRequestIdOrderById(request.getId()));
    }

    private static boolean mayUse(Authentication caller, HelpRequest request) {
        return Callers.canViewCustomer(caller, request.getCustomerId());
    }

    private static Long userIdOf(Authentication caller) {
        return caller instanceof JwtAuthenticationToken token ? Callers.userIdOf(token.getToken()) : null;
    }

    private static ResourceNotFoundException notFound(Long id) {
        return new ResourceNotFoundException("Question " + id + " not found.");
    }
}
