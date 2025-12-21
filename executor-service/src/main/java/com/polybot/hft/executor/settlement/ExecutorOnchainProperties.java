package com.polybot.hft.executor.settlement;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;

@Validated
@ConfigurationProperties(prefix = "executor.onchain")
public record ExecutorOnchainProperties(
    /**
     * Polygon (or compatible) JSON-RPC endpoint used to broadcast settlement transactions.
     */
    URI rpcUrl,
    /**
     * Polymarket ProxyWalletFactory address for the configured chain.
     * This is the only contract we call directly; it forwards calls to the user's proxy wallet.
     */
    String proxyWalletFactoryAddress,
    /**
     * Fallback gas limit when estimation fails.
     */
    @NotNull @Min(21_000) Long fallbackGasLimit,
    /**
     * Multiplier applied to eth_estimateGas result.
     */
    @NotNull @DecimalMin("0.0") Double gasLimitMultiplier,
    /**
     * Multiplier applied to eth_gasPrice.
     */
    @NotNull @DecimalMin("0.0") Double gasPriceMultiplier,
    /**
     * Receipt polling interval.
     */
    @NotNull @Min(100) Long receiptPollIntervalMillis,
    /**
     * Receipt polling attempts.
     */
    @NotNull @Min(1) Integer receiptPollAttempts
) {
  public ExecutorOnchainProperties {
    if (rpcUrl == null) {
      rpcUrl = URI.create("https://polygon-rpc.com");
    }
    if (proxyWalletFactoryAddress == null || proxyWalletFactoryAddress.isBlank()) {
      // Polygon mainnet ProxyWalletFactory (Polymarket).
      proxyWalletFactoryAddress = "0xab45c5a4b0c941a2f231c04c3f49182e1a254052";
    }
    if (fallbackGasLimit == null) {
      fallbackGasLimit = 1_000_000L;
    }
    if (gasLimitMultiplier == null) {
      gasLimitMultiplier = 1.25;
    }
    if (gasPriceMultiplier == null) {
      gasPriceMultiplier = 1.10;
    }
    if (receiptPollIntervalMillis == null) {
      receiptPollIntervalMillis = 1_000L;
    }
    if (receiptPollAttempts == null) {
      receiptPollAttempts = 60;
    }
  }
}

