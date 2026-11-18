package com.bank.account.payee;

import com.bank.account.payee.PayeeDtos.NewPayee;
import com.bank.account.payee.PayeeDtos.PayeeResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

/** Saved payees. PayeeService keeps customers to their own. */
@RestController
@RequestMapping("/api/payees")
@RequiredArgsConstructor
public class PayeeController {

    private final PayeeService payees;

    /** A customer gets their own; staff say whose with customerId=. */
    @GetMapping
    public List<PayeeResponse> list(Authentication caller, @RequestParam(required = false) Long customerId) {
        return payees.list(caller, customerId);
    }

    @PostMapping
    public ResponseEntity<PayeeResponse> add(Authentication caller, @Valid @RequestBody NewPayee request) {
        PayeeResponse created = payees.add(caller, request);
        return ResponseEntity.created(URI.create("/api/payees/" + created.id())).body(created);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(Authentication caller, @PathVariable Long id) {
        payees.remove(caller, id);
    }
}
