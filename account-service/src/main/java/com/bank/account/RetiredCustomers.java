package com.bank.account;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Customers being or already deleted. This replaces the foreign key the monolith had: opening an
 * account and retiring a customer take the same lock, so one always sees what the other did, and a
 * customer can never be deleted while an account is being opened for them.
 */
@Repository
@RequiredArgsConstructor
public class RetiredCustomers {

    private final JdbcTemplate jdbc;

    /** Held until the surrounding transaction ends. */
    public void lock(Long customerId) {
        jdbc.query("select pg_advisory_xact_lock(?)", resultSet -> { }, customerId);
    }

    public boolean isRetired(Long customerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from retired_customers where customer_id = ?)", Boolean.class, customerId));
    }

    public void add(Long customerId) {
        jdbc.update("insert into retired_customers (customer_id) values (?) on conflict do nothing", customerId);
    }
}
