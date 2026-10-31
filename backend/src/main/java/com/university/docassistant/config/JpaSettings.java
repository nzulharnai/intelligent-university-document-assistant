package com.university.docassistant.config;

import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Hibernate settings the schema depends on, kept in code: application.yml is not committed, so a setting there would
 * be missing in CI and in an older local copy.
 * <p>
 * Inserts ask for the new id only ("RETURNING id"). With generated keys the PostgreSQL driver appends "RETURNING *",
 * which sends back every column (documents.text_tsv too) and fails with "cached plan must not change result type"
 * once {@link DocumentSearchSchema} adds or rebuilds that column under a pooled connection.
 */
@Configuration
public class JpaSettings {

    @Bean
    public HibernatePropertiesCustomizer insertsReturnOnlyTheId() {
        return properties -> properties.put(AvailableSettings.USE_GET_GENERATED_KEYS, false);
    }
}
