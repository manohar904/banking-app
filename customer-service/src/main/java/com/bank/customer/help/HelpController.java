package com.bank.customer.help;

import com.bank.customer.help.HelpDtos.HelpReply;
import com.bank.customer.help.HelpDtos.HelpRequestDetail;
import com.bank.customer.help.HelpDtos.HelpRequestSummary;
import com.bank.customer.help.HelpDtos.NewHelpRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Help: questions from customers and the bank's answers. Who may call what is in SecurityConfig. */
@RestController
@RequestMapping("/api/help-requests")
@RequiredArgsConstructor
public class HelpController {

    private final HelpService help;

    /** Customers only. */
    @PostMapping
    public ResponseEntity<HelpRequestDetail> open(Authentication caller, @Valid @RequestBody NewHelpRequest request) {
        HelpRequestDetail created = help.open(caller, request.subject(), request.message());
        return ResponseEntity.created(URI.create("/api/help-requests/" + created.id())).body(created);
    }

    /** A customer gets their own; staff get everyone's, optionally only some statuses. Newest activity first. */
    @GetMapping
    public PagedModel<HelpRequestSummary> list(Authentication caller,
            @RequestParam(name = "status", required = false) Set<HelpStatus> statuses,
            @PageableDefault(sort = "updatedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return new PagedModel<>(help.list(caller, statuses == null ? Set.of() : statuses, pageable));
    }

    @GetMapping("/{id}")
    public HelpRequestDetail get(Authentication caller, @PathVariable Long id) {
        return help.get(caller, id);
    }

    @PostMapping("/{id}/messages")
    public HelpRequestDetail reply(Authentication caller, @PathVariable Long id, @Valid @RequestBody HelpReply reply) {
        return help.reply(caller, id, reply.message());
    }

    @PostMapping("/{id}/close")
    public HelpRequestDetail close(Authentication caller, @PathVariable Long id) {
        return help.close(caller, id);
    }
}
