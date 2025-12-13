package com.polybot.hft.polymarket.order;

import com.polybot.hft.domain.OrderSide;
import com.polybot.hft.polymarket.crypto.Eip712Signer;
import com.polybot.hft.polymarket.model.SignedOrder;
import com.polybot.hft.polymarket.onchain.ContractConfig;
import org.web3j.crypto.Credentials;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

public final class PolymarketOrderBuilder {

  private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";

  private final int chainId;
  private final Credentials signerCredentials;
  private final int signatureType;
  private final String funderAddress;

  public PolymarketOrderBuilder(int chainId, Credentials signerCredentials, int signatureType, String funderAddress) {
    this.chainId = chainId;
    this.signerCredentials = Objects.requireNonNull(signerCredentials, "signerCredentials");
    this.signatureType = signatureType;
    this.funderAddress = (funderAddress == null || funderAddress.isBlank()) ? null : funderAddress;
  }

  private static void validatePrice(BigDecimal price, BigDecimal tickSize) {
    if (price == null) {
      throw new IllegalArgumentException("price must not be null");
    }
    if (tickSize == null) {
      throw new IllegalArgumentException("tickSize must not be null");
    }
    BigDecimal min = tickSize;
    BigDecimal max = BigDecimal.ONE.subtract(tickSize);
    if (price.compareTo(min) < 0 || price.compareTo(max) > 0) {
      throw new IllegalArgumentException("price must be within [" + min + ", " + max + "]");
    }
  }

  private static int decimalPlaces(BigDecimal value) {
    BigDecimal stripped = value.stripTrailingZeros();
    return Math.max(0, stripped.scale());
  }

  private static BigDecimal roundNormal(BigDecimal value, int decimals) {
    if (decimalPlaces(value) <= decimals) {
      return value;
    }
    return value.setScale(decimals, RoundingMode.HALF_UP);
  }

  private static BigDecimal roundDown(BigDecimal value, int decimals) {
    if (decimalPlaces(value) <= decimals) {
      return value;
    }
    return value.setScale(decimals, RoundingMode.DOWN);
  }

  private static BigDecimal roundUp(BigDecimal value, int decimals) {
    if (decimalPlaces(value) <= decimals) {
      return value;
    }
    return value.setScale(decimals, RoundingMode.UP);
  }

  private static BigDecimal clampAmountDecimals(BigDecimal amount, int maxDecimals) {
    if (decimalPlaces(amount) <= maxDecimals) {
      return amount;
    }

    BigDecimal roundedUp = roundUp(amount, maxDecimals + 4);
    if (decimalPlaces(roundedUp) > maxDecimals) {
      return roundDown(roundedUp, maxDecimals);
    }
    return roundedUp;
  }

  private static BigInteger toBaseUnits(BigDecimal amount, int decimals) {
    if (decimalPlaces(amount) > decimals) {
      throw new IllegalArgumentException("Amount has more than " + decimals + " decimals: " + amount);
    }
    BigDecimal scaled = amount.movePointRight(decimals);
    return scaled.setScale(0, RoundingMode.UNNECESSARY).toBigIntegerExact();
  }

  public SignedOrder buildLimitOrder(String tokenId, OrderSide side, BigDecimal price, BigDecimal size, BigDecimal tickSize, boolean negRisk, Integer feeRateBps, Long nonce, Long expirationSeconds, String taker) {
    validatePrice(price, tickSize);

    RoundConfig roundConfig = RoundConfig.fromTickSize(tickSize);
    BigDecimal rawPrice = roundNormal(price, roundConfig.priceDecimals());

    BigDecimal rawMakerAmt;
    BigDecimal rawTakerAmt;
    if (side == OrderSide.BUY) {
      rawTakerAmt = roundDown(size, roundConfig.sizeDecimals());
      rawMakerAmt = rawTakerAmt.multiply(rawPrice);
      rawMakerAmt = clampAmountDecimals(rawMakerAmt, roundConfig.amountDecimals());
    } else {
      rawMakerAmt = roundDown(size, roundConfig.sizeDecimals());
      rawTakerAmt = rawMakerAmt.multiply(rawPrice);
      rawTakerAmt = clampAmountDecimals(rawTakerAmt, roundConfig.amountDecimals());
    }

    return signOrder(tokenId, side, toBaseUnits(rawMakerAmt, ContractConfig.forChainId(chainId).collateralTokenDecimals()), toBaseUnits(rawTakerAmt, ContractConfig.forChainId(chainId).collateralTokenDecimals()), negRisk, feeRateBps, nonce, expirationSeconds, taker);
  }

