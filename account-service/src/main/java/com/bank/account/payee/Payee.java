package com.bank.account.payee;

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
import org.hibernate.annotations.Immutable;

/** An account a customer pays often, saved under a name. Removed and saved again rather than changed. */
@Entity
@Table(name = "payees")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // required by JPA, not for application code
public class Payee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long customerId;

    @Column(nullable = false, updatable = false, length = 40)
    private String name;

    @Column(nullable = false, updatable = false, length = 12)
    private String accountNumber;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    Payee(Long customerId, String name, String accountNumber, Instant createdAt) {
        this.customerId = customerId;
        this.name = name;
        this.accountNumber = accountNumber;
        this.createdAt = createdAt;
    }
}
