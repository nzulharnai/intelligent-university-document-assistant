package com.university.docassistant.util;

import java.time.YearMonth;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Finds personal data in a document's text and replaces it with placeholders, so the text can go to the external AI,
 * into search and into Q&A chunks. Pure and stateless. Deliberately narrow: dates, years, course codes, page numbers
 * and scores never match any pattern.
 * <ul>
 *   <li>IIN: a 12-digit token that is not part of a longer number, and either has a valid IIN checksum and birth date,
 *       or follows the word "ИИН"/"ЖСН"/"IIN" (a mistyped IIN next to its label is still an IIN). A random 12-digit
 *       number (an order or invoice number) passes the checksum and the date check only rarely. The same rule
 *       applies to an IIN written in groups, 6+6 or 4+4+4 digits with one space or dash between them ("990101 300013",
 *       "9901-0130-0013"); a grouped number right after "№", "No" or "номер" is taken for an order or document number
 *       unless the IIN label is there too.</li>
 *   <li>Kazakhstan phone: +7 or 8, then 10 digits starting with 7 (all Kazakh codes do), with spaces, dashes or
 *       brackets between them.</li>
 *   <li>E-mail.</li>
 *   <li>ID card / passport number: 9 digits or N + 8 digits within a few words after "удостоверение", "паспорт",
 *       "№ документа", "номер документа" or "жеке куәлік".</li>
 * </ul>
 * Not masked: e-mails on public domains (and their subdomains), and the given e-mails and phones (the course
 * teacher's own contacts).
 */
public final class PersonalDataMasker {

    /** Bump whenever a pattern changes: stored texts masked by an older version are masked again at startup. */
    public static final int VERSION = 2;

    private PersonalDataMasker() {
    }

    public enum PiiType {
        EMAIL("[e-mail скрыт]"),
        ID_DOCUMENT("[номер документа скрыт]"),
        IIN("[ИИН скрыт]"),
        PHONE("[телефон скрыт]");

        private final String placeholder;

        PiiType(String placeholder) {
            this.placeholder = placeholder;
        }

        public String placeholder() {
            return placeholder;
        }
    }

    /** What must stay readable. E-mails and domains are compared case-insensitively, phones by their last 10 digits. */
    public record Exemptions(Set<String> publicEmailDomains, Set<String> emails, Set<String> phones) {

        public static final Exemptions NONE = new Exemptions(Set.of(), Set.of(), Set.of());

        public Exemptions {
            publicEmailDomains = lowerCase(publicEmailDomains);
            emails = lowerCase(emails);
            phones = phones == null ? Set.of() : phones.stream()
                    .map(PersonalDataMasker::lastTenDigits).filter(digits -> digits.length() == 10)
                    .collect(Collectors.toUnmodifiableSet());
        }

        private static Set<String> lowerCase(Set<String> values) {
            return values == null ? Set.of() : values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.strip().toLowerCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    /** The masked text, how many items were replaced and of which kinds ("IIN,PHONE"; null when none). */
    public record Result(String text, int count, Set<PiiType> types) {

        public String typesText() {
            return types.isEmpty() ? null : types.stream().sorted().map(Enum::name).collect(Collectors.joining(","));
        }
    }

    private static final Pattern EMAIL = Pattern.compile(
            "(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+)");

    private static final Pattern ID_DOCUMENT = Pattern.compile(
            "(удостоверени\\p{L}*|паспорт\\p{L}*|№\\s*документа|номер\\s+документа|жеке\\s+куәлі\\p{L}*)"
                    + "([^\\d\\n]{0,40}?)"
                    + "(?<![\\p{L}\\d])([NН]\\s?\\d{8}|\\d{9})(?!\\d)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern TWELVE_DIGITS = Pattern.compile("(?<!\\d)\\d{12}(?!\\d)");
    private static final Pattern IIN_LABEL = Pattern.compile("(?:иин|жсн|iin)\\W{0,15}$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
    /**
     * 6+6 or 4+4+4 digits, the same single space or dash between all groups, not part of a longer run of digit groups
     * (a card number "1234 5678 9012 3456", a phone "+7 7011 2345 67").
     */
    private static final Pattern GROUPED_TWELVE_DIGITS = Pattern.compile(
            "(?<![\\d+])(?<!\\d[ -])(?:\\d{6}([ -])\\d{6}|\\d{4}([ -])\\d{4}\\2\\d{4})(?![ -]?\\d)");
    /** An order, document or registration number: "№ 9901 0130 0013", "No. 990101-300013", "номер 990101 300013". */
    private static final Pattern NUMBER_LABEL = Pattern.compile("(?:№|\\bno\\.?|номер\\p{L}*)\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\d+\\p{L}])(?:\\+7|8)[\\s-]?[(]?7(?:[\\s()-]{0,2}\\d){9}(?!\\d)",
            Pattern.UNICODE_CHARACTER_CLASS);

    public static Result mask(String text, Exemptions exemptions) {
        if (text == null || text.isEmpty()) {
            return new Result(text, 0, Set.of());
        }
        Exemptions keep = exemptions == null ? Exemptions.NONE : exemptions;
        int[] count = {0};
        Set<PiiType> types = EnumSet.noneOf(PiiType.class);

        String masked = replace(text, EMAIL, match -> {
            String domain = match.group(1).toLowerCase(Locale.ROOT);
            if (keep.emails().contains(match.group().toLowerCase(Locale.ROOT)) || isPublicDomain(domain, keep)) {
                return null;
            }
            return hit(PiiType.EMAIL, count, types);
        });
        masked = replace(masked, ID_DOCUMENT, match ->
                match.group(1) + match.group(2) + hit(PiiType.ID_DOCUMENT, count, types));
        String current = masked;
        masked = replace(masked, TWELVE_DIGITS, match -> {
            if (partOfLongerNumber(current, match.start(), match.end())) {
                return null;
            }
            boolean labelled = IIN_LABEL.matcher(current.substring(Math.max(0, match.start() - 20), match.start())).find();
            return labelled || isValidIin(match.group()) ? hit(PiiType.IIN, count, types) : null;
        });
        String grouped = masked;
        masked = replace(masked, GROUPED_TWELVE_DIGITS, match -> {
            String before = grouped.substring(Math.max(0, match.start() - 20), match.start());
            if (IIN_LABEL.matcher(before).find()) {
                return hit(PiiType.IIN, count, types);
            }
            boolean numbered = NUMBER_LABEL.matcher(before).find();
            return !numbered && isValidIin(match.group().replaceAll("\\D", "")) ? hit(PiiType.IIN, count, types) : null;
        });
        masked = replace(masked, PHONE, match ->
                keep.phones().contains(lastTenDigits(match.group())) ? null : hit(PiiType.PHONE, count, types));
        return new Result(masked, count[0], types.isEmpty() ? Set.of() : Set.copyOf(types));
    }

    /**
     * The Kazakhstan IIN rule: YYMMDD of a real date, a century/sex digit 1-6, and the control digit - the sum of the
     * first 11 digits weighted 1..11 modulo 11, or with weights 3..11,1,2 when that gives 10 (10 again: invalid).
     */
    public static boolean isValidIin(String digits) {
        if (digits == null || !digits.matches("\\d{12}")) {
            return false;
        }
        int[] d = digits.chars().map(c -> c - '0').toArray();
        int centuryDigit = d[6];
        if (centuryDigit < 1 || centuryDigit > 6) {
            return false;
        }
        int year = (centuryDigit <= 2 ? 1800 : centuryDigit <= 4 ? 1900 : 2000) + d[0] * 10 + d[1];
        int month = d[2] * 10 + d[3];
        int day = d[4] * 10 + d[5];
        if (month < 1 || month > 12 || day < 1 || day > YearMonth.of(year, month).lengthOfMonth()) {
            return false;
        }
        int control = weightedSum(d, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}) % 11;
        if (control == 10) {
            control = weightedSum(d, new int[]{3, 4, 5, 6, 7, 8, 9, 10, 11, 1, 2}) % 11;
            if (control == 10) {
                return false;
            }
        }
        return control == d[11];
    }

    private static int weightedSum(int[] digits, int[] weights) {
        int sum = 0;
        for (int i = 0; i < 11; i++) {
            sum += digits[i] * weights[i];
        }
        return sum;
    }

    private static boolean isPublicDomain(String domain, Exemptions keep) {
        return keep.publicEmailDomains().stream()
                .anyMatch(publicDomain -> domain.equals(publicDomain) || domain.endsWith("." + publicDomain));
    }

    /** "1.234567890123" or "234567890123,5": the 12 digits are a piece of a decimal number, not an IIN. */
    private static boolean partOfLongerNumber(String text, int start, int end) {
        boolean before = start >= 2 && (text.charAt(start - 1) == '.' || text.charAt(start - 1) == ',')
                && Character.isDigit(text.charAt(start - 2));
        boolean after = end + 1 < text.length() && (text.charAt(end) == '.' || text.charAt(end) == ',')
                && Character.isDigit(text.charAt(end + 1));
        return before || after;
    }

    private static String lastTenDigits(String value) {
        String digits = value == null ? "" : value.replaceAll("\\D", "");
        return digits.length() <= 10 ? digits : digits.substring(digits.length() - 10);
    }

    private static String hit(PiiType type, int[] count, Set<PiiType> types) {
        count[0]++;
        types.add(type);
        return type.placeholder();
    }

    /** Replaces each match with what {@code replacement} returns; null keeps the match as it is. */
    private static String replace(String text, Pattern pattern, Function<Matcher, String> replacement) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        while (matcher.find()) {
            String value = replacement.apply(matcher);
            if (value != null) {
                out.append(text, last, matcher.start()).append(value);
                last = matcher.end();
            }
        }
        return last == 0 ? text : out.append(text, last, text.length()).toString();
    }
}
