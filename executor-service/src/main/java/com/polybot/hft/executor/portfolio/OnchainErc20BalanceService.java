package com.polybot.hft.executor.portfolio;

import com.polybot.hft.executor.settlement.ExecutorOnchainProperties;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.http.HttpService;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class OnchainErc20BalanceService {

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

  public BigInteger balanceOfRaw(@NonNull String tokenAddress, @NonNull String ownerAddress) {
    try {
      Function fn = new Function(
          "balanceOf",
          List.of(new Address(ownerAddress)),
          List.of(new TypeReference<Uint256>() {
          })
      );
      String data = FunctionEncoder.encode(fn);

      Transaction tx = Transaction.createEthCallTransaction(ownerAddress, tokenAddress, data);
      String value = web3j().ethCall(tx, DefaultBlockParameterName.LATEST).send().getValue();

      List<Type> decoded = FunctionReturnDecoder.decode(value, fn.getOutputParameters());
      if (decoded == null || decoded.isEmpty()) {
        return BigInteger.ZERO;
      }
      Object raw = decoded.getFirst().getValue();
      return raw instanceof BigInteger bi ? bi : BigInteger.ZERO;
    } catch (Exception e) {
      log.debug("onchain balanceOf failed token={} owner={} err={}", tokenAddress, ownerAddress, e.toString());
      return BigInteger.ZERO;
    }
  }

  public BigDecimal balanceOf(@NonNull String tokenAddress, @NonNull String ownerAddress, int decimals) {
    BigInteger raw = balanceOfRaw(tokenAddress, ownerAddress);
    if (decimals <= 0) {
      return new BigDecimal(raw);
    }
    return new BigDecimal(raw).movePointLeft(decimals).setScale(decimals, RoundingMode.DOWN);
  }
}

