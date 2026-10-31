package com.university.docassistant.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhoneNumbersTest {

    @Test
    void keepsOnlyTheDigits() {
        assertThat(PhoneNumbers.normalize("+7 (701) 123-45-67")).isEqualTo("77011234567");
        assertThat(PhoneNumbers.normalize(" 8 701 123 45 67 ")).isEqualTo("87011234567");
        assertThat(PhoneNumbers.normalize("7011234567")).isEqualTo("7011234567");
    }

    @Test
    void blankMeansNoPhone() {
        assertThat(PhoneNumbers.normalize(null)).isNull();
        assertThat(PhoneNumbers.normalize("   ")).isNull();
    }

    @Test
    void rejectsWhatIsNotAPhoneNumber() {
        for (String input : new String[]{"12345", "1234567890123456", "+7 701 ABC 45 67", "7701+1234567",
                "звоните 87011234567", "8.701.123.45.67"}) {
            assertThatThrownBy(() -> PhoneNumbers.normalize(input)).as(input).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
