package com.bank.account;

import com.bank.account.card.CardRepository;
import com.bank.account.card.CardStatus;
import com.bank.account.dto.AccountResponse;
import com.bank.account.dto.CreateAccountRequest;
import com.bank.account.dto.TransactionResponse;
import com.bank.account.loan.LoanRepository;
import com.bank.account.loan.LoanStatus;
import com.bank.common.ConflictException;
import com.bank.common.ResourceNotFoundException;
import com.bank.common.events.DomainEvents;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional
public class AccountService {

    private static final long ACCOUNT_NUMBER_BOUND = 1_000_000_000_000L; // 12 digits

    private final AccountRepository accountRepository;
    private final AccountTransactionRepository transactionRepository;
    private final RetiredCustomers retiredCustomers;
    private final CustomerServiceClient customerService;
    private final DomainEvents events;
    private final LoanRepository loanRepository;
    private final CardRepository cardRepository;
    private final SecureRandom random = new SecureRandom();

    public AccountResponse create(CreateAccountRequest request) {
        Long customerId = request.customerId();
        customerService.requireExists(customerId);
        // A customer being deleted is retired first, so this cannot leave an account without an owner.
        retiredCustomers.lock(customerId);
        if (retiredCustomers.isRetired(customerId)) {
            throw new ResourceNotFoundException("Customer " + customerId + " not found.");
        }
        Account account = new Account(customerId, generateAccountNumber(), request.type());
        Account saved = accountRepository.saveAndFlush(account);
        events.record("ACCOUNT_OPENED", saved.getId(), Map.of("customerId", customerId, "type", saved.getType().name()));
        return AccountResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public AccountResponse get(Long id) {
        return AccountResponse.from(findAccount(id));
    }

    @Transactional(readOnly = true)
    public AccountResponse getByNumber(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
                .map(AccountResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("No account has the number " + accountNumber + "."));
    }

    @Transactional(readOnly = true)
    public Page<AccountResponse> list(Long customerId, Pageable pageable) {
        Page<Account> accounts = customerId == null
                ? accountRepository.findAll(pageable)
                : accountRepository.findByCustomerId(customerId, pageable);
        return accounts.map(AccountResponse::from);
    }

    public AccountResponse deposit(Long id, BigDecimal amount) {
        Account account = findAccount(id);
        account.deposit(amount);
        return saveWithLedgerEntry(account, TransactionType.DEPOSIT, amount);
    }

    public AccountResponse withdraw(Long id, BigDecimal amount) {
        Account account = findAccount(id);
        account.withdraw(amount);
        return saveWithLedgerEntry(account, TransactionType.WITHDRAWAL, amount);
    }

    /**
     * Both sides share one database transaction, so money is never taken without arriving.
     *
     * <p>The lower account id is always updated first. Otherwise A to B and B to A at the same moment
     * could each hold the row the other needs and deadlock; this way the later one gets a clean 409.
     * Each side is changed only just before it is flushed, so that a flush writes only that side.
     */
    public AccountResponse transfer(Long id, String toAccountNumber, BigDecimal amount) {
        return transfer(id, toAccountNumber, amount, false);
    }

    /** byCustomer marks a transfer the customer sent themselves, which counts towards their daily limit. */
    AccountResponse transfer(Long id, String toAccountNumber, BigDecimal amount, boolean byCustomer) {
        Account from = findAccount(id);
        Account to = accountRepository.findByAccountNumber(toAccountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("No account has the number " + toAccountNumber + "."));
        if (from.getId().equals(to.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An account cannot transfer to itself.");
        }
        UUID transferId = UUID.randomUUID();
        if (from.getId() < to.getId()) {
            sendTransfer(from, to, amount, transferId, byCustomer);
            receiveTransfer(to, from, amount, transferId, byCustomer);
        } else {
            receiveTransfer(to, from, amount, transferId, byCustomer);
            sendTransfer(from, to, amount, transferId, byCustomer);
        }
        return AccountResponse.from(from);
    }

    private void sendTransfer(Account from, Account to, BigDecimal amount, UUID transferId, boolean byCustomer) {
        from.withdraw(amount);
        saveTransferSide(from, to, TransactionType.TRANSFER_OUT, "TRANSFER_SENT", amount, transferId, byCustomer);
    }

    private void receiveTransfer(Account to, Account from, BigDecimal amount, UUID transferId,
            boolean byCustomer) {
        to.deposit(amount);
        saveTransferSide(to, from, TransactionType.TRANSFER_IN, "TRANSFER_RECEIVED", amount, transferId, byCustomer);
    }

    private void saveTransferSide(Account account, Account other, TransactionType type, String event,
            BigDecimal amount, UUID transferId, boolean byCustomer) {
        Account saved = accountRepository.saveAndFlush(account);
        transactionRepository.save(
                new AccountTransaction(saved, type, amount, transferId, other.getAccountNumber(), byCustomer));
        events.record(event, saved.getId(), Map.of("amount", amount, "balanceAfter", saved.getBalance(),
                "transferId", transferId.toString(), "otherAccountId", other.getId(),
                "customerId", saved.getCustomerId(), "accountEnding", accountEnding(saved)));
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> listTransactions(Long id, Pageable pageable) {
        findAccount(id); // an unknown account is a 404, not an empty history
        return transactionRepository.findByAccountId(id, pageable).map(TransactionResponse::from);
    }

    public void delete(Long id) {
        Account account = findAccount(id);
        if (!account.hasZeroBalance()) {
            throw new ConflictException("An account can only be deleted when its balance is zero.");
        }
        if (transactionRepository.existsByAccountId(id)) {
            throw new ConflictException("An account with transaction history cannot be deleted.");
        }
        if (loanRepository.existsByAccountIdAndStatusIn(id, EnumSet.of(LoanStatus.REQUESTED, LoanStatus.ACTIVE))) {
            throw new ConflictException("This account has a loan request waiting. Cancel or reject it first.");
        }
        accountRepository.delete(account);
        events.record("ACCOUNT_CLOSED", id, Map.of("customerId", account.getCustomerId()));
    }

    /** Part of deleting a customer: from here on they cannot get new accounts. Safe to repeat. */
    public void retireCustomer(Long customerId) {
        retiredCustomers.lock(customerId);
        if (accountRepository.existsByCustomerId(customerId)) {
            throw new ConflictException("Customer " + customerId + " still has accounts.");
        }
        if (cardRepository.existsByCustomerIdAndStatusNot(customerId, CardStatus.CLOSED)) {
            throw new ConflictException("Customer " + customerId + " still has an open credit card.");
        }
        retiredCustomers.add(customerId);
    }

    /**
     * Flushes the balance first: if the optimistic lock or a constraint fails, no ledger entry is
     * written. Both writes share one database transaction, so they commit or roll back together.
     */
    private AccountResponse saveWithLedgerEntry(Account account, TransactionType type, BigDecimal amount) {
        Account saved = accountRepository.saveAndFlush(account);
        transactionRepository.save(new AccountTransaction(saved, type, amount));
        events.record(type == TransactionType.DEPOSIT ? "MONEY_DEPOSITED" : "MONEY_WITHDRAWN", saved.getId(),
                Map.of("amount", amount, "balanceAfter", saved.getBalance(),
                        "customerId", saved.getCustomerId(), "accountEnding", accountEnding(saved)));
        return AccountResponse.from(saved);
    }

    /** Last four digits only, because events are copied to Kafka and Elasticsearch. */
    private static String accountEnding(Account account) {
        String number = account.getAccountNumber();
        return number.substring(number.length() - 4);
    }

    private Account findAccount(Long id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account " + id + " not found."));
    }

    /** A collision is very unlikely. If one happens, the unique constraint rejects it with a 409. */
    private String generateAccountNumber() {
        return String.format(Locale.ROOT, "%012d", random.nextLong(ACCOUNT_NUMBER_BOUND));
    }
}
