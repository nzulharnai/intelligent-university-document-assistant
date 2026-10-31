package com.university.docassistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Settings of the OpenAI-compatible Chat Completions API used for task extraction ({@code app.ai.*}).
 *
 * @param maxInputChars  longest user message of one deadline-extraction call; longer texts are sent in several
 *                       chunks. Missing or below 1 means {@value #DEFAULT_MAX_INPUT_CHARS}
 * @param maxConcurrency analyses running at the same time; the rest wait in a queue of {@code queueCapacity}
 * @param maxConcurrentQuestions Q&A calls in flight at once; each holds a request thread while the model answers,
 *                               so this caps how many threads slow AI answers can occupy
 * @param maxAttempts    tries per call when the API answers 429 or 5xx (rate limits, overload); the first try
 *                       included. Missing or below 1 means {@value #DEFAULT_MAX_ATTEMPTS}
 */
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(
        boolean enabled,
        String apiKey,
        String model,
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        int maxInputChars,
        int maxConcurrency,
        int queueCapacity,
        int maxConcurrentQuestions,
        int maxAttempts
) {
    /** Placeholder key from application.yml: treated like a missing key. */
    public static final String DEMO_KEY = "demo-key";
    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    /** About 5k tokens: one call fits Groq's free per-minute token limit (~8k for openai/gpt-oss-120b). */
    public static final int DEFAULT_MAX_INPUT_CHARS = 15_000;

    public AiProperties {
        // A key pasted with a stray space or newline would otherwise break the Authorization header.
        apiKey = apiKey == null ? null : apiKey.strip();
        // An absent app.ai.max-attempts binds as 0.
        maxAttempts = maxAttempts < 1 ? DEFAULT_MAX_ATTEMPTS : maxAttempts;
        maxInputChars = maxInputChars < 1 ? DEFAULT_MAX_INPUT_CHARS : maxInputChars;
    }

    /** True only when a call can actually be made; otherwise analysis is skipped without contacting the API. */
    public boolean isConfigured() {
        return enabled && apiKey != null && !apiKey.isBlank() && !DEMO_KEY.equals(apiKey)
                && baseUrl != null && !baseUrl.isBlank();
    }
}
