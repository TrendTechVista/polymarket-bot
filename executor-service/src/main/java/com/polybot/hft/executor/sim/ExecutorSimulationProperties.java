package com.polybot.hft.executor.sim;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "executor.sim")
public record ExecutorSimulationProperties(
    @NotNull Boolean enabled,
    /**
     * Username to attribute simulated fills to (written as polymarket.user.trade events).
     */
    String username,
    /**
     * Proxy address to attribute simulated fills to. Does not need to be a real Polygon address.
     */
    String proxyAddress,
    /**
     * When enabled, simulate fills and publish polymarket.user.trade events.
     */
    @NotNull Boolean fillsEnabled,
    /**
     * Fill simulation poll interval.
     */
    @NotNull @Min(50) Long fillPollMillis,
    /**
     * Probability (0..1) of a maker-like partial fill per poll when our bid is competitive.
     */
    @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double makerFillProbabilityPerPoll,
    /**
     * Fraction (0..1) of remaining size to fill when a maker-like fill triggers.
     */
    @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double makerFillFractionOfRemaining
) {
  public ExecutorSimulationProperties {
    if (enabled == null) {
      enabled = false;
    }
    if (username == null || username.isBlank()) {
      username = "polybot-sim";
    }
    if (proxyAddress == null || proxyAddress.isBlank()) {
      proxyAddress = "sim";
    }
    if (fillsEnabled == null) {
      fillsEnabled = true;
    }
    if (fillPollMillis == null) {
      fillPollMillis = 250L;
    }
    if (makerFillProbabilityPerPoll == null) {
      makerFillProbabilityPerPoll = 0.03;
    }
    if (makerFillFractionOfRemaining == null) {
      makerFillFractionOfRemaining = 0.25;
    }
  }
}

