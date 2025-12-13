package com.polybot.hft.polymarket.auth;

import com.polybot.hft.config.HftProperties;
import com.polybot.hft.polymarket.clob.PolymarketClobClient;
import com.polybot.hft.polymarket.model.ApiCreds;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Credentials;

import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.annotation.PostConstruct;

@Component
@Slf4j
@RequiredArgsConstructor
public class PolymarketAuthContext {

  private static final Pattern HEX_32_BYTES = Pattern.compile("0x[0-9a-fA-F]{64}");
  private static final Pattern HEX_20_BYTES = Pattern.compile("0x[0-9a-fA-F]{40}");

  private final @NonNull HftProperties properties;
  private final @NonNull PolymarketClobClient clobClient;
  private final @NonNull Environment environment;

  private volatile Credentials signerCredentials;
  private volatile ApiCreds apiCreds;
  private volatile boolean autoDeriveEnabled;
  private volatile long configuredNonce;

  public record DeriveAttempt(
      boolean attempted,
      boolean success,
      String method,
      long nonce,
      String error
  ) {
  }

  @PostConstruct
  void initFromConfig() {
    HftProperties.Auth auth = properties.polymarket().auth();

    String resolvedPrivateKey = environment.getProperty("hft.polymarket.auth.private-key");
    Boolean resolvedAutoDerive = environment.getProperty("hft.polymarket.auth.auto-create-or-derive-api-creds", Boolean.class);
    Long resolvedNonce = environment.getProperty("hft.polymarket.auth.nonce", Long.class);

    String privateKey = (resolvedPrivateKey != null && !resolvedPrivateKey.isBlank())
        ? resolvedPrivateKey
        : auth.privateKey();
    this.autoDeriveEnabled = resolvedAutoDerive != null ? resolvedAutoDerive : auth.autoCreateOrDeriveApiCreds();
    this.configuredNonce = resolvedNonce != null ? resolvedNonce : auth.nonce();

    log.info("Auth config loaded (mode={}, autoDerive={}, signerKeyPresent={}, apiKeyPresent={})",
        properties.mode(),
        autoDeriveEnabled,
        privateKey != null && !privateKey.isBlank(),
        auth.apiKey() != null && !auth.apiKey().isBlank());
    if (privateKey != null && !privateKey.isBlank()) {
      requireHex32("hft.polymarket.auth.private-key", privateKey);
      this.signerCredentials = Credentials.create(strip0x(privateKey));
    }

    String apiKey = auth.apiKey();
    String apiSecret = auth.apiSecret();
    String apiPassphrase = auth.apiPassphrase();
    if (apiKey != null && !apiKey.isBlank()
        && apiSecret != null && !apiSecret.isBlank()
        && apiPassphrase != null && !apiPassphrase.isBlank()) {
      this.apiCreds = new ApiCreds(apiKey, apiSecret, apiPassphrase);
    }

    if (properties.mode() == HftProperties.TradingMode.LIVE
        && autoDeriveEnabled
        && this.apiCreds == null) {
      Credentials signer = requireSignerCredentials();
      DeriveAttempt attempt = tryCreateOrDeriveApiCreds(configuredNonce);
      if (attempt.success()) {
        log.info("Loaded Polymarket API key creds (method={}, keySuffix=...{})",
            attempt.method(),
            safeSuffix(apiCreds == null ? null : apiCreds.key(), 6));
      } else if (attempt.error() != null) {
        log.warn("Failed to auto create/derive Polymarket API creds: {}", attempt.error());
      }
    }

    String funder = auth.funderAddress();
    if (funder != null && !funder.isBlank()) {
      requireHex20("hft.polymarket.auth.funder-address", funder);
    }
  }

  public Optional<Credentials> signerCredentials() {
    return Optional.ofNullable(signerCredentials);
  }

  public Credentials requireSignerCredentials() {
    Credentials creds = signerCredentials;
    if (creds == null) {
      throw new IllegalStateException("Polymarket signer private key is not configured (hft.polymarket.auth.private-key)");
    }
    return creds;
  }

  public Optional<ApiCreds> apiCreds() {
    return Optional.ofNullable(apiCreds);
  }

  public ApiCreds requireApiCreds() {
    ApiCreds creds = apiCreds;
    if (creds == null) {
      throw new IllegalStateException("Polymarket API creds not configured (api-key/secret/passphrase)");
    }
    return creds;
  }

  public boolean autoDeriveEnabled() {
    return autoDeriveEnabled;
  }

  public long configuredNonce() {
    return configuredNonce;
  }

  public synchronized DeriveAttempt tryCreateOrDeriveApiCreds(long nonce) {
    if (this.apiCreds != null) {
      return new DeriveAttempt(false, true, "already-present", nonce, null);
    }
    Credentials signer;
    try {
      signer = requireSignerCredentials();
    } catch (Exception e) {
      return new DeriveAttempt(true, false, null, nonce, e.toString());
    }

    try {
      ApiCreds created = clobClient.createApiCreds(signer, nonce);
      if (created != null && created.key() != null && !created.key().isBlank()) {
        this.apiCreds = created;
        return new DeriveAttempt(true, true, "create", nonce, null);
      }
    } catch (Exception ignored) {
      // fall through to derive
    }

    try {
      ApiCreds derived = clobClient.deriveApiCreds(signer, nonce);
      if (derived.key() != null && !derived.key().isBlank()) {
        this.apiCreds = derived;
        return new DeriveAttempt(true, true, "derive", nonce, null);
      }
      return new DeriveAttempt(true, false, "derive", nonce, "derive returned empty creds");
    } catch (Exception e) {
      return new DeriveAttempt(true, false, "derive", nonce, e.toString());
    }
  }

  private static String strip0x(String hex) {
    String trimmed = hex.trim();
    return trimmed.startsWith("0x") || trimmed.startsWith("0X") ? trimmed.substring(2) : trimmed;
  }

  private static String safeSuffix(String value, int len) {
    if (value == null || value.isBlank() || len <= 0) {
      return "";
    }
    return value.length() <= len ? value : value.substring(value.length() - len);
  }

  private static void requireHex32(String field, String value) {
    String trimmed = value == null ? "" : value.trim();
    if (!HEX_32_BYTES.matcher(trimmed).matches()) {
      throw new IllegalArgumentException(field + " must be 0x + 64 hex chars");
    }
  }

  private static void requireHex20(String field, String value) {
    String trimmed = value == null ? "" : value.trim();
    if (!HEX_20_BYTES.matcher(trimmed).matches()) {
      throw new IllegalArgumentException(field + " must be 0x + 40 hex chars");
    }
  }
}
