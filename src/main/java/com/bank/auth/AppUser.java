package com.bank.auth;

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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** Someone who can sign in. Only the password hash is ever stored. */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // required by JPA, not for application code
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(unique = true)
    private Long customerId;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    private AppUser(String email, String passwordHash, Role role, Long customerId) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.customerId = customerId;
    }

    public static AppUser staff(String email, String passwordHash) {
        return new AppUser(email, passwordHash, Role.STAFF, null);
    }

    public static AppUser customer(Long customerId, String email, String passwordHash) {
        return new AppUser(email, passwordHash, Role.CUSTOMER, customerId);
    }

    public void changeEmail(String email) {
        this.email = email;
    }

    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }
}
