package com.bank.account.savings;

import com.bank.account.savings.SavingsGoals.GoalResponse;
import com.bank.account.savings.SavingsGoals.SetGoal;
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

/** A savings account's goal. The account's owner and staff may see and change it. */
@RestController
@RequestMapping("/api/accounts/{id}/goal")
@RequiredArgsConstructor
public class SavingsGoalController {

    private final SavingsGoals savingsGoals;

    /** The goal and its progress, or 204 when the account has none. */
    @PreAuthorize("@access.canViewAccount(authentication, #id)")
    @GetMapping
    public ResponseEntity<GoalResponse> get(@PathVariable Long id) {
        return savingsGoals.find(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PreAuthorize("@access.canViewAccount(authentication, #id)")
    @PutMapping
    public GoalResponse set(@PathVariable Long id, @Valid @RequestBody SetGoal request) {
        return savingsGoals.set(id, request);
    }

    @PreAuthorize("@access.canViewAccount(authentication, #id)")
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Long id) {
        savingsGoals.remove(id);
    }
}
