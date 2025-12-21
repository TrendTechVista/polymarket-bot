package com.polybot.hft.executor.settlement;

import lombok.NonNull;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

final class ConditionalTokensCallEncoder {

  private ConditionalTokensCallEncoder() {
  }

  static String encodeMergePositions(
      @NonNull String collateralAddress,
      @NonNull String conditionIdHex,
      @NonNull List<BigInteger> partitionIndexSets,
      @NonNull BigInteger amount
  ) {
    Function function = new Function(
        "mergePositions",
        List.of(
            new Address(collateralAddress),
            new Bytes32(new byte[32]), // parentCollectionId = 0x00...00 for top-level conditions
            bytes32(conditionIdHex),
            new DynamicArray<>(Uint256.class, toUint256(partitionIndexSets)),
            new Uint256(amount)
        ),
        List.of()
    );
    return FunctionEncoder.encode(function);
  }

  static String encodeRedeemPositions(
      @NonNull String collateralAddress,
      @NonNull String conditionIdHex,
      @NonNull List<BigInteger> indexSets
  ) {
    Function function = new Function(
        "redeemPositions",
        List.of(
            new Address(collateralAddress),
            new Bytes32(new byte[32]), // parentCollectionId = 0x00...00 for top-level conditions
            bytes32(conditionIdHex),
            new DynamicArray<>(Uint256.class, toUint256(indexSets))
        ),
        List.of()
    );
    return FunctionEncoder.encode(function);
  }

  private static Bytes32 bytes32(String hex) {
    byte[] bytes = Numeric.hexStringToByteArray(hex);
    if (bytes.length != 32) {
      throw new IllegalArgumentException("Expected bytes32 hex (32 bytes), got len=" + bytes.length + " hex=" + hex);
    }
    return new Bytes32(bytes);
  }

  private static List<Uint256> toUint256(List<BigInteger> values) {
    List<Uint256> out = new ArrayList<>(values.size());
    for (BigInteger v : values) {
      out.add(new Uint256(v));
    }
    return out;
  }
}
