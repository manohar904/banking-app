package com.bank.account.card;

import com.bank.account.card.CardDtos.CardResponse;
import com.bank.account.card.CardDtos.CardTransactionResponse;
import com.bank.account.card.CardDtos.IssueCard;
import com.bank.account.card.CardDtos.NewLimit;
import com.bank.account.card.CardDtos.Payment;
import com.bank.account.card.CardDtos.Purchase;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Credit cards. Who may call what is in SecurityConfig; CardService keeps customers to their own cards. */
@RestController
@RequestMapping("/api/cards")
@RequiredArgsConstructor
public class CardController {

    private final CardService cards;

    /** Staff only. */
    @PostMapping
    public ResponseEntity<CardResponse> issue(@Valid @RequestBody IssueCard request) {
        CardResponse created = cards.issue(request.customerId(), request.creditLimit());
        return ResponseEntity.created(URI.create("/api/cards/" + created.id())).body(created);
    }

    /** A customer gets their own; staff say whose with customerId=. */
    @GetMapping
    public List<CardResponse> list(Authentication caller, @RequestParam(required = false) Long customerId) {
        return cards.list(caller, customerId);
    }

    @GetMapping("/{id}")
    public CardResponse get(Authentication caller, @PathVariable Long id) {
        return cards.get(caller, id);
    }

    /** Newest first. */
    @GetMapping("/{id}/transactions")
    public PagedModel<CardTransactionResponse> transactions(Authentication caller, @PathVariable Long id,
            @PageableDefault(sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return new PagedModel<>(cards.transactions(caller, id, pageable));
    }

    /** Staff only. */
    @PostMapping("/{id}/purchases")
    public CardResponse purchase(@PathVariable Long id, @Valid @RequestBody Purchase purchase) {
        return cards.purchase(id, purchase.amount(), purchase.merchant());
    }

    @PostMapping("/{id}/payments")
    public CardResponse pay(Authentication caller, @PathVariable Long id, @Valid @RequestBody Payment payment) {
        return cards.pay(caller, id, payment.fromAccountId(), payment.amount());
    }

    @PostMapping("/{id}/freeze")
    public CardResponse freeze(Authentication caller, @PathVariable Long id) {
        return cards.freeze(caller, id);
    }

    @PostMapping("/{id}/unfreeze")
    public CardResponse unfreeze(Authentication caller, @PathVariable Long id) {
        return cards.unfreeze(caller, id);
    }

    /** Staff only. */
    @PutMapping("/{id}/limit")
    public CardResponse changeLimit(@PathVariable Long id, @Valid @RequestBody NewLimit limit) {
        return cards.changeLimit(id, limit.creditLimit());
    }

    /** Staff only. */
    @PostMapping("/{id}/close")
    public CardResponse close(@PathVariable Long id) {
        return cards.close(id);
    }
}
