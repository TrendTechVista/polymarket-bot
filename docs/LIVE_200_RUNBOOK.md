# LIVE $200 Runbook

This runbook uses the new `live` Spring profile configs:
- `executor-service/src/main/resources/application-live.yaml`
- `strategy-service/src/main/resources/application-live.yaml`

## 0) Configure Secrets (required)

You have two options.

### Option A: No env vars (recommended if you prefer YAML)

1) Copy `config/application-live.yaml.example` → `config/application-live.yaml`
2) Fill in:
- `hft.polymarket.auth.private-key`
- (either) set `auto-create-or-derive-api-creds: true` (and optional `nonce`)
- (or) paste `api-key/api-secret/api-passphrase` and set `auto-create-or-derive-api-creds: false`

This file is gitignored via `.gitignore`.

### Option B: Env vars

Executor service reads Polymarket credentials from env (see `executor-service/src/main/resources/application.yaml`).

Minimum required:
```bash
export POLYMARKET_PRIVATE_KEY='0x...'
```

If you already have Polymarket CLOB API creds:
```bash
export POLYMARKET_API_KEY='...'
export POLYMARKET_API_SECRET='...'
export POLYMARKET_API_PASSPHRASE='...'
export POLYMARKET_AUTO_DERIVE=false
```

If you want the executor to auto create/derive API creds from the private key at startup:
```bash
export POLYMARKET_AUTO_DERIVE=true
export POLYMARKET_API_NONCE=0   # only set if you know you need a specific nonce
```

## 1) Safety Switches (recommended)

Hard disable trading (executor-side):
```bash
export HFT_KILL_SWITCH=true
```

Enable the strategy (default is disabled in the `live` profile; you can set this in `config/application-live.yaml` instead):
```bash
export GABAGOOL_ENABLED=true
```

Allow strategy to send the LIVE_ACK header (required for order placement; default is false in the `live` profile; you can set this in `config/application-live.yaml` instead):
```bash
export HFT_SEND_LIVE_ACK=true
```

## 2) Start Services (LIVE)

Start executor in LIVE mode (real orders):
```bash
mvn -pl executor-service spring-boot:run -Dspring-boot.run.profiles=live
```

Verify Polymarket connectivity:
```bash
curl -s 'http://localhost:8080/api/polymarket/health?deep=false'
curl -s 'http://localhost:8080/api/polymarket/account'
```

Start strategy in LIVE mode (will place orders via executor if enabled and LIVE_ACK is on):
```bash
export GABAGOOL_BANKROLL_USD=200
mvn -pl strategy-service spring-boot:run -Dspring-boot.run.profiles=live
```

## 3) Emergency Stop

Fastest stop is to stop the strategy process (it cancels its own open orders on shutdown):
```bash
pkill -f 'strategy-service'
```

If needed, cancel specific orders via executor (requires LIVE_ACK header in LIVE mode):
```bash
curl -H 'X-HFT-LIVE-ACK: true' -X DELETE 'http://localhost:8080/api/polymarket/orders/<orderId>'
```

## 4) Default $200 Limits (what LIVE profile enforces)

These are controlled by env vars in the `live` profile:
- `GABAGOOL_BANKROLL_USD=200`
- `GABAGOOL_MAX_ORDER_FRAC=0.05` (≈ $10 per order cap)
- `GABAGOOL_MAX_TOTAL_FRAC=0.50` (≈ $100 total exposure cap)
- `HFT_MAX_ORDER_NOTIONAL_USD=10` (executor hard cap, same as per-order)