  public SignedOrder buildMarketOrder(String tokenId, OrderSide side, BigDecimal amount, BigDecimal price, BigDecimal tickSize, boolean negRisk, Integer feeRateBps, Long nonce, String taker) {
    validatePrice(price, tickSize);

    RoundConfig roundConfig = RoundConfig.fromTickSize(tickSize);
    BigDecimal rawPrice = roundDown(price, roundConfig.priceDecimals());

    BigDecimal rawMakerAmt;
    BigDecimal rawTakerAmt;
    if (side == OrderSide.BUY) {
      rawMakerAmt = roundDown(amount, roundConfig.sizeDecimals());
      rawTakerAmt = rawMakerAmt.divide(rawPrice, roundConfig.amountDecimals() + 12, RoundingMode.HALF_UP);
      rawTakerAmt = clampAmountDecimals(rawTakerAmt, roundConfig.amountDecimals());
    } else {
      rawMakerAmt = roundDown(amount, roundConfig.sizeDecimals());
      rawTakerAmt = rawMakerAmt.multiply(rawPrice);
      rawTakerAmt = clampAmountDecimals(rawTakerAmt, roundConfig.amountDecimals());
    }

    return signOrder(tokenId, side, toBaseUnits(rawMakerAmt, ContractConfig.forChainId(chainId).collateralTokenDecimals()), toBaseUnits(rawTakerAmt, ContractConfig.forChainId(chainId).collateralTokenDecimals()), negRisk, feeRateBps, nonce, 0L, taker);
  }

  private SignedOrder signOrder(String tokenId, OrderSide side, BigInteger makerAmount, BigInteger takerAmount, boolean negRisk, Integer feeRateBps, Long nonce, Long expirationSeconds, String taker) {
    ContractConfig contractConfig = ContractConfig.forChainId(chainId);
    String exchangeContract = negRisk ? contractConfig.negRiskExchange() : contractConfig.exchange();

    String signerAddress = signerCredentials.getAddress();
    String makerAddress = (funderAddress == null) ? signerAddress : funderAddress;
    String takerAddress = (taker == null || taker.isBlank()) ? ZERO_ADDRESS : taker;

    String salt = Long.toString(Math.round(ThreadLocalRandom.current().nextDouble() * (double) Instant.now().toEpochMilli()));

    SignedOrder unsignedOrder = new SignedOrder(salt, makerAddress, signerAddress, takerAddress, tokenId, makerAmount.toString(), takerAmount.toString(), Long.toString(expirationSeconds == null ? 0L : expirationSeconds), Long.toString(nonce == null ? 0L : nonce), Integer.toString(feeRateBps == null ? 0 : feeRateBps), side, signatureType, "");

    String signature = Eip712Signer.signOrder(signerCredentials, chainId, exchangeContract, unsignedOrder.salt(), unsignedOrder.maker(), unsignedOrder.signer(), unsignedOrder.taker(), unsignedOrder.tokenId(), unsignedOrder.makerAmount(), unsignedOrder.takerAmount(), unsignedOrder.expiration(), unsignedOrder.nonce(), unsignedOrder.feeRateBps(), unsignedOrder.side().toEip712Value(), unsignedOrder.signatureType());

    return new SignedOrder(unsignedOrder.salt(), unsignedOrder.maker(), unsignedOrder.signer(), unsignedOrder.taker(), unsignedOrder.tokenId(), unsignedOrder.makerAmount(), unsignedOrder.takerAmount(), unsignedOrder.expiration(), unsignedOrder.nonce(), unsignedOrder.feeRateBps(), unsignedOrder.side(), unsignedOrder.signatureType(), signature);
  }

  private record RoundConfig(int priceDecimals, int sizeDecimals, int amountDecimals) {
    static RoundConfig fromTickSize(BigDecimal tickSize) {
      int priceDecimals = Math.max(0, tickSize.stripTrailingZeros().scale());
      return new RoundConfig(priceDecimals, 2, priceDecimals + 2);
    }
  }
}
