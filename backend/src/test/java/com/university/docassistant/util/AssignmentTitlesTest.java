package com.university.docassistant.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AssignmentTitlesTest {

    private static final LocalDateTime OCT_20 = LocalDateTime.of(2026, 10, 20, 18, 0);

    @ParameterizedTest
    @ValueSource(strings = {
            "Лабораторная работа 1", "Лабораторная работа №1", "Лабораторная работа № 1.", "лаб. работа №1",
            "Лаб. 1", "ЛАБ.№1", "лаб1", "Лаба 1", "Лабораторная  работа   1",
            "Лабораторной работы №1"})
    void labVariantsAreTheSame(String title) {
        assertThat(AssignmentTitles.normalize(title)).isEqualTo("лабораторная работа 1");
    }

    @Test
    void sameWordingNormalizesEqual() {
        assertThat(AssignmentTitles.normalize("Лаб. работа №1: Сортировка!"))
                .isEqualTo(AssignmentTitles.normalize("лабораторная работа 1 — сортировка"))
                .isEqualTo("лабораторная работа 1 сортировка");
        assertThat(AssignmentTitles.normalize("Ёмкость стека")).isEqualTo("емкость стека");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Пр. 3", "Практическая работа №3", "Практика 3", "пр. работа 3", "Практическое занятие 3"})
    void practiceVariantsAreTheSame(String title) {
        assertThat(AssignmentTitles.normalize(title)).isEqualTo("практическая работа 3");
    }

    @ParameterizedTest
    @ValueSource(strings = {"СРС 2", "С.Р.С. №2", "СРО-2", "Самостоятельная работа студента 2", "срс№2"})
    void srsVariantsAreTheSame(String title) {
        assertThat(AssignmentTitles.normalize(title)).isEqualTo("срс 2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"СРСП 2", "С.Р.С.П. 2", "СРОП №2", "Самостоятельная работа студента под руководством преподавателя 2"})
    void srspIsNotSrs(String title) {
        assertThat(AssignmentTitles.normalize(title)).isEqualTo("срсп 2");
    }

    @Test
    void sameNormalizedTitleAndDeadlineOnTheSameDayIsTheSameAssignment() {
        assertThat(AssignmentTitles.sameAssignment("Лаб. работа №1", OCT_20,
                "Лабораторная работа 1", OCT_20.withHour(23).withMinute(59))).isTrue();
        assertThat(AssignmentTitles.sameAssignment("Лаб. 1", OCT_20.withHour(0).withMinute(0),
                "ЛАБ.№1", OCT_20)).as("any time of the same day").isTrue();
    }

    @Test
    void anotherDayIsAnotherAssignment() {
        assertThat(AssignmentTitles.sameAssignment("Лаб. 1", OCT_20, "Лаб. 1", OCT_20.plusDays(1))).isFalse();
        assertThat(AssignmentTitles.sameAssignment("Лаб. 1", OCT_20.withHour(23).withMinute(59),
                "Лаб. 1", OCT_20.plusDays(1).withHour(0).withMinute(1))).as("a minute apart, another day").isFalse();
    }

    @Test
    void withoutDeadlinesOnlyBothMissingMatch() {
        assertThat(AssignmentTitles.sameAssignment("СРС №2", null, "С.Р.С. 2", null)).isTrue();
        assertThat(AssignmentTitles.sameAssignment("СРС №2", null, "СРС 2", OCT_20)).isFalse();
        assertThat(AssignmentTitles.sameAssignment("СРС №2", OCT_20, "СРС 2", null)).isFalse();
    }

    @Test
    void differentNumbersAreNeverDuplicates() {
        assertThat(AssignmentTitles.sameAssignment("Лаб. 1", OCT_20, "Лаб. 2", OCT_20)).isFalse();
        assertThat(AssignmentTitles.sameAssignment("Лабораторная работа №1", null, "Лабораторная работа №12", null))
                .isFalse();
        assertThat(AssignmentTitles.sameAssignment("СРС 1", OCT_20, "СРС 2", OCT_20)).isFalse();
    }

    @Test
    void wordOrderIsKeptSoANumberInFrontIsAnotherTitle() {
        assertThat(AssignmentTitles.normalize("1 лабораторная работа")).isEqualTo("1 лабораторная работа");
        assertThat(AssignmentTitles.sameAssignment("1 лабораторная работа", OCT_20, "Лабораторная работа 1", OCT_20))
                .isFalse();
    }

    @Test
    void differentKindsWithTheSameNumberAreNotDuplicates() {
        assertThat(AssignmentTitles.sameAssignment("Лаб. 1", OCT_20, "Практика 1", OCT_20)).isFalse();
        assertThat(AssignmentTitles.sameAssignment("СРС 1", OCT_20, "СРСП 1", OCT_20)).isFalse();
    }

    @Test
    void differentWordingIsNotADuplicateAndEmptyTitlesNeverMatch() {
        assertThat(AssignmentTitles.sameAssignment("Лабораторная работа 1", OCT_20, "Лаб. 1: сортировка массивов",
                OCT_20)).as("only the same normalized title counts").isFalse();
        assertThat(AssignmentTitles.sameAssignment("Эссе по истории", OCT_20, "Курсовой проект", OCT_20)).isFalse();
        assertThat(AssignmentTitles.sameAssignment("!!!", null, "???", null)).isFalse();
        assertThat(AssignmentTitles.normalize(null)).isEmpty();
    }

    @Test
    void wordsThatOnlyStartLikeAKindOfWorkAreKept() {
        assertThat(AssignmentTitles.normalize("Лабиринт и праздник")).isEqualTo("лабиринт и праздник");
    }
}
