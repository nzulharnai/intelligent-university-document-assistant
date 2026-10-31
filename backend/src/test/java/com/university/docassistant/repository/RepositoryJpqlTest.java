package com.university.docassistant.repository;

import jakarta.persistence.Entity;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every JPQL @Query of the repositories goes through Hibernate's parser and semantic check against the real entity
 * model (PostgreSQL dialect, no database): a typo in a path, an enum constant or a parameter fails here instead of at
 * startup. The SQL of native queries is checked against PostgreSQL in the opt-in *PostgresTest classes.
 */
class RepositoryJpqlTest {

    private static final String BASE = "com.university.docassistant";

    @Test
    void everyJpqlQueryParses() throws Exception {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                .applySetting("hibernate.hbm2ddl.auto", "none")
                .build();
        MetadataSources sources = new MetadataSources(registry);
        for (Class<?> entity : classes(BASE + ".entity", new AnnotationTypeFilter(Entity.class))) {
            sources.addAnnotatedClass(entity);
        }
        List<String> checked = new ArrayList<>();
        try (SessionFactory factory = sources.buildMetadata().buildSessionFactory();
             Session session = factory.openSession()) {
            for (Class<?> repository : classes(BASE + ".repository", new AssignableTypeFilter(Repository.class))) {
                for (Method method : repository.getDeclaredMethods()) {
                    Query query = method.getAnnotation(Query.class);
                    if (query == null || query.nativeQuery()) {
                        continue;
                    }
                    String where = repository.getSimpleName() + "." + method.getName();
                    parse(session, query.value(), where);
                    if (!query.countQuery().isEmpty()) {
                        parse(session, query.countQuery(), where + " (count)");
                    }
                    checked.add(where);
                }
            }
        }
        assertThat(checked).contains("DocumentRepository.findSummariesInCourses", "DocumentRepository.findAllSummaries");
    }

    private static void parse(Session session, String jpql, String where) {
        try {
            String start = jpql.strip().toUpperCase(Locale.ROOT);
            if (start.startsWith("UPDATE") || start.startsWith("DELETE") || start.startsWith("INSERT")) {
                session.createMutationQuery(jpql);
            } else {
                session.createQuery(jpql, Object.class);
            }
        } catch (RuntimeException ex) {
            throw new AssertionError(where + ": " + ex.getMessage(), ex);
        }
    }

    private static List<Class<?>> classes(String pkg, org.springframework.core.type.filter.TypeFilter filter)
            throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(
                    org.springframework.beans.factory.annotation.AnnotatedBeanDefinition definition) {
                return true; // Interfaces too: the repositories are interfaces.
            }
        };
        scanner.addIncludeFilter(filter);
        List<Class<?>> found = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(pkg)) {
            found.add(ClassUtils.forName(definition.getBeanClassName(), RepositoryJpqlTest.class.getClassLoader()));
        }
        return found;
    }
}
