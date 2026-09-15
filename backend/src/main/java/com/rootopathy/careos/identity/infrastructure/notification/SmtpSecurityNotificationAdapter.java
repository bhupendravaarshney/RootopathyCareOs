package com.rootopathy.careos.identity.infrastructure.notification;

import com.rootopathy.careos.identity.application.SecurityNotificationPort;
import com.rootopathy.careos.identity.infrastructure.config.IdentitySecurityProperties;
import java.time.Instant;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public final class SmtpSecurityNotificationAdapter implements SecurityNotificationPort {
    private final JavaMailSender mailSender;
    private final IdentitySecurityProperties properties;

    public SmtpSecurityNotificationAdapter(JavaMailSender mailSender, IdentitySecurityProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public void sendPasswordReset(String email, String rawToken, Instant expiresAt) {
        var message = new SimpleMailMessage();
        message.setFrom(properties.mailFrom());
        message.setTo(email);
        message.setSubject("Reset your CareOS password");
        message.setText("Use this one-time link before " + expiresAt + ":\n\n"
                + properties.applicationBaseUrl()
                + "/#/reset-password?token="
                + rawToken
                + "\n\nIf you did not request this, no action is required.");
        mailSender.send(message);
    }

    @Override
    public void sendInvitation(String email, String rawToken, Instant expiresAt) {
        var message = new SimpleMailMessage();
        message.setFrom(properties.mailFrom());
        message.setTo(email);
        message.setSubject("Your CareOS organization invitation");
        message.setText("Use this one-time invitation before " + expiresAt + ":\n\n"
                + properties.applicationBaseUrl()
                + "/#/accept-invitation?token="
                + rawToken
                + "\n\nIf you were not expecting this invitation, no action is required.");
        mailSender.send(message);
    }

    @Override
    public void sendMfaAdministrativelyReset(String email) {
        var message = new SimpleMailMessage();
        message.setFrom(properties.mailFrom());
        message.setTo(email);
        message.setSubject("Your CareOS MFA was reset");
        message.setText(
                "An independently approved administrator reset removed your CareOS MFA method and signed out existing sessions. Sign in with your password and enroll MFA again. Contact support immediately if this was unexpected.");
        mailSender.send(message);
    }
}
