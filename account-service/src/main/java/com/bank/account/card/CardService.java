package com.bank.account.card;

import com.bank.account.Account;
import com.bank.account.AccountRepository;
import com.bank.account.AccountTransaction;
import com.bank.account.AccountTransactionRepository;
import com.bank.account.CustomerServiceClient;
import com.bank.account.RetiredCustomers;
import com.bank.account.card.CardDtos.CardResponse;
import com.bank.account.card.CardDtos.CardTransactionResponse;
import com.bank.common.ConflictException;
import com.bank.common.ResourceNotFoundException;
import com.bank.common.events.DomainEvents;
import com.bank.common.security.Callers;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Credit cards. Staff issue them, record purchases, change limits and close them; the customer and
 * staff can freeze a card and pay what is owed from one of the customer's accounts. A payment changes
 * the card and the account's balance in one database transaction, so they always agree.
 *
 * <p>A customer only ever sees their own cards: anyone else's answers 404, as if it did not exist.
 */
@Service
@Transactional
public class CardService {

    /** Cards a customer may have open (active or frozen) at once. */
    static final int MAX_OPEN_PER_CUSTOMER = 3;
    /** Cards are valid until the end of the month this many years after they are issued. */
    private static final int YEARS_VALID = 4;

    private final CardRepository cards;
    private final CardTransactionRepository cardLedger;
    private final AccountRepository accounts;
    private final AccountTransactionRepository accountLedger;
    private final CustomerServiceClient customerService;
    /** The per-customer lock that opening an account takes. */
    private final RetiredCustomers retiredCustomers;
    private final DomainEvents events;
    private final BigDecimal maxLimit;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    CardService(CardRepository cards, CardTransactionRepository cardLedger, AccountRepository accounts,
            AccountTransactionRepository accountLedger, CustomerServiceClient customerService,
            RetiredCustomers retiredCustomers, DomainEvents events,
            @Value("${bank.cards.max-limit}") BigDecimal maxLimit,
            @Value("${bank.transfers.time-zone}") ZoneId timeZone) {
        this.cards = cards;
        this.cardLedger = cardLedger;
        this.accounts = accounts;
        this.accountLedger = accountLedger;
        this.customerService = customerService;
        this.retiredCustomers = retiredCustomers;
        this.events = events;
        this.maxLimit = maxLimit;
        this.clock = Clock.system(timeZone);
    }

    /** Staff only. */
    public CardResponse issue(Long customerId, BigDecimal creditLimit) {
        requireAllowedLimit(creditLimit);
        customerService.requireExists(customerId);
        // A customer being deleted is retired first, so this can't leave a card without an owner.
        retiredCustomers.lock(customerId);
        if (retiredCustomers.isRetired(customerId)) {
            throw new ResourceNotFoundException("Customer " + customerId + " not found.");
        }
        if (cards.countByCustomerIdAndStatusNot(customerId, CardStatus.CLOSED) >= MAX_OPEN_PER_CUSTOMER) {
            throw new ConflictException("A customer can have at most " + MAX_OPEN_PER_CUSTOMER + " cards.");
        }
        LocalDate expiresOn = YearMonth.now(clock).plusYears(YEARS_VALID).atEndOfMonth();
        String lastFour = String.format(Locale.ROOT, "%04d", random.nextInt(10_000));
        Card card = cards.saveAndFlush(new Card(customerId, lastFour, expiresOn,
                creditLimit.setScale(2), clock.instant()));
        events.record("CARD_ISSUED", auditId(card), Map.of("customerId", customerId, "creditLimit",
                card.getCreditLimit()));
        return CardResponse.from(card);
    }

