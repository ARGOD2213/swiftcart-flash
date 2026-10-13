package dev.mahindra.swiftcart.checkout;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What the shopper sends. Deliberately no price or amount: the server prices the order from the SKU, so a
 * tampered client can't pay less.
 */
public record CheckoutRequest(
        @NotBlank @Size(max = 40) String sku,
        @Min(1) @Max(10) int quantity,
        @NotBlank @Size(max = 20) String phone,
        @NotNull @Valid Address address) {

    public record Address(
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Size(max = 120) String line1,
            @Size(max = 120) String line2,
            @NotBlank @Size(max = 60) String city,
            @NotBlank @Size(max = 60) String state,
            @NotBlank @Pattern(regexp = "^[1-9][0-9]{5}$", message = "must be a 6-digit PIN code") String pincode) {
    }
}
