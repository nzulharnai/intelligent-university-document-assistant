package com.university.docassistant.util;

import java.util.regex.Pattern;

/** Phone numbers as stored in users.phone: digits only, 10 to 15 of them ("+7 (701) 123-45-67" -> "77011234567"). */
public final class PhoneNumbers {

    private static final Pattern ALLOWED = Pattern.compile("\\+?[\\d\\s()-]+");
    private static final int MIN_DIGITS = 10;
    private static final int MAX_DIGITS = 15;

    private PhoneNumbers() {
    }

    /**
     * The digits of {@code input}; null for a blank or null input (no phone). IllegalArgumentException when it is not
     * a phone number: letters or other characters, a "+" not at the start, or fewer than 10 or more than 15 digits.
     */
    public static String normalize(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String value = input.strip();
        String digits = value.replaceAll("\\D", "");
        if (!ALLOWED.matcher(value).matches() || digits.length() < MIN_DIGITS || digits.length() > MAX_DIGITS) {
            throw new IllegalArgumentException("Not a phone number");
        }
        return digits;
    }
}