    /** A customer gets their own; staff one customer's. */
    @Transactional(readOnly = true)
    public List<CardResponse> list(Authentication caller, Long customerId) {
        Long owner = Callers.isStaff(caller) ? customerId : Callers.customerIdOf(caller);
        if (owner == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say whose cards: customerId=.");
        }
        return cards.findByCustomerIdOrderById(owner).stream().map(CardResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public CardResponse get(Authentication caller, Long id) {
        return CardResponse.from(find(caller, id));
    }

    /** Newest first. */
    @Transactional(readOnly = true)
    public Page<CardTransactionResponse> transactions(Authentication caller, Long id, Pageable pageable) {
        find(caller, id);
        Page<CardTransaction> page = cardLedger.findByCardId(id, pageable);
        Map<Long, String> numbers = accounts.findAllById(page.map(CardTransaction::getAccountId)
                        .filter(accountId -> accountId != null).toSet()).stream()
                .collect(Collectors.toMap(Account::getId, Account::getAccountNumber));
        return page.map(entry -> new CardTransactionResponse(entry.getId(), entry.getType(), entry.getAmount(),
                entry.getOwedAfter(), entry.getMerchant(),
                entry.getAccountId() == null ? null : numbers.get(entry.getAccountId()), entry.getCreatedAt()));
    }

    /** Staff only, standing in for the shops a real card would be used at. */
    public CardResponse purchase(Long id, BigDecimal amount, String merchant) {
        Card card = cards.findForUpdate(id).orElseThrow(() -> notFound(id));
        card.purchase(amount, LocalDate.now(clock));
        Card saved = cards.saveAndFlush(card);
        cardLedger.save(CardTransaction.purchase(saved, amount, merchant.strip(), clock.instant()));
        // accountEnding is the card's last four digits, which the transaction email shows. The shop's
        // name stays out of events, like other free text.
        events.record("CARD_PURCHASE", auditId(saved), Map.of("customerId", saved.getCustomerId(),
                "amount", amount, "owedAfter", saved.getOwed(), "accountEnding", saved.getLastFour()));
        return CardResponse.from(saved);
    }

    /** Pays the card from one of its owner's accounts. Not enough money there is a 422, and nothing changes. */
    public CardResponse pay(Authentication caller, Long id, Long fromAccountId, BigDecimal amount) {
        Card card = findForUpdate(caller, id);
        Account account = accounts.findById(fromAccountId)
                .filter(found -> found.getCustomerId().equals(card.getCustomerId()))
                .orElseThrow(() -> new ResourceNotFoundException("Account " + fromAccountId + " not found."));
        card.payment(amount);
        account.withdraw(amount);
        Account savedAccount = accounts.saveAndFlush(account);
        accountLedger.save(AccountTransaction.forCardPayment(savedAccount, amount, card.getId()));
        Card saved = cards.saveAndFlush(card);
        cardLedger.save(CardTransaction.payment(saved, amount, fromAccountId, clock.instant()));
        events.record("CARD_PAID", auditId(saved), Map.of("customerId", saved.getCustomerId(), "amount", amount,
                "owedAfter", saved.getOwed(), "accountId", fromAccountId));
        String number = savedAccount.getAccountNumber();
        events.record("CARD_PAYMENT_TAKEN", savedAccount.getId(), Map.of("amount", amount,
                "balanceAfter", savedAccount.getBalance(), "cardId", saved.getId(),
                "customerId", saved.getCustomerId(), "accountEnding", number.substring(number.length() - 4)));
        return CardResponse.from(saved);
    }

    public CardResponse freeze(Authentication caller, Long id) {
        Card card = findForUpdate(caller, id);
        card.freeze();
        return save(card, "CARD_FROZEN");
    }

    public CardResponse unfreeze(Authentication caller, Long id) {
        Card card = findForUpdate(caller, id);
        card.unfreeze();
        return save(card, "CARD_UNFROZEN");
    }

    /** Staff only. */
    public CardResponse changeLimit(Long id, BigDecimal creditLimit) {
        requireAllowedLimit(creditLimit);
        Card card = cards.findForUpdate(id).orElseThrow(() -> notFound(id));
        card.changeLimit(creditLimit.setScale(2));
        Card saved = cards.saveAndFlush(card);
        events.record("CARD_LIMIT_CHANGED", auditId(saved), Map.of("customerId", saved.getCustomerId(),
                "creditLimit", saved.getCreditLimit()));
        return CardResponse.from(saved);
    }

    /** Staff only, once nothing is owed. */
    public CardResponse close(Long id) {
        Card card = cards.findForUpdate(id).orElseThrow(() -> notFound(id));
        card.close(clock.instant());
        return save(card, "CARD_CLOSED");
    }

    private CardResponse save(Card card, String event) {
        Card saved = cards.saveAndFlush(card);
        events.record(event, auditId(saved), Map.of("customerId", saved.getCustomerId()));
        return CardResponse.from(saved);
    }

    private void requireAllowedLimit(BigDecimal creditLimit) {
        if (creditLimit.compareTo(maxLimit) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A card's limit can be at most " + maxLimit.toPlainString() + ".");
        }
    }

    private Card find(Authentication caller, Long id) {
        return cards.findById(id).filter(found -> mayUse(caller, found)).orElseThrow(() -> notFound(id));
    }

    private Card findForUpdate(Authentication caller, Long id) {
        return cards.findForUpdate(id).filter(found -> mayUse(caller, found)).orElseThrow(() -> notFound(id));
    }

    private static boolean mayUse(Authentication caller, Card card) {
        return Callers.canViewCustomer(caller, card.getCustomerId());
    }

    /** Card and account ids can be the same number, so the audit log and events name cards "card-<id>". */
    static String auditId(Card card) {
        return "card-" + card.getId();
    }

    private static ResourceNotFoundException notFound(Long id) {
        return new ResourceNotFoundException("Card " + id + " not found.");
    }
}
