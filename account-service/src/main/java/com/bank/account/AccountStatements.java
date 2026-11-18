package com.bank.account;

import com.bank.account.statement.StatementPdf;
import com.bank.account.statement.StatementPdf.Column;
import com.bank.common.ResourceNotFoundException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * An account's statement for one calendar month, as a PDF: the balance at the start, every entry in the
 * month, and the balance at the end. Months start at midnight in the bank's time zone, the same one the
 * daily transfer limit uses. The month still going shows everything so far.
 */
@Service
@Transactional(readOnly = true)
public class AccountStatements {

    private static final Set<TransactionType> MONEY_OUT = EnumSet.of(TransactionType.WITHDRAWAL,
            TransactionType.TRANSFER_OUT, TransactionType.LOAN_REPAYMENT, TransactionType.CARD_PAYMENT);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ROOT);

    /** A statement as a file. */
    public record File(String name, byte[] content) {
    }

    private final AccountRepository accounts;
    private final AccountTransactionRepository ledger;
    private final CustomerServiceClient customerService;
    private final Clock clock;

    AccountStatements(AccountRepository accounts, AccountTransactionRepository ledger,
            CustomerServiceClient customerService, @Value("${bank.transfers.time-zone}") ZoneId timeZone) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.customerService = customerService;
        this.clock = Clock.system(timeZone);
    }

    /**
     * Who may see the account is checked by the caller. No month means this one. A month before the
     * account was opened or still to come is a 400.
     */
    public File forMonth(Long accountId, YearMonth asked) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account " + accountId + " not found."));
        ZoneId zone = clock.getZone();
        YearMonth now = YearMonth.now(clock);
        YearMonth month = asked == null ? now : asked;
        YearMonth opened = YearMonth.from(account.getCreatedAt().atZone(zone));
        if (month.isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "There is no statement for a month still to come.");
        }
        if (month.isBefore(opened)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The account was opened in " + MONTH.format(opened) + ".");
        }

        Instant from = month.atDay(1).atStartOfDay(zone).toInstant();
        Instant until = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        BigDecimal opening = ledger.findFirstByAccountIdAndCreatedAtLessThanOrderByIdDesc(accountId, from)
                .map(AccountTransaction::getBalanceAfter).orElse(BigDecimal.ZERO.setScale(2));
        List<AccountTransaction> entries =
                ledger.findByAccountIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderById(accountId, from, until);

        BigDecimal in = BigDecimal.ZERO.setScale(2);
        BigDecimal out = BigDecimal.ZERO.setScale(2);
        BigDecimal closing = opening;
        boolean soFar = month.equals(now);
        LocalDate lastDay = soFar ? LocalDate.now(clock) : month.atEndOfMonth();

        StatementPdf pdf = new StatementPdf("Account statement");
        customerService.nameOf(account.getCustomerId()).ifPresent(name -> pdf.detail("Name", name));
        pdf.detail("Account", account.getAccountNumber() + " (" + typeName(account.getType()) + ")")
                .detail("Period", StatementPdf.day(month.atDay(1)) + " to " + StatementPdf.day(lastDay)
                        + (soFar ? " (month so far)" : ""))
                .detail("Made on", StatementPdf.day(LocalDate.now(clock)))
                .table("Transactions", "No money moved in or out of the account this month.",
                        new Column("Date", 1.2f, false), new Column("Description", 3f, false),
                        new Column("Money out", 1.3f, true), new Column("Money in", 1.3f, true),
                        new Column("Balance", 1.4f, true));
        for (AccountTransaction entry : entries) {
            boolean isOut = MONEY_OUT.contains(entry.getType());
            if (isOut) {
                out = out.add(entry.getAmount());
            } else {
                in = in.add(entry.getAmount());
            }
            closing = entry.getBalanceAfter();
            pdf.row(StatementPdf.day(LocalDate.ofInstant(entry.getCreatedAt(), zone)), describe(entry),
                    isOut ? StatementPdf.money(entry.getAmount()) : "",
                    isOut ? "" : StatementPdf.money(entry.getAmount()),
                    StatementPdf.money(entry.getBalanceAfter()));
        }
        pdf.summary("Balance at the start", StatementPdf.money(opening))
                .summary("Money in", StatementPdf.money(in))
                .summary("Money out", StatementPdf.money(out))
                .summary(soFar ? "Balance now" : "Balance at the end", StatementPdf.money(closing))
                .footnote("If anything here looks wrong, please contact the bank through Help.");
        return new File("statement-" + account.getAccountNumber() + "-" + month + ".pdf", pdf.toBytes());
    }

    /** One calendar month's money in and out, and the balance at its end (or now, for this month). */
    public record MonthSummary(String month, BigDecimal moneyIn, BigDecimal moneyOut, BigDecimal endBalance) {
    }

    /** Most months a summary can cover. */
    public static final int MAX_SUMMARY_MONTHS = 24;

    /**
     * Money in and out for each of the last few months, oldest first, ending with this one. Months before
     * the account was opened are left out. Who may see the account is checked by the caller.
     */
    public List<MonthSummary> summary(Long accountId, int months) {
        if (months < 1 || months > MAX_SUMMARY_MONTHS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ask for between 1 and " + MAX_SUMMARY_MONTHS + " months.");
        }
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account " + accountId + " not found."));
        ZoneId zone = clock.getZone();
        YearMonth now = YearMonth.now(clock);
        YearMonth opened = YearMonth.from(account.getCreatedAt().atZone(zone));
        YearMonth first = now.minusMonths(months - 1L);
        if (first.isBefore(opened)) {
            first = opened;
        }
        Instant from = first.atDay(1).atStartOfDay(zone).toInstant();
        BigDecimal balance = ledger.findFirstByAccountIdAndCreatedAtLessThanOrderByIdDesc(accountId, from)
                .map(AccountTransaction::getBalanceAfter).orElse(BigDecimal.ZERO.setScale(2));
        List<AccountTransaction> entries = ledger.findByAccountIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderById(
                accountId, from, now.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant());

        List<MonthSummary> result = new ArrayList<>();
        int next = 0;
        for (YearMonth month = first; !month.isAfter(now); month = month.plusMonths(1)) {
            Instant end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
            BigDecimal in = BigDecimal.ZERO.setScale(2);
            BigDecimal out = BigDecimal.ZERO.setScale(2);
            while (next < entries.size() && entries.get(next).getCreatedAt().isBefore(end)) {
                AccountTransaction entry = entries.get(next++);
                if (MONEY_OUT.contains(entry.getType())) {
                    out = out.add(entry.getAmount());
                } else {
                    in = in.add(entry.getAmount());
                }
                balance = entry.getBalanceAfter();
            }
            result.add(new MonthSummary(month.toString(), in, out, balance));
        }
        return result;
    }

    private static String describe(AccountTransaction entry) {
        return switch (entry.getType()) {
            case DEPOSIT -> "Deposit";
            case WITHDRAWAL -> "Withdrawal";
            case TRANSFER_OUT -> "Transfer to " + entry.getCounterpartyAccountNumber();
            case TRANSFER_IN -> "Transfer from " + entry.getCounterpartyAccountNumber();
            case LOAN_PAYOUT -> "Loan paid out";
            case LOAN_REPAYMENT -> "Loan repayment";
            case CARD_PAYMENT -> "Credit card payment";
            case INTEREST -> "Interest";
        };
    }

    private static String typeName(AccountType type) {
        return switch (type) {
            case CHECKING -> "checking account";
            case SAVINGS -> "savings account";
        };
    }
}
