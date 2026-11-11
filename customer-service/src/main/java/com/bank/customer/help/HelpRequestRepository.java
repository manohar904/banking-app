package com.bank.customer.help;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface HelpRequestRepository extends JpaRepository<HelpRequest, Long> {

    /** Locks the row, so two messages sent at the same moment are applied one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from HelpRequest r where r.id = :id")
    Optional<HelpRequest> findForUpdate(Long id);

    long countByCustomerIdAndStatusNot(Long customerId, HelpStatus status);

    Page<HelpRequest> findByCustomerId(Long customerId, Pageable pageable);

    Page<HelpRequest> findByStatusIn(Collection<HelpStatus> statuses, Pageable pageable);
}
