package com.polybot.hft.executor.settlement;

import com.polybot.hft.config.HftProperties;
import com.polybot.hft.polymarket.auth.PolymarketAuthContext;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthEstimateGas;
import org.web3j.protocol.core.methods.response.EthGasPrice;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class ProxyWalletFactoryTxSender {

  private final @NonNull HftProperties properties;
  private final @NonNull PolymarketAuthContext authContext;
  private final @NonNull ExecutorOnchainProperties onchainProperties;

  private volatile Web3j web3j;

  private Web3j web3j() {
    Web3j existing = web3j;
    if (existing != null) {
      return existing;
    }
    synchronized (this) {
      if (web3j == null) {
        web3j = Web3j.build(new HttpService(onchainProperties.rpcUrl().toString()));
      }
      return web3j;
    }
  }

  public TransactionReceipt sendFactoryProxyTx(String calldataHex) throws IOException, InterruptedException {
    Credentials signer = authContext.requireSignerCredentials();
    String from = signer.getAddress();
    String to = onchainProperties.proxyWalletFactoryAddress();
    BigInteger valueWei = BigInteger.ZERO;

    BigInteger gasPrice = resolveGasPrice();
    BigInteger gasLimit = resolveGasLimit(from, to, calldataHex, valueWei);

    org.web3j.crypto.RawTransaction rawTx = org.web3j.crypto.RawTransaction.createTransaction(
        resolveNonce(from),
        gasPrice,
        gasLimit,
        to,
        valueWei,
        calldataHex
    );

    byte[] signed = org.web3j.crypto.TransactionEncoder.signMessage(rawTx, properties.polymarket().chainId(), signer);
    String hexValue = org.web3j.utils.Numeric.toHexString(signed);

    EthSendTransaction send = web3j().ethSendRawTransaction(hexValue).send();
    if (send.hasError()) {
      throw new IOException("eth_sendRawTransaction error: " + send.getError().getMessage());
    }

    String txHash = send.getTransactionHash();
    log.info("settlement tx sent (hash={}, gasPrice={}, gasLimit={})", txHash, gasPrice, gasLimit);

    TransactionReceipt receipt = waitForReceipt(txHash);
    String status = receipt.getStatus();
    if (status != null && status.equalsIgnoreCase("0x0")) {
      throw new IOException("settlement tx reverted (hash=" + txHash + ")");
    }
    log.info("settlement tx confirmed (hash={}, status={})", txHash, status);
    return receipt;
  }

  private BigInteger resolveNonce(String from) throws IOException {
    return web3j().ethGetTransactionCount(from, org.web3j.protocol.core.DefaultBlockParameterName.PENDING)
        .send()
        .getTransactionCount();
  }

  private BigInteger resolveGasPrice() throws IOException {
    EthGasPrice gasPrice = web3j().ethGasPrice().send();
    BigInteger base = gasPrice.getGasPrice();
    BigDecimal scaled = new BigDecimal(base).multiply(BigDecimal.valueOf(onchainProperties.gasPriceMultiplier()));
    return scaled.setScale(0, RoundingMode.CEILING).toBigIntegerExact();
  }

  private BigInteger resolveGasLimit(String from, String to, String data, BigInteger valueWei) throws IOException {
    BigInteger fallback = BigInteger.valueOf(onchainProperties.fallbackGasLimit());
    try {
      Transaction tx = Transaction.createFunctionCallTransaction(from, null, null, null, to, valueWei, data);
      EthEstimateGas estimate = web3j().ethEstimateGas(tx).send();
      if (estimate.hasError() || estimate.getAmountUsed() == null) {
        return fallback;
      }
      BigInteger used = estimate.getAmountUsed();
      BigDecimal scaled = new BigDecimal(used).multiply(BigDecimal.valueOf(onchainProperties.gasLimitMultiplier()));
      BigInteger limit = scaled.setScale(0, RoundingMode.CEILING).toBigIntegerExact();
      return limit.max(BigInteger.valueOf(21_000L));
    } catch (Exception e) {
      return fallback;
    }
  }

  private TransactionReceipt waitForReceipt(String txHash) throws IOException, InterruptedException {
    long sleepMillis = onchainProperties.receiptPollIntervalMillis();
    int attempts = onchainProperties.receiptPollAttempts();

    for (int i = 0; i < attempts; i++) {
      EthGetTransactionReceipt resp = web3j().ethGetTransactionReceipt(txHash).send();
      Optional<TransactionReceipt> receipt = resp.getTransactionReceipt();
      if (receipt.isPresent()) {
        return receipt.get();
      }
      Thread.sleep(sleepMillis);
    }

    throw new IOException("timed out waiting for receipt (hash=" + txHash + ", waited=" +
        Duration.ofMillis(sleepMillis * (long) attempts) + ")");
  }
}

