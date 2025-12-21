package com.polybot.hft.executor.settlement;

import lombok.NonNull;
import org.web3j.utils.Numeric;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal ABI encoder for Polymarket's ProxyWalletFactory:
 * <pre>
 * function proxy((uint8,address,uint256,bytes)[] calls) external payable returns (bytes[] memory)
 * </pre>
 *
 * Web3j 5.0.0 doesn't ship struct/tuple encoders, so we encode this one manually.
 */
final class ProxyWalletFactoryCallEncoder {

  private static final byte[] SELECTOR_PROXY = Numeric.hexStringToByteArray("0x34ee9791");

  private ProxyWalletFactoryCallEncoder() {
  }

  record ProxyCall(int typeCode, String to, BigInteger value, byte[] data) {
    static ProxyCall call(@NonNull String to, @NonNull String calldataHex) {
      return new ProxyCall(1, to, BigInteger.ZERO, Numeric.hexStringToByteArray(calldataHex));
    }
  }

  static String encodeProxy(List<ProxyCall> calls) {
    List<ProxyCall> safeCalls = calls == null ? List.of() : calls;

    // calldata = selector + abi.encode(calls)
    // calls is the only arg => head is 1 word offset to tail (0x20)
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(SELECTOR_PROXY);

    // Head: offset (32)
    out.writeBytes(encodeUint256(BigInteger.valueOf(32)));

    // Tail: dynamic array of dynamic tuples
    out.writeBytes(encodeProxyCallArray(safeCalls));

    return Numeric.toHexString(out.toByteArray());
  }

  private static byte[] encodeProxyCallArray(List<ProxyCall> calls) {
    int n = calls.size();
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    out.writeBytes(encodeUint256(BigInteger.valueOf(n))); // length

    // Offsets table (n words), relative to start of this array encoding (the length word)
    List<byte[]> encodedElements = new ArrayList<>(n);
    for (ProxyCall c : calls) {
      encodedElements.add(encodeProxyCallTuple(c));
    }

    int headSize = 32 * (1 + n); // length + offsets
    int running = 0;
    for (byte[] element : encodedElements) {
      out.writeBytes(encodeUint256(BigInteger.valueOf(headSize + running)));
      running += element.length;
    }

    // Elements payload
    for (byte[] element : encodedElements) {
      out.writeBytes(element);
    }

    return out.toByteArray();
  }

  private static byte[] encodeProxyCallTuple(ProxyCall call) {
    // tuple(uint8 typeCode, address to, uint256 value, bytes data)
    // head = 4 words; data offset = 0x80 (128)
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(encodeUint256(BigInteger.valueOf(call.typeCode)));
    out.writeBytes(encodeAddress(call.to));
    out.writeBytes(encodeUint256(call.value == null ? BigInteger.ZERO : call.value));
    out.writeBytes(encodeUint256(BigInteger.valueOf(32L * 4))); // offset to bytes data
    out.writeBytes(encodeBytes(call.data == null ? new byte[0] : call.data));
    return out.toByteArray();
  }

  private static byte[] encodeUint256(BigInteger v) {
    byte[] raw = Numeric.toBytesPadded(v, 32);
    return raw;
  }

  private static byte[] encodeAddress(String addressHex) {
    String addr = addressHex == null ? "" : addressHex.trim();
    if (addr.startsWith("0x") || addr.startsWith("0X")) {
      addr = addr.substring(2);
    }
    if (addr.length() != 40) {
      throw new IllegalArgumentException("Expected 20-byte address hex, got: " + addressHex);
    }
    byte[] addrBytes = Numeric.hexStringToByteArray("0x" + addr);
    byte[] padded = new byte[32];
    System.arraycopy(addrBytes, 0, padded, 12, 20);
    return padded;
  }

  private static byte[] encodeBytes(byte[] data) {
    int len = data.length;
    int paddedLen = ((len + 31) / 32) * 32;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(encodeUint256(BigInteger.valueOf(len)));
    if (len > 0) {
      out.writeBytes(data);
    }
    if (paddedLen > len) {
      out.writeBytes(new byte[paddedLen - len]);
    }
    return out.toByteArray();
  }
}

