package com.bank.common.audit;

import com.fasterxml.jackson.annotation.JsonRawValue;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Lets staff read this service's audit log, newest first. Each service sets its own path. */
@RestController
class AuditController {

    private final JdbcTemplate jdbc;

    AuditController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PreAuthorize("hasRole('STAFF')")
    @GetMapping("${bank.audit.path}")
    PagedModel<AuditEntry> list(
            @RequestParam(name = "actorUserId", required = false) Long actorUserId,
            @RequestParam(name = "targetId", required = false) String targetId,
            Pageable pageable) {
        StringBuilder where = new StringBuilder(" where true");
        List<Object> arguments = new ArrayList<>();
        if (actorUserId != null) {
            where.append(" and actor_user_id = ?");
            arguments.add(actorUserId);
        }
        if (targetId != null) {
            where.append(" and target_id = ?");
            arguments.add(targetId);
        }
        Long total = jdbc.queryForObject("select count(*) from audit_log" + where, Long.class, arguments.toArray());

        List<Object> pageArguments = new ArrayList<>(arguments);
        pageArguments.add(pageable.getPageSize());
        pageArguments.add(pageable.getOffset());
        List<AuditEntry> entries = jdbc.query(
                "select * from audit_log" + where + " order by id desc limit ? offset ?",
                AuditController::toEntry, pageArguments.toArray());
        return new PagedModel<>(new PageImpl<>(entries, pageable, total == null ? 0 : total));
    }

    private static AuditEntry toEntry(ResultSet row, int rowNumber) throws SQLException {
        return new AuditEntry(
                row.getLong("id"),
                row.getTimestamp("occurred_at").toInstant(),
                row.getObject("actor_user_id", Long.class),
                row.getString("actor_role"),
                row.getString("action"),
                row.getString("target_id"),
                row.getString("details"));
    }

    /** details is passed through as stored JSON, so amounts keep their exact digits. */
    record AuditEntry(
            long id,
            Instant occurredAt,
            Long actorUserId,
            String actorRole,
            String action,
            String targetId,
            @JsonRawValue String details) {
    }
}
