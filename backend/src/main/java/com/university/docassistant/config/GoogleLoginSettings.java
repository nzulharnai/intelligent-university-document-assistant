package com.university.docassistant.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * app.oauth2.google: Google sign-in is switched on only for a client ID that looks real (ends with
 * .apps.googleusercontent.com, no placeholder words) and a non-empty secret. Otherwise the app runs with password
 * login only, logs one WARN at startup, and /api/v1/auth/providers reports google=false so the login page hides the
 * button instead of sending users to Google's "Error 401: invalid_client". The secret is never logged.
 */
@Slf4j
@Component
public class GoogleLoginSettings {

    public static final String CLIENT_ID_PROPERTY = "app.oauth2.google.client-id";
    public static final String CLIENT_SECRET_PROPERTY = "app.oauth2.google.client-secret";
    public static final String REGISTRATION_ID = "google";

    private static final String CLIENT_ID_SUFFIX = ".apps.googleusercontent.com";
    private static final Pattern PLACEHOLDER = Pattern.compile("your|placeholder|example|changeme|change-me|replace",
            Pattern.CASE_INSENSITIVE);

    private final String clientId;
    private final String clientSecret;
    private final String problem;

    public GoogleLoginSettings(@Value("${" + CLIENT_ID_PROPERTY + ":}") String clientId,
                               @Value("${" + CLIENT_SECRET_PROPERTY + ":}") String clientSecret) {
        this.clientId = clientId == null ? "" : clientId.strip();
        this.clientSecret = clientSecret == null ? "" : clientSecret.strip();
        this.problem = problem(this.clientId, this.clientSecret);
    }

    /** Why Google sign-in stays off, or null when the settings look usable. Never contains the values themselves. */
    static String problem(String clientId, String clientSecret) {
        String id = clientId == null ? "" : clientId.strip();
        String secret = clientSecret == null ? "" : clientSecret.strip();
        if (id.isEmpty() || PLACEHOLDER.matcher(id).find() || !id.endsWith(CLIENT_ID_SUFFIX)
                || id.length() == CLIENT_ID_SUFFIX.length()) {
            return "GOOGLE_CLIENT_ID is missing or a placeholder";
        }
        if (secret.isEmpty() || PLACEHOLDER.matcher(secret).find()) {
            return "GOOGLE_CLIENT_SECRET is missing or a placeholder";
        }
        return null;
    }

    public boolean isEnabled() {
        return problem == null;
    }

    /** The Google client for Spring's oauth2Login; redirect URI {baseUrl}/login/oauth2/code/google. */
    public ClientRegistration registration() {
        if (!isEnabled()) {
            throw new IllegalStateException("Google sign-in is not configured");
        }
        return CommonOAuth2Provider.GOOGLE.getBuilder(REGISTRATION_ID)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logStatus() {
        if (isEnabled()) {
            log.info("Google sign-in is configured");
        } else {
            log.warn("Google sign-in is not configured: {}. Only password login is available", problem);
        }
    }

    /** Registers the Google client (and turns on oauth2Login) only when the same check passes. */
    public static class Enabled implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return problem(context.getEnvironment().getProperty(CLIENT_ID_PROPERTY),
                    context.getEnvironment().getProperty(CLIENT_SECRET_PROPERTY)) == null;
        }
    }
}
