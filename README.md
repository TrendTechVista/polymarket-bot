# Polybot (Java / Spring Boot)

Two-process layout (low-latency):

- `executor-service`: owns keys, derives creds, signs and places orders (REST API for Postman/curl)
- `strategy-service`: connects to public market WS, runs strategies, sends orders to `executor-service`

Core Polymarket integration lives in `polybot-core`.

- CLOB REST integration (public + authenticated)
- L1 EIP-712 auth (derive/create API creds)
- L2 HMAC auth (signed REST requests)
- EIP-712 order signing (CTF Exchange)
- Market data via REST orderbook + optional market WebSocket cache
- Optional sample strategy engine (disabled by default)

## Requirements

- Java 21+
- Maven

## Run

```bash
mvn test

# Terminal 1 (API executor)
mvn -pl executor-service spring-boot:run -Dspring-boot.run.profiles=develop

# Terminal 2 (strategy runner)
mvn -pl strategy-service spring-boot:run -Dspring-boot.run.profiles=develop
```

## Configuration

Configs:

- `executor-service/src/main/resources/application.yaml`
- `strategy-service/src/main/resources/application.yaml`

Recommended env vars (for `executor-service`):

```bash
export POLYMARKET_PRIVATE_KEY="0x..."
export POLYMARKET_FUNDER_ADDRESS="0x..."        # optional (proxy wallet address)
export POLYMARKET_API_KEY="..."                 # L2 creds (optional if auto-derive enabled)
export POLYMARKET_API_SECRET="..."
export POLYMARKET_API_PASSPHRASE="..."
```

Key settings (all under `hft.*`):

- `hft.mode`: `PAPER` (default) or `LIVE`
- `hft.polymarket.auth.signature-type`: `0` (EOA), `1` (POLY_PROXY), `2` (GNOSIS_SAFE)
- `hft.polymarket.auth.auto-create-or-derive-api-creds`: when `true` and `hft.mode=LIVE`, derive/create L2 creds from your private key
- `hft.risk.kill-switch`: blocks new order placement when `true`
- `hft.polymarket.market-ws-enabled`: enable CLOB market WS cache
- `hft.polymarket.market-asset-ids`: list of token IDs to subscribe to (market channel)
- `hft.executor.base-url`: where `strategy-service` sends orders (default `http://localhost:8080`)

## API

- `GET /api/polymarket/auth/status`: shows whether signer + API creds are configured (no secrets)
- `POST /api/polymarket/auth/derive?nonce=N`: derive/create API creds from your private key (requires `X-HFT-LIVE-ACK: true` in `LIVE`)

- `GET /api/polymarket/orderbook/{tokenId}`: REST orderbook snapshot
- `GET /api/polymarket/tick-size/{tokenId}`: current minimum tick size
- `GET /api/polymarket/neg-risk/{tokenId}`: whether market is neg-risk
- `GET /api/polymarket/marketdata/top/{tokenId}`: best bid/ask from WS cache (if enabled)

- `POST /api/polymarket/orders/limit`: place a limit order (signed)
- `POST /api/polymarket/orders/market`: place a marketable order (signed)
- `DELETE /api/polymarket/orders/{orderId}`: cancel an order

Gamma (market/search metadata):

- `GET /api/polymarket/gamma/search?query=...`
- `GET /api/polymarket/gamma/markets`
- `GET /api/polymarket/gamma/markets/{id}`
- `GET /api/polymarket/gamma/events`
- `GET /api/polymarket/gamma/events/{id}`

If Gamma returns `401 invalid token/cookies`, pass your `Authorization` and/or `Cookie` headers through to the service (the Postman env has `gammaAuthorization` and `gammaCookie` variables for this).

Example (paper mode):

```bash
curl -sS localhost:8080/api/polymarket/orders/limit \\
  -H 'content-type: application/json' \\
  -d '{
    "tokenId": "104173557214744537570424345347209544585775842950109756851652855913015295701992",
    "side": "BUY",
    "price": 0.01,
    "size": 5,
    "orderType": "GTC"
  }'
```

## WebSocket Notes

Polymarket’s CLOB market websocket is `wss://ws-subscriptions-clob.polymarket.com/ws/market` and expects a subscribe message like:

```json
{"assets_ids":["<tokenId>"],"type":"market"}
```

This project uses it to keep a lightweight top-of-book cache; order placement is still done via REST.

## Sample Strategy (Disabled by Default)

`strategy-service` contains sample engines under `com.polybot.hft.polymarket.strategy.*`.

`MidpointMakerEngine` is a simple “cancel/replace around midpoint” example.

Enable it only after you’ve set risk limits and are confident in the behavior:

- `hft.polymarket.market-ws-enabled=true`
- `hft.strategy.midpoint-maker.enabled=true`

`HouseEdgeEngine` is a “biased market maker” example:

- Computes a short moving average from the `last_trade_price` WS event
- Chooses a YES/NO bias and skews quotes to accumulate the “winner”

Config (requires market WS + both token IDs subscribed):

- `hft.polymarket.market-asset-ids` must include both `yesTokenId` and `noTokenId`
- `hft.strategy.house-edge.enabled=true`
- `hft.strategy.house-edge.markets[0].yes-token-id=...`
- `hft.strategy.house-edge.markets[0].no-token-id=...`

## Safety

Never commit private keys or API secrets. Prefer `hft.mode=PAPER` until you’ve validated end-to-end behavior.

## Postman

Import:

- `postman/Polybot.postman_collection.json`
- `postman/Polybot.local.postman_environment.json`
