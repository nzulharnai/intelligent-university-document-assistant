package com.university.docassistant.util;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Compares assignment titles the way a teacher would: "Лаб. работа №1", "Лабораторная работа 1" and "ЛАБ.1" are the
 * same assignment, "Лаб. 1" and "Лаб. 2" never are. Used to skip an AI assignment the course already has (a syllabus
 * and the assignment file both mention it).
 */
public final class AssignmentTitles {

    private AssignmentTitles() {
    }

    private static final int FLAGS = Pattern.UNICODE_CHARACTER_CLASS;
    private static final Pattern LETTER_THEN_DIGIT = Pattern.compile("(\\p{L})(\\d)");
    private static final Pattern DIGIT_THEN_LETTER = Pattern.compile("(\\d)(\\p{L})");
    private static final Pattern NOT_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private static final String LAB = "лабораторная работа";
    private static final String PRACTICE = "практическая работа";
    private static final String SRSP = "срсп";
    private static final String SRS = "срс";

    /** Spellings of each kind of work, applied in order (СРСП before СРС); Latin look-alikes of с/о are accepted. */
    private record Rule(Pattern pattern, String replacement) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("\\b(?:[сc]р[сc]п|[сc]р[оo]п|[сc] р [сc] п|[сc] р [оo] п"
                    + "|самостоятельн\\w* работ\\w* (?:студент\\w*|обучающ\\w*) (?:под руководством|с) преподавател\\w*)\\b",
                    FLAGS), SRSP),
            new Rule(Pattern.compile("\\b(?:[сc]р[сc]|[сc]р[оo]|[сc] р [сc]|[сc] р [оo]"
                    + "|самостоятельн\\w* работ\\w*(?: (?:студент\\w*|обучающ\\w*))?)\\b", FLAGS), SRS),
            new Rule(Pattern.compile("\\b(?:лабораторн\\w*|лаб(?:а|ы|у|ка|ки)?)(?: раб\\w*)?\\b", FLAGS), LAB),
            new Rule(Pattern.compile("\\b(?:практическ\\w*(?: (?:раб\\w*|занят\\w*))?|практик\\w*|пр(?: раб\\w*)?)\\b",
                    FLAGS), PRACTICE));

    /**
     * Lower case, "ё" as "е", without "№", punctuation and extra spaces, every spelling of a kind of work unified;
     * numbers are kept. "Лаб. работа №1: Сортировка" -> "лабораторная работа 1 сортировка". Never null.
     */
    public static String normalize(String title) {
        if (title == null) {
            return "";
        }
        String text = title.toLowerCase(Locale.ROOT).replace('ё', 'е').replace("№", " ");
        text = LETTER_THEN_DIGIT.matcher(text).replaceAll("$1 $2");
        text = DIGIT_THEN_LETTER.matcher(text).replaceAll("$1 $2");
        text = NOT_ALPHANUMERIC.matcher(text).replaceAll(" ");
        text = SPACES.matcher(text).replaceAll(" ").strip();
        for (Rule rule : RULES) {
            text = rule.pattern().matcher(text).replaceAll(rule.replacement());
        }
        return SPACES.matcher(text).replaceAll(" ").strip();
    }

    /**
     * Whether two assignments are the same one: the same normalized title (never an empty one) and deadlines on the same
     * calendar day, or neither has a deadline. Numbers are part of the title, so "Лаб. 1" and "Лаб. 2" never are.
     */
    public static boolean sameAssignment(String titleA, LocalDateTime deadlineA, String titleB, LocalDateTime deadlineB) {
        String a = normalize(titleA);
        if (a.isEmpty() || !a.equals(normalize(titleB))) {
            return false;
        }
        return deadlineA == null ? deadlineB == null
                : deadlineB != null && deadlineA.toLocalDate().equals(deadlineB.toLocalDate());
    }
}
