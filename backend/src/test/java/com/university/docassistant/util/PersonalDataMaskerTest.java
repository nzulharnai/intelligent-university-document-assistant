package com.university.docassistant.util;

import com.university.docassistant.util.PersonalDataMasker.Exemptions;
import com.university.docassistant.util.PersonalDataMasker.PiiType;
import com.university.docassistant.util.PersonalDataMasker.Result;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PersonalDataMaskerTest {

    // Valid IINs (checksum and birth date): 1999-01-01 male, 2005-03-15 male, 2001-02-22 female.
    private static final String IIN_1 = "990101300013";
    private static final String IIN_2 = "050315501230";
    private static final String IIN_3 = "010222607898";

    private static String mask(String text) {
        return PersonalDataMasker.mask(text, Exemptions.NONE).text();
    }

    @Nested
    class Iin {

        @Test
        void validIinIsMaskedWithOrWithoutItsLabel() {
            assertThat(mask("Студент Иванов, ИИН " + IIN_1 + ", группа CS-21"))
                    .isEqualTo("Студент Иванов, ИИН [ИИН скрыт], группа CS-21");
            assertThat(mask(IIN_2 + " " + IIN_3)).isEqualTo("[ИИН скрыт] [ИИН скрыт]");
        }

        @Test
        void twelveDigitOrderNumberIsNotAnIin() {
            // Bad checksum and no valid birth date: an order number, not personal data.
            assertThat(mask("Номер заказа 123456789012 от 12.10.2026")).isEqualTo("Номер заказа 123456789012 от 12.10.2026");
            assertThat(PersonalDataMasker.isValidIin("123456789012")).isFalse();
        }

        @Test
        void labelledIinIsMaskedEvenWithATypo() {
            assertThat(mask("ИИН: 990101300019")).isEqualTo("ИИН: [ИИН скрыт]");
            assertThat(mask("ЖСН 990101300019")).isEqualTo("ЖСН [ИИН скрыт]");
        }

        @Test
        void twelveDigitsInsideALongerNumberAreNotAnIin() {
            assertThat(mask("Счёт 1" + IIN_1)).isEqualTo("Счёт 1" + IIN_1); // 13 digits
            assertThat(mask("Код " + IIN_1 + "7")).isEqualTo("Код " + IIN_1 + "7");
            assertThat(mask("Сумма 3." + IIN_1)).isEqualTo("Сумма 3." + IIN_1);
        }

        @Test
        void checksumAndDateRules() {
            assertThat(PersonalDataMasker.isValidIin(IIN_1)).isTrue();
            assertThat(PersonalDataMasker.isValidIin("990101300014")).isFalse(); // wrong control digit
            assertThat(PersonalDataMasker.isValidIin("991301300013")).isFalse(); // month 13
            assertThat(PersonalDataMasker.isValidIin("990101900013")).isFalse(); // century digit 9
        }
    }

    /** Review fix: an IIN written in groups, 6+6 or 4+4+4 digits with one space or dash between them. */
    @Nested
    class GroupedIin {

        @Test
        void validIinInGroupsIsMasked() {
            assertThat(mask("ИИН 990101 300013, группа CS-21")).isEqualTo("ИИН [ИИН скрыт], группа CS-21");
            assertThat(mask("Иванов А. 990101-300013")).isEqualTo("Иванов А. [ИИН скрыт]");
            assertThat(mask("Иванов А. 9901 0130 0013.")).isEqualTo("Иванов А. [ИИН скрыт].");
            assertThat(mask("(0503-1550-1230)")).isEqualTo("([ИИН скрыт])");
        }

        @Test
        void labelledGroupsAreMaskedEvenWithATypo() {
            assertThat(mask("ЖСН: 9901 0130 0019")).isEqualTo("ЖСН: [ИИН скрыт]");
            assertThat(mask("IIN 990101-300019")).isEqualTo("IIN [ИИН скрыт]");
        }

        @Test
        void datesAndYearsAreNotIins() {
            for (String text : List.of("Учебный год 2024 2025 2026", "2025-2026-2027 гг.", "Сессия 202512 202601",
                    "с 01-09-2024 по 31-12-2024", "Период 2024-09 2025-05")) {
                assertThat(mask(text)).as(text).isEqualTo(text);
            }
        }

        @Test
        void phoneNumbersAreNotIinsAndStayPhones() {
            assertThat(mask("тел. +7 7011 2345 67")).isEqualTo("тел. [телефон скрыт]");
            assertThat(mask("8 701 123 45 67")).isEqualTo("[телефон скрыт]");
            assertThat(mask("+7 701123 456789")).doesNotContain("[ИИН скрыт]");
        }

        @Test
        void orderAndDocumentNumbersAreNotIins() {
            // A valid IIN checksum and date, but explicitly an order / registration number.
            for (String text : List.of("Приказ № 9901 0130 0013 от 12.10.2026", "Договор No. 990101-300013",
                    "регистрационный номер 990101 300013")) {
                assertThat(mask(text)).as(text).isEqualTo(text);
            }
            assertThat(mask("Номер заказа 1234 5678 9012")).isEqualTo("Номер заказа 1234 5678 9012"); // bad checksum
        }

        @Test
        void longerDigitGroupsAndMixedSeparatorsAreLeftAlone() {
            for (String text : List.of("Карта 9901 0130 0013 4567", "1 990101 300013", "9901 0130-0013",
                    "990101  300013", "Счёт 990101-300013-7")) {
                assertThat(mask(text)).as(text).isEqualTo(text);
            }
        }

        @Test
        void maskerVersionWasBumpedSoStoredTextsAreMaskedAgain() {
            assertThat(PersonalDataMasker.VERSION).isGreaterThanOrEqualTo(2);
        }
    }

    @Nested
    class Phone {

        @ParameterizedTest
        @ValueSource(strings = {"+7 701 123 45 67", "+7 (701) 123-45-67", "8 701 123 45 67", "87011234567",
                "+77011234567", "8-701-123-45-67", "8 (7172) 12-34-56", "+7 7172 123456"})
        void kazakhPhoneFormatsAreMasked(String phone) {
            assertThat(mask("Тел.: " + phone + ".")).isEqualTo("Тел.: [телефон скрыт].");
        }

        @ParameterizedTest
        @ValueSource(strings = {"+7 495 123 45 67", "8 800 555 35 35", "87 баллов из 100", "2025-2026 учебный год",
                "8 701 123", "Неделя 8, 7 занятий, 12 часов"})
        void otherNumbersAreNotPhones(String text) {
            assertThat(mask(text)).isEqualTo(text);
        }

        @Test
        void exemptPhoneIsKept() {
            Exemptions teacher = new Exemptions(Set.of(), Set.of(), Set.of("+7 (701) 123-45-67"));
            assertThat(PersonalDataMasker.mask("Звоните 87011234567, деканат 87019998877", teacher).text())
                    .isEqualTo("Звоните 87011234567, деканат [телефон скрыт]");
        }
    }

    @Nested
    class Email {

        @Test
        void emailIsMasked() {
            assertThat(mask("Пишите на ivanov.a@gmail.com.")).isEqualTo("Пишите на [e-mail скрыт].");
        }

        @Test
        void publicDomainAndItsSubdomainsAreKept() {
            Exemptions sdu = new Exemptions(Set.of("SDU.edu.kz"), Set.of(), Set.of());
            assertThat(PersonalDataMasker.mask("Деканат: dean@sdu.edu.kz, кафедра it@cs.sdu.edu.kz, "
                    + "студент: s1@mail.ru, подделка: x@notsdu.edu.kz", sdu).text())
                    .isEqualTo("Деканат: dean@sdu.edu.kz, кафедра it@cs.sdu.edu.kz, студент: [e-mail скрыт], "
                            + "подделка: [e-mail скрыт]");
        }

        @Test
        void teachersOwnEmailIsKept() {
            Exemptions teacher = new Exemptions(Set.of(), Set.of("A.Kim@Gmail.com"), Set.of());
            assertThat(PersonalDataMasker.mask("Преподаватель: a.kim@gmail.com; ассистент: b.lee@gmail.com", teacher)
                    .text()).isEqualTo("Преподаватель: a.kim@gmail.com; ассистент: [e-mail скрыт]");
        }
    }

    @Nested
    class IdDocument {

        @ParameterizedTest
        @ValueSource(strings = {"Удостоверение личности № 012345678", "удостоверение: 012345678",
                "Паспорт N12345678", "паспорт гражданина РК N 12345678", "№ документа 012345678",
                "Жеке куәлік 012345678"})
        void idNumbersNextToTheirWordsAreMasked(String text) {
            Result result = PersonalDataMasker.mask(text, Exemptions.NONE);
            assertThat(result.text()).endsWith("[номер документа скрыт]").doesNotContain("12345678");
            assertThat(result.types()).containsExactly(PiiType.ID_DOCUMENT);
        }

        @Test
        void nineDigitsWithoutAnIdWordAreKept() {
            assertThat(mask("Аудитория 501, тираж 123456789 экз.")).isEqualTo("Аудитория 501, тираж 123456789 экз.");
        }
    }

    @Test
    void syllabusDataIsNeverMasked() {
        String syllabus = """
                Курс CS101 «Алгоритмы», 2025-2026 учебный год, 5 кредитов (ECTS).
                Лабораторная работа №3: сдать до 20.10.2026 23:59, стр. 12-15, максимум 100 баллов (20%).
                Экзамен 15 декабря 2026 г. в 10:00, ауд. 301. Итог = 0.6 * РК + 0.4 * экзамен.
                """;
        Result result = PersonalDataMasker.mask(syllabus, Exemptions.NONE);
        assertThat(result.text()).isEqualTo(syllabus);
        assertThat(result.count()).isZero();
        assertThat(result.typesText()).isNull();
    }

    @Test
    void countsAndTypesOfEverythingMasked() {
        Result result = PersonalDataMasker.mask("ИИН " + IIN_1 + ", тел. 87011234567, тел. +7 777 000 11 22, "
                + "a@b.kz", Exemptions.NONE);
        assertThat(result.count()).isEqualTo(4);
        assertThat(result.typesText()).isEqualTo("EMAIL,IIN,PHONE");
        assertThat(result.text()).doesNotContain(IIN_1).doesNotContain("87011234567").doesNotContain("a@b.kz");
    }

    @Test
    void emptyTextStaysAsItIs() {
        assertThat(PersonalDataMasker.mask(null, Exemptions.NONE).text()).isNull();
        assertThat(PersonalDataMasker.mask("", null).count()).isZero();
    }
}
