package dev.mahindra.swiftcart.checkout;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Indian mobile numbers to E.164 ({@code +91XXXXXXXXXX}). Normalising matters for limits: "98765 43210",
 * "+91-9876543210" and "09876543210" are one phone, so they share one per-phone purchase limit.
 */
public final class Phones {

    private static final Pattern MOBILE = Pattern.compile("^[6-9]\\d{9}$");

    private Phones() {
    }

    public static Optional<String> normalizeIndianMobile(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String digits = raw.replaceAll("[\\s\\-().]", "");
        if (digits.startsWith("+91")) {
            digits = digits.substring(3);
        } else if (digits.startsWith("0091")) {
            digits = digits.substring(4);
        } else if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        } else if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        return MOBILE.matcher(digits).matches() ? Optional.of("+91" + digits) : Optional.empty();
    }
}
