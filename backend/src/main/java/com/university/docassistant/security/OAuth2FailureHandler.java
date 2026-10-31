package com.university.docassistant.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * Sends the user back to the frontend login page with ?error=oauth2_cancelled (the user declined on Google's page)
 * or ?error=oauth2_failed instead of Spring's default /login?error page. Only the OAuth2 error code is logged.
 */
@Slf4j
@Component
public class OAuth2FailureHandler extends SimpleUrlAuthenticationFailureHandler {

    static final String CANCELLED = "oauth2_cancelled";
    static final String FAILED = "oauth2_failed";

    private final String loginUrl;

    public OAuth2FailureHandler(@Value("${app.frontend-url}") String frontendUrl) {
        this.loginUrl = frontendUrl + "/login";
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        String oauthError = exception instanceof OAuth2AuthenticationException oauth
                ? oauth.getError().getErrorCode()
                : exception.getClass().getSimpleName();
        log.warn("Google sign-in failed: {}", oauthError);
        String targetUrl = UriComponentsBuilder.fromUriString(loginUrl)
                .queryParam("error", "access_denied".equals(oauthError) ? CANCELLED : FAILED)
                .build()
                .toUriString();
        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}
