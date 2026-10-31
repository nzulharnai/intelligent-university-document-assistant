package com.university.docassistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.university.docassistant.config.AiProperties;
import com.university.docassistant.dto.TaskExtractDto;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiTaskExtractionServiceTest {

    private static final String LONG_TEXT = "Лабораторная работа 1: реализовать сортировку слиянием. Сдать до 15 декабря.";

    private final AiClientService client = mock(AiClientService.class);
    private final AiProperties properties = new AiProperties(true, "key", "model", "http://localhost",
            Duration.ofSeconds(1), Duration.ofSeconds(1), 1_000, 1, 1, 1, 1);
    private final AiTaskExtractionService service = new AiTaskExtractionService(client, properties, new ObjectMapper(),
            Clock.fixed(Instant.parse("2026-10-03T08:00:00Z"), ZoneOffset.UTC));

    @Test
    void parsesPlainArray() {
        List<TaskExtractDto> tasks = service.parseTasks("""
                [{"title": "Лаба 1", "description": "Сортировка", "deadline": "2026-12-15T23:59:00"},
                 {"title": "Проект", "description": null, "deadline": null}]""");

        assertThat(tasks).containsExactly(
                new TaskExtractDto("Лаба 1", "Сортировка", LocalDateTime.of(2026, 12, 15, 23, 59)),
                new TaskExtractDto("Проект", null, null));
    }

    @Test
    void toleratesFencesCommentsTrailingCommasAndProse() {
        List<TaskExtractDto> tasks = service.parseTasks("""
                Вот результат:
                ```json
                [
                  {
                    "title": "Лаба 2", // комментарий модели
                    "deadline": "2026-11-01",
                  },
                ]
                ```""");

        assertThat(tasks).containsExactly(new TaskExtractDto("Лаба 2", null, LocalDateTime.of(2026, 11, 1, 23, 59)));
    }

    @Test
    void acceptsArrayWrappedInObject() {
        assertThat(service.parseTasks("{\"tasks\": [{\"title\": \"Эссе\"}]}"))
                .extracting(TaskExtractDto::title).containsExactly("Эссе");
    }

    @Test
    void dropsInvalidItemsAndDuplicates() {
        List<TaskExtractDto> tasks = service.parseTasks("""
                [{"description": "без названия"}, "строка", {"title": "  "},
                 {"title": "Лаба 3", "deadline": "когда-нибудь"}, {"title": "лаба 3", "deadline": null}]""");

        assertThat(tasks).containsExactly(new TaskExtractDto("Лаба 3", null, null));
    }

    @Test
    void unusableAnswerYieldsNoTasks() {
        assertThat(service.parseTasks("Извините, я не нашёл заданий.")).isEmpty();
        assertThat(service.parseTasks("{\"error\": \"oops\"}")).isEmpty();
    }

    @Test
    void parsesDeadlineFormats() {
        assertThat(AiTaskExtractionService.parseDeadline("2026-12-15T10:30")).isEqualTo(LocalDateTime.of(2026, 12, 15, 10, 30));
        assertThat(AiTaskExtractionService.parseDeadline("2026-12-15 10:30:00")).isEqualTo(LocalDateTime.of(2026, 12, 15, 10, 30));
        assertThat(AiTaskExtractionService.parseDeadline("2026-12-15T10:30:00Z")).isEqualTo(LocalDateTime.of(2026, 12, 15, 10, 30));
        assertThat(AiTaskExtractionService.parseDeadline("null")).isNull();
        assertThat(AiTaskExtractionService.parseDeadline("15.12.2026")).isNull();
    }

    @Test
    void shortTextIsNotSentToAi() {
        assertThat(service.extractTasksFromText("Короткий текст")).isEmpty();
        assertThat(service.extractTasksFromText(null)).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void sendsTodayAndTheWholeTextWhenItFitsInOneCall() {
        when(client.chat(anyString(), anyString())).thenReturn(Optional.of("[{\"title\": \"Лаба 1\"}]"));

        List<TaskExtractDto> tasks = service.extractTasksFromText(LONG_TEXT);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(client).chat(eq(AiTaskExtractionService.SYSTEM_PROMPT), message.capture());
        assertThat(message.getValue()).isEqualTo("Сегодняшняя дата: 2026-10-03\n\nТекст документа:\n" + LONG_TEXT);
        assertThat(tasks).extracting(TaskExtractDto::title).containsExactly("Лаба 1");
    }

    /** Texts longer than max-input-chars (1 000 here): sent in chunks, one call each, results merged. */
    @Nested
    class LongText {

        /** Three paragraphs of ~600 characters: two never fit in one 1 000-character call, so 3 chunks. */
        private final String first = paragraph("Лабораторная работа 1 и проект.");
        private final String second = paragraph("Лабораторная работа 2.");
        private final String third = paragraph("Экзамен.");
        private final String text = first + "\n\n" + second + "\n\n" + third;

        private static String paragraph(String start) {
            StringBuilder paragraph = new StringBuilder(start);
            while (paragraph.length() < 600) {
                paragraph.append(" Требования к отчёту и порядок защиты.");
            }
            return paragraph.toString();
        }

        private void answer(int part, String json) {
            when(client.chat(anyString(), contains("(часть " + part + " из 3)"))).thenReturn(Optional.ofNullable(json));
        }

        @Test
        void threeChunksGiveMergedTasksWithoutDuplicates() {
            answer(1, """
                    {"summary": "Силлабус.", "topics": ["Сортировки"], "tasks": [
                      {"title": "Лаб. работа №1", "deadline": "2026-11-01T18:00:00"},
                      {"title": "Проект", "deadline": null}]}""");
            answer(2, """
                    {"summary": "Вторая часть.", "topics": ["Графы"], "tasks": [
                      {"title": "Лабораторная работа 1", "deadline": "2026-11-01T18:00:00"},
                      {"title": "Лабораторная работа 2", "deadline": "2026-11-15T18:00:00"}]}""");
            answer(3, """
                    {"summary": "Третья часть.", "topics": [], "tasks": [
                      {"title": "ЛАБ.2", "deadline": "2026-11-15T18:00:00"},
                      {"title": "Лабораторная работа 1", "deadline": "2026-12-01T18:00:00"},
                      {"title": "Экзамен", "deadline": null}]}""");

            Optional<AiTaskExtractionService.Analysis> analysis = service.analyze(text);

            verify(client, times(3)).chat(anyString(), anyString());
            assertThat(analysis).hasValueSatisfying(result -> {
                assertThat(result.partial()).isFalse();
                assertThat(result.summary()).isEqualTo("Силлабус.");
                assertThat(result.topics()).containsExactly("Сортировки");
                // Lab 1 and Lab 2 are mentioned twice with the same deadline: once each. Lab 1 with another
                // deadline is another item, as within one answer.
                assertThat(result.tasks()).extracting(TaskExtractDto::title, TaskExtractDto::deadline).containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Лаб. работа №1", LocalDateTime.of(2026, 11, 1, 18, 0)),
                        org.assertj.core.groups.Tuple.tuple("Проект", null),
                        org.assertj.core.groups.Tuple.tuple("Лабораторная работа 2", LocalDateTime.of(2026, 11, 15, 18, 0)),
                        org.assertj.core.groups.Tuple.tuple("Лабораторная работа 1", LocalDateTime.of(2026, 12, 1, 18, 0)),
                        org.assertj.core.groups.Tuple.tuple("Экзамен", null));
            });
        }

        @Test
        void everyRequestStaysWithinTheLimitAndTheChunksCoverTheTextAtParagraphBreaks() {
            when(client.chat(anyString(), anyString())).thenReturn(Optional.of("{\"tasks\": []}"));

            service.analyze(text);

            List<String> parts = sentParts(3);
            assertThat(parts).containsExactly(first + "\n\n", second + "\n\n", third);
            assertThat(String.join("", parts)).isEqualTo(text);
        }

        @Test
        void textWithoutParagraphBreaksIsCutAtSpacesOrHardWithinTheLimit() {
            when(client.chat(anyString(), anyString())).thenReturn(Optional.of("{\"tasks\": []}"));
            String words = "слово ".repeat(500); // 3 000 characters, spaces only
            String solid = "x".repeat(2_500); // nothing to cut at

            service.analyze(words);
            List<String> wordParts = sentParts(4);
            assertThat(String.join("", wordParts)).isEqualTo(words);
            assertThat(wordParts).allSatisfy(part -> assertThat(part).endsWith(" "));

            org.mockito.Mockito.clearInvocations(client);
            service.analyze(solid);
            assertThat(String.join("", sentParts(3))).isEqualTo(solid);
        }

        @Test
        void oneFailedChunkMarksTheAnalysisPartialAndKeepsTheOtherTasks() {
            answer(1, "{\"summary\": \"Силлабус.\", \"tasks\": [{\"title\": \"Лабораторная работа 1\"}]}");
            answer(2, null); // still failing after the client's retries
            answer(3, "{\"tasks\": [{\"title\": \"Экзамен\"}]}");

            assertThat(service.analyze(text)).hasValueSatisfying(result -> {
                assertThat(result.partial()).isTrue();
                assertThat(result.summary()).isEqualTo("Силлабус.");
                assertThat(result.tasks()).extracting(TaskExtractDto::title)
                        .containsExactly("Лабораторная работа 1", "Экзамен");
            });
            verify(client, times(3)).chat(anyString(), anyString());
        }

        @Test
        void unusableAnswerCountsAsAFailedChunkAndSummaryComesFromTheFirstThatAnswered() {
            answer(1, "Извините, не могу.");
            answer(2, "{\"summary\": \"Вторая часть.\", \"tasks\": [{\"title\": \"Лабораторная работа 2\"}]}");
            answer(3, "{\"tasks\": []}");

            assertThat(service.analyze(text)).hasValueSatisfying(result -> {
                assertThat(result.partial()).isTrue();
                assertThat(result.summary()).isEqualTo("Вторая часть.");
                assertThat(result.tasks()).extracting(TaskExtractDto::title).containsExactly("Лабораторная работа 2");
            });
        }

        @Test
        void noChunkAnsweredIsNoResult() {
            when(client.chat(anyString(), anyString())).thenReturn(Optional.empty());

            assertThat(service.analyze(text)).isEmpty();
        }

        /** The document text of each of the {@code expected} calls, checking every message against the limit. */
        private List<String> sentParts(int expected) {
            ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
            verify(client, atLeast(1)).chat(eq(AiTaskExtractionService.SYSTEM_PROMPT), messages.capture());
            assertThat(messages.getAllValues()).hasSize(expected).allSatisfy(message -> assertThat(message)
                    .startsWith("Сегодняшняя дата: 2026-10-03\n\nТекст документа (часть ")
                    .hasSizeLessThanOrEqualTo(properties.maxInputChars()));
            return messages.getAllValues().stream().map(message -> message.substring(message.indexOf("):\n") + 3))
                    .toList();
        }
    }

    @Test
    void unavailableAiYieldsNoTasks() {
        when(client.chat(anyString(), anyString())).thenReturn(Optional.empty());

        assertThat(service.extractTasksFromText(LONG_TEXT)).isEmpty();
        assertThat(service.analyze(LONG_TEXT)).isEmpty();
    }

    @Test
    void parsesSummaryTopicsAndTasks() {
        Optional<AiTaskExtractionService.Analysis> analysis = service.parseAnalysis("""
                Вот анализ: {"summary": "Силлабус курса алгоритмов.",
                 "topics": ["Сортировки", "Графы\\nи деревья", "Сортировки", "", null, 5],
                 "tasks": [{"title": "Лаба 1", "deadline": "2026-12-15"}]}""");

        assertThat(analysis).hasValueSatisfying(result -> {
            assertThat(result.summary()).isEqualTo("Силлабус курса алгоритмов.");
            assertThat(result.topics()).containsExactly("Сортировки", "Графы и деревья", "5");
            assertThat(result.tasks()).containsExactly(
                    new TaskExtractDto("Лаба 1", null, LocalDateTime.of(2026, 12, 15, 23, 59)));
        });
    }

    @Test
    void successfulAnalysisWithoutTasksIsNotAFailure() {
        assertThat(service.parseAnalysis("{\"summary\": \"Положение об общежитии.\", \"topics\": [], \"tasks\": []}"))
                .hasValueSatisfying(result -> {
                    assertThat(result.summary()).isEqualTo("Положение об общежитии.");
                    assertThat(result.tasks()).isEmpty();
                });
    }

    @Test
    void bareTaskArrayIsStillAccepted() {
        assertThat(service.parseAnalysis("[{\"title\": \"Эссе\"}]"))
                .hasValueSatisfying(result -> {
                    assertThat(result.summary()).isNull();
                    assertThat(result.topics()).isEmpty();
                    assertThat(result.tasks()).extracting(TaskExtractDto::title).containsExactly("Эссе");
                });
    }
}
