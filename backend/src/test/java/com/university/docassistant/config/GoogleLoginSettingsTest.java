package com.university.docassistant.config;

import com.university.docassistant.controller.AuthController;
import com.university.docassistant.security.ClientIpResolver;
import com.university.docassistant.security.CustomUserDetailsService;
import com.university.docassistant.security.JwtAuthenticationFilter;
import com.university.docassistant.security.JwtTokenProvider;
import com.university.docassistant.security.OAuth2FailureHandler;
import com.university.docassistant.security.OAuth2SuccessHandler;
import com.university.docassistant.security.RestSecurityErrorHandler;
import com.university.docassistant.service.AuthService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Google sign-in only with a real-looking client: otherwise no Google client is registered, the app starts with
 * password login only, /api/v1/auth/providers says google=false and one WARN is logged - never with the secret.
 */
@ExtendWith(OutputCaptureExtension.class)
class GoogleLoginSettingsTest {

    private static final String VALID_ID = "123456789012-abcdefghijklmnopqrstuvwxyz012345.apps.googleusercontent.com";
    private static final String SECRET = "GOCSPX-test-secret-must-never-be-logged";

    @Test
    void missingOrPlaceholderClientIdTurnsGoogleOff() {
        for (String id : new String[]{null, "", "   ", "your-google-client-id",
                "your-google-client-id.apps.googleusercontent.com", ".apps.googleusercontent.com",
                "123456789012-abcdef", "client-id-placeholder.apps.googleusercontent.com"}) {
            assertThat(new GoogleLoginSettings(id, SECRET).isEnabled()).as("client id %s", id).isFalse();
        }
    }

    @Test
    void missingOrPlaceholderSecretTurnsGoogleOff() {
        for (String secret : new String[]{null, "", "  ", "your-google-client-secret"}) {
            assertThat(new GoogleLoginSettings(VALID_ID, secret).isEnabled()).as("secret %s", secret).isFalse();
        }
    }

    @Test
    void validLookingClientTurnsGoogleOnWithTheDefaultRedirectUri() {
        GoogleLoginSettings settings = new GoogleLoginSettings("  " + VALID_ID + " ", " " + SECRET);

        assertThat(settings.isEnabled()).isTrue();
        ClientRegistration registration = settings.registration();
        assertThat(registration.getRegistrationId()).isEqualTo("google");
        assertThat(registration.getClientId()).isEqualTo(VALID_ID);
        assertThat(registration.getClientSecret()).isEqualTo(SECRET);
        assertThat(registration.getRedirectUri()).isEqualTo("{baseUrl}/{action}/oauth2/code/{registrationId}");
        assertThat(registration.getScopes()).containsExactlyInAnyOrder("openid", "profile", "email");
    }

    @Test
    void providersEndpointReportsTheFlag() {
        AuthService authService = mock(AuthService.class);
        ClientIpResolver ipResolver = mock(ClientIpResolver.class);

        assertThat(new AuthController(authService, ipResolver, new GoogleLoginSettings("your-google-client-id", SECRET))
                .providers().getBody().google()).isFalse();
        assertThat(new AuthController(authService, ipResolver, new GoogleLoginSettings(VALID_ID, SECRET))
                .providers().getBody().google()).isTrue();
    }

    @Test
    void startupCheckWarnsOnceAndNeverLogsTheSecret(CapturedOutput output) {
        new GoogleLoginSettings("", SECRET).logStatus();
        new GoogleLoginSettings("your-google-client-id", SECRET).logStatus();
        new GoogleLoginSettings(VALID_ID, SECRET).logStatus();

        assertThat(output.getAll())
                .contains("Google sign-in is not configured: GOOGLE_CLIENT_ID is missing or a placeholder")
                .contains("Google sign-in is configured")
                .doesNotContain(SECRET)
                .doesNotContain("GOCSPX-");
    }

    @Test
    void withoutGoogleThePasswordLoginChainStartsWithoutOauth2Login() {
        contextRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ClientRegistrationRepository.class);
            List<Filter> filters = context.getBean(SecurityFilterChain.class).getFilters();
            assertThat(filters).anyMatch(JwtAuthenticationFilter.class::isInstance);
            assertThat(filters).noneMatch(OAuth2AuthorizationRequestRedirectFilter.class::isInstance);
        });
        contextRunner().withPropertyValues(GoogleLoginSettings.CLIENT_ID_PROPERTY + "=your-google-client-id",
                GoogleLoginSettings.CLIENT_SECRET_PROPERTY + "=" + SECRET).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ClientRegistrationRepository.class);
        });
    }

    @Test
    void withGoogleBothPasswordAndOauth2LoginAreInTheChain() {
        contextRunner().withPropertyValues(GoogleLoginSettings.CLIENT_ID_PROPERTY + "=" + VALID_ID,
                GoogleLoginSettings.CLIENT_SECRET_PROPERTY + "=" + SECRET).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ClientRegistrationRepository.class).findByRegistrationId("google")).isNotNull();
            List<Filter> filters = context.getBean(SecurityFilterChain.class).getFilters();
            assertThat(filters).anyMatch(JwtAuthenticationFilter.class::isInstance);
            assertThat(filters).anyMatch(OAuth2AuthorizationRequestRedirectFilter.class::isInstance);
        });
    }

    private WebApplicationContextRunner contextRunner() {
        return new WebApplicationContextRunner()
                .withPropertyValues("app.cors.allowed-origins=http://localhost:5173")
                .withBean(JwtTokenProvider.class, () -> mock(JwtTokenProvider.class))
                .withBean(CustomUserDetailsService.class, () -> mock(CustomUserDetailsService.class))
                .withBean(OAuth2SuccessHandler.class, () -> mock(OAuth2SuccessHandler.class))
                .withBean(OAuth2FailureHandler.class, () -> mock(OAuth2FailureHandler.class))
                .withBean(RestSecurityErrorHandler.class, () -> mock(RestSecurityErrorHandler.class))
                .withUserConfiguration(Mvc.class, GoogleLoginSettings.class, SecurityConfig.class);
    }

    /** Spring Security's request matchers need Spring MVC in the same context. */
    @Configuration
    @EnableWebMvc
    static class Mvc {
    }
}
