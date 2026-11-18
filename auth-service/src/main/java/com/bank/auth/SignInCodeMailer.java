package com.bank.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends sign-in codes. Unlike reset links this waits for the mail server, so the person can be told at
 * once if the code could not be sent. The password was already right, so the wait reveals nothing.
 * Never log the code.
 */
@Component
public class SignInCodeMailer {

    private final JavaMailSender mail;
    private final String from;

    SignInCodeMailer(JavaMailSender mail, @Value("${bank.password-reset.from}") String from) {
        this.mail = mail;
        this.from = from;
    }

    /** Without a sender address no codes can be sent, so two-step sign-in cannot be used. */
    public boolean canSend() {
        return !from.isBlank();
    }

    /** Throws MailException if the mail server refuses or cannot be reached. */
    void send(String to, String code, SignInCodes.Purpose purpose, int minutes) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        if (purpose == SignInCodes.Purpose.SIGN_IN) {
            message.setSubject("Your bank sign-in code: " + code);
            message.setText("""
                    Your code to finish signing in is:

                    %s

                    It works once, for %d minutes. Never give it to anyone, including bank staff.

                    If you did not just sign in, someone knows your password: change it straight away.
                    """.formatted(code, minutes));
        } else {
            message.setSubject("Your code to turn on two-step sign-in: " + code);
            message.setText("""
                    Enter this code to turn on two-step sign-in:

                    %s

                    It works once, for %d minutes. If you did not ask for this, change your password.
                    """.formatted(code, minutes));
        }
        mail.send(message);
    }
}
