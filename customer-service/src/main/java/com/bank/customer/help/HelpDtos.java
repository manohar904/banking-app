package com.bank.customer.help;

import com.bank.customer.Customer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** What the Help API takes and gives back. */
public final class HelpDtos {

    private HelpDtos() {
    }

    public record NewHelpRequest(
            @NotBlank @Size(max = 120) String subject,
            @NotBlank @Size(max = 2000) String message) {
    }

    public record HelpReply(@NotBlank @Size(max = 2000) String message) {
    }

    /** customerName is only filled in for staff. */
    public record HelpRequestSummary(
            Long id,
            Long customerId,
            String customerName,
            String subject,
            HelpStatus status,
            Instant createdAt,
            Instant updatedAt) {

        static HelpRequestSummary from(HelpRequest request, Customer owner) {
            return new HelpRequestSummary(request.getId(), request.getCustomerId(), nameOf(owner),
                    request.getSubject(), request.getStatus(), request.getCreatedAt(), request.getUpdatedAt());
        }
    }

    public record HelpRequestDetail(
            Long id,
            Long customerId,
            String customerName,
            String subject,
            HelpStatus status,
            Instant createdAt,
            Instant updatedAt,
            List<HelpMessageResponse> messages) {

        static HelpRequestDetail from(HelpRequest request, Customer owner, List<HelpMessage> messages) {
            return new HelpRequestDetail(request.getId(), request.getCustomerId(), nameOf(owner),
                    request.getSubject(), request.getStatus(), request.getCreatedAt(), request.getUpdatedAt(),
                    messages.stream().map(HelpMessageResponse::from).toList());
        }
    }

    /** Which member of staff wrote a message is kept, but not shown: customers hear from "the bank". */
    public record HelpMessageResponse(Long id, boolean fromBank, String body, Instant createdAt) {

        static HelpMessageResponse from(HelpMessage message) {
            return new HelpMessageResponse(message.getId(), message.isFromBank(), message.getBody(),
                    message.getCreatedAt());
        }
    }

    private static String nameOf(Customer owner) {
        return owner == null ? null : owner.getFirstName() + " " + owner.getLastName();
    }
}
