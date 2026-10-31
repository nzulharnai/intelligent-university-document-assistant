package com.university.docassistant.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Background AI analysis runs on its own bounded pool: a slow or hanging AI API can occupy at most
 * {@code app.ai.max-concurrency} threads, never the request threads. When the queue is full, submitting throws
 * TaskRejectedException, which callers handle (an upload then simply skips the analysis).
 */
@Configuration
@EnableAsync
@EnableConfigurationProperties({AiProperties.class, PrivacyProperties.class, OcrProperties.class})
public class AsyncConfig {

    public static final String AI_EXECUTOR = "aiTaskExecutor";
    /** One thread for re-masking a course's documents (CoursePrivacyRemask): CPU only, never the AI's pool. */
    public static final String PRIVACY_EXECUTOR = "privacyTaskExecutor";
    /** Notification e-mails (NotificationService): sent after commit here, never on a request thread. */
    public static final String MAIL_EXECUTOR = "mailTaskExecutor";

    @Bean(name = MAIL_EXECUTOR)
    public ThreadPoolTaskExecutor mailTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("mail-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(1_000);
        executor.setAllowCoreThreadTimeOut(true);
        return executor;
    }

    @Bean(name = PRIVACY_EXECUTOR)
    public ThreadPoolTaskExecutor privacyTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("privacy-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(100);
        executor.setAllowCoreThreadTimeOut(true);
        return executor;
    }

    @Bean(name = AI_EXECUTOR)
    public ThreadPoolTaskExecutor aiTaskExecutor(AiProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("ai-");
        executor.setCorePoolSize(properties.maxConcurrency());
        executor.setMaxPoolSize(properties.maxConcurrency());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setAllowCoreThreadTimeOut(true);
        return executor;
    }
}
