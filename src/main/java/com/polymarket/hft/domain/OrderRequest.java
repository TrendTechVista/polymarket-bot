package com.polymarket.hft.domain;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record OrderRequest(
        @NotBlank String instrument,
        @NotNull OrderSide side,
        @DecimalMin(value = "0.0001") BigDecimal price,
        @DecimalMin(value = "0.0001") BigDecimal quantity
) {
}
