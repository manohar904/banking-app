package com.bank.account.spending;

import com.bank.account.spending.SpendingAlerts.AlertResponse;
import com.bank.account.spending.SpendingAlerts.SetAlert;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** An account's spending alert. The account's owner and staff may see and change it. */
@RestController
@RequestMapping("/api/accounts/{id}/spending-alert")
@RequiredArgsConstructor
public class SpendingAlertController {

    private final SpendingAlerts spendingAlerts;

    /** The alert and this month's spending against it, or 204 when the account has none. */
    @PreAuthorize("@access.canViewAccount(authentication, #id)")
    @GetMapping
    public ResponseEntity<AlertResponse> get(@PathVariable Long id) {
        return spendingAlerts.find(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PreAuthorize("@access.canViewAccount(authentication, #id)")
    @PutMapping
    public AlertResponse set(@PathVariable Long id, @Valid @RequestBody SetAlert request) {
        return spendingAlerts.set(id, request);
    }

    @PreAuthorize("@access.canViewAccount(authentication, #id)")
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Long id) {
        spendingAlerts.remove(id);
    }
}
