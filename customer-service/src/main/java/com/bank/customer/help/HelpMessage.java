package com.bank.customer.help;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "help_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // required by JPA, not for application code
public class HelpMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long requestId;

    @Column(nullable = false, updatable = false)
    private boolean fromBank;

    /** Who wrote it, for the bank's own records. Never shown to customers. */
    @Column(updatable = false)
    private Long authorUserId;

    @Column(nullable = false, updatable = false, length = 2000)
    private String body;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    HelpMessage(Long requestId, boolean fromBank, Long authorUserId, String body, Instant now) {
        this.requestId = requestId;
        this.fromBank = fromBank;
        this.authorUserId = authorUserId;
        this.body = body;
        this.createdAt = now;
    }
}
