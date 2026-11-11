package com.bank.customer.help;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface HelpMessageRepository extends JpaRepository<HelpMessage, Long> {

    List<HelpMessage> findByRequestIdOrderById(Long requestId);
}
