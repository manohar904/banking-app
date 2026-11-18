package com.bank.account.scheduled;

import com.bank.account.scheduled.ScheduledPaymentDtos.ScheduledPaymentResponse;
import com.bank.account.scheduled.ScheduledPaymentDtos.SetUp;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Monthly payments. ScheduledPaymentService keeps customers to their own. */
@RestController
@RequestMapping("/api/scheduled-payments")
@RequiredArgsConstructor
public class ScheduledPaymentController {

    private final ScheduledPaymentService payments;

    /** A customer gets their own; staff say whose with customerId=. */
    @GetMapping
    public List<ScheduledPaymentResponse> list(Authentication caller, @RequestParam(required = false) Long customerId) {
        return payments.list(caller, customerId);
    }

    @PostMapping
    public ResponseEntity<ScheduledPaymentResponse> setUp(Authentication caller, @Valid @RequestBody SetUp request) {
        ScheduledPaymentResponse created = payments.setUp(caller, request);
        return ResponseEntity.created(URI.create("/api/scheduled-payments/" + created.id())).body(created);
    }

    @PostMapping("/{id}/cancel")
    public ScheduledPaymentResponse cancel(Authentication caller, @PathVariable Long id) {
        return payments.cancel(caller, id);
    }
}
