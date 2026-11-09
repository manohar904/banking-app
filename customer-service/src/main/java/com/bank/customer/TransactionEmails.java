package com.bank.customer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tells customers by email when money moves. Works from events, after the money has already moved, so
 * email trouble can never hold up or fail a transaction. Emails wait in a table and are retried, so a
 * mail outage delays them rather than losing them.
 */
@Slf4j
@Component
public class TransactionEmails {

    private static final int BATCH_SIZE = 20;
    private static final int MAX_ATTEMPTS = 10;

    /** Exact decimals: as a double, a large amount would show the wrong cents. */
    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    private record Pending(String eventId, String recipient, String subject, String body, int attempts) { }

    private final JdbcTemplate jdbc;
    private final CustomerRepository customers;
    private final JavaMailSender mail;
    private final TransactionTemplate transactions;
    private final String from;

    TransactionEmails(JdbcTemplate jdbc, CustomerRepository customers, JavaMailSender mail,
            PlatformTransactionManager transactionManager, @Value("${bank.transaction-emails.from}") String from) {
        this.jdbc = jdbc;
        this.customers = customers;
        this.mail = mail;
        this.transactions = new TransactionTemplate(transactionManager);
        this.from = from;
    }

    /** Called for every event on the topic. Most are not about money and are ignored. */
    public void handle(String payload) {
        if (from.isBlank()) {
            return;
        }
        JsonNode event;
        try {
            event = json.readTree(payload);
        } catch (JacksonException e) {
            log.warn("Ignoring an event that could not be read.");
            return;
        }
        String headline = headline(event.path("type").asString(""));
        JsonNode data = event.path("data");
        if (headline == null || !data.has("customerId") || !data.has("accountEnding")) {
            return; // Events from before these fields existed have no one to tell.
        }
        customers.findById(data.path("customerId").asLong())
                .filter(Customer::isTransactionEmailsEnabled)
                .ifPresent(customer -> queue(event.path("eventId").asString(), customer, headline, data));
    }

    private void queue(String eventId, Customer customer, String headline, JsonNode data) {
        String subject = headline.formatted(money(data.path("amount")), data.path("accountEnding").asString());
        String body = """
                Hello %s,

                %s.
                New balance: %s

                If you don't recognise this, please contact the bank straight away.
                """.formatted(customer.getFirstName(), subject, money(data.path("balanceAfter")));
        jdbc.update("""
                insert into transaction_emails (event_id, recipient, subject, body) values (?, ?, ?, ?)
                on conflict (event_id) do nothing
                """, eventId, customer.getEmail(), subject, body);
    }

    private static String headline(String eventType) {
        return switch (eventType) {
            case "MONEY_DEPOSITED" -> "Deposit of %s to your account ending %s";
            case "MONEY_WITHDRAWN" -> "Withdrawal of %s from your account ending %s";
            case "TRANSFER_SENT" -> "Transfer of %s from your account ending %s";
            case "TRANSFER_RECEIVED" -> "Transfer of %s to your account ending %s";
            default -> null;
        };
    }

    private static String money(JsonNode amount) {
        BigDecimal exact = amount.decimalValue().setScale(2, RoundingMode.UNNECESSARY);
        return String.format(Locale.ROOT, "%,.2f", exact);
    }

    /** Waits longer after each failure, about an hour in total, then gives up on that email. */
    @Scheduled(fixedDelayString = "${bank.transaction-emails.send-delay:5000}",
            initialDelayString = "${bank.transaction-emails.send-delay:5000}")
    public void sendPending() {
        transactions.executeWithoutResult(status -> {
            // Skip locked: a second copy of the service takes other rows instead of sending these too.
            List<Pending> batch = jdbc.query("""
                    select event_id, recipient, subject, body, attempts from transaction_emails
                    where sent_at is null and attempts < ? and next_attempt_at <= now()
                    order by created_at limit ? for update skip locked
                    """, (row, number) -> new Pending(row.getString(1), row.getString(2), row.getString(3),
                            row.getString(4), row.getInt(5)), MAX_ATTEMPTS, BATCH_SIZE);
            for (Pending email : batch) {
                if (!send(email)) {
                    break; // Mail is probably down, so leave the rest for later.
                }
            }
            jdbc.update("delete from transaction_emails where created_at < now() - interval '7 days'");
        });
    }

    private boolean send(Pending email) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email.recipient());
        message.setSubject(email.subject());
        message.setText(email.body());
        try {
            mail.send(message);
            jdbc.update("update transaction_emails set sent_at = now() where event_id = ?", email.eventId());
            return true;
        } catch (MailException e) {
            int attempts = email.attempts() + 1;
            log.warn("A transaction email could not be sent (attempt {} of {}): {}", attempts, MAX_ATTEMPTS,
                    e.getClass().getSimpleName());
            jdbc.update("""
                    update transaction_emails set attempts = ?, next_attempt_at = now() + make_interval(mins => ?)
                    where event_id = ?
                    """, attempts, attempts, email.eventId());
            return false;
        }
    }
}
