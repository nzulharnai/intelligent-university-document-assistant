package com.university.docassistant.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import static org.assertj.core.api.Assertions.assertThat;

/** An OAuth error from Google lands on the frontend login page with a code AuthPage turns into a Russian message. */
class OAuth2FailureHandlerTest {

    private final OAuth2FailureHandler handler = new OAuth2FailureHandler("http://localhost:5173");

    private String redirectFor(AuthenticationException exception) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationFailure(new MockHttpServletRequest(), response, exception);
        return response.getRedirectedUrl();
    }

    @Test
    void cancelledOnGooglesPageGoesToLoginAsCancelled() throws Exception {
        assertThat(redirectFor(new OAuth2AuthenticationException(new OAuth2Error("access_denied"))))
                .isEqualTo("http://localhost:5173/login?error=oauth2_cancelled");
    }

    @Test
    void anyOtherFailureGoesToLoginAsFailed() throws Exception {
        assertThat(redirectFor(new OAuth2AuthenticationException(new OAuth2Error("invalid_token_response"))))
                .isEqualTo("http://localhost:5173/login?error=oauth2_failed");
        assertThat(redirectFor(new InternalAuthenticationServiceException("boom")))
                .isEqualTo("http://localhost:5173/login?error=oauth2_failed");
    }
}
