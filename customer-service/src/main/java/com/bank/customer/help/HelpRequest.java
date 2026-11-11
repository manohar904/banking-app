package com.bank.customer.help;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "help_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // required by JPA, not for application code
public class HelpRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long customerId;

    @Column(nullable = false, length = 120, updatable = false)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private HelpStatus status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /** When the last message was sent or the request was closed. Lists are sorted by it. */
    @Column(nullable = false)
    private Instant updatedAt;

    HelpRequest(Long customerId, String subject, Instant now) {
        this.customerId = customerId;
        this.subject = subject;
        this.status = HelpStatus.WAITING_FOR_BANK;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void messageSent(boolean fromBank, Instant now) {
        this.status = fromBank ? HelpStatus.ANSWERED : HelpStatus.WAITING_FOR_BANK;
        this.updatedAt = now;
    }

    void close(Instant now) {
        this.status = HelpStatus.CLOSED;
        this.updatedAt = now;
    }
}
