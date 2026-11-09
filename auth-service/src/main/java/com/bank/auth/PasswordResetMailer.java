package com.bank.auth;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends reset links in the background, so the time an answer takes does not reveal whether the email
 * has an account. If sending fails the person can simply ask again. Never log the link: it is a
 * working password reset.
 */
@Slf4j
@Component
public class PasswordResetMailer {

    private static final int MAX_WAITING = 100;

    private final JavaMailSender mail;
    private final String from;
    private final ThreadPoolExecutor sender = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_WAITING));

    PasswordResetMailer(JavaMailSender mail, @Value("${bank.password-reset.from}") String from) {
        this.mail = mail;
        this.from = from;
    }

    void send(String to, String link) {
        if (from.isBlank()) {
            log.warn("A password reset was requested, but email is not set up, so nothing was sent.");
            return;
        }
        try {
            sender.execute(() -> deliver(to, link));
        } catch (RejectedExecutionException e) {
            log.warn("Too many password reset emails are waiting; one was dropped.");
        }
    }

    private void deliver(String to, String link) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject("Reset your bank password");
        message.setText("""
                Someone asked to reset the password for this account.

                To choose a new password, open this link within 30 minutes:

                %s

                If this wasn't you, you can ignore this email. Your password has not changed.
                """.formatted(link));
        try {
            mail.send(message);
        } catch (MailException e) {
            log.warn("A password reset email could not be sent: {}", e.getClass().getSimpleName());
        }
    }

    @PreDestroy
    void stop() {
        sender.shutdown();
    }
}
