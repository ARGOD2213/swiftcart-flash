package dev.mahindra.swiftcart.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PhonesTest {

    @ParameterizedTest
    @ValueSource(strings = {"9876543210", "98765 43210", "+91 98765 43210", "+91-98765-43210", "919876543210", "09876543210",
            "0091 9876543210", "(+91) 98765.43210"})
    void everyWayOfWritingOneMobileIsOnePhone(String raw) {
        assertThat(Phones.normalizeIndianMobile(raw)).hasValue("+919876543210");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "12345", "5876543210", "98765432100", "+1 415 555 0100", "abcdefghij", "+9198765432"})
    void anythingElseIsRejected(String raw) {
        assertThat(Phones.normalizeIndianMobile(raw)).isEmpty();
    }
}
