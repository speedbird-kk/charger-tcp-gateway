# charger-tcp-gateway

Slim, self-hosted TCP gateway for TEASK chargers — **Path B** of the local plan
(see `../../TEASK.v2/TCP_LOCAL_PLAN.md`). Chargers connect **directly** to this
server over TCP; there is no vendor cloud in the path.

It reuses `charger-iot-sdk` **as-is** for the wire protocol (framing + CRC16 +
all encode/decode forms), and strips everything the original `charger-iot-api`
needed for a clustered deployment (Eureka, Redis, Alibaba RocketMQ, the missing
`protocolApi` Feign client). What's left is: **Netty + the SDK codec + two HTTP
hops** to the TEASK Node backend.

## What it does

```
 charger ──TCP:6666──►  gateway  ──HTTP──►  TEASK backend      (device reports)
 charger ◄─TCP:6666──   gateway  ◄─HTTP──   TEASK backend      (start / stop)
```

- **TCP :6666** — chargers connect, log in (`0x01`), heartbeat, and stream reports.
  The gateway auto-acks the reports a real device expects (`0x0D→0x0E`,
  `0x0F→0x10`, `0x11→0x12`) and forwards every decoded message to the backend.
- **HTTP :8080** — the control plane the Node backend calls to drive a device:
  - `GET  /health`
  - `GET  /devices`
  - `POST /devices/{deviceId}/start`  body `{ "slotNo":3, "money":500, "flowNo?":"…", "chargeType?":0 }`
  - `POST /devices/{deviceId}/stop`   body `{ "slotNo":3 }`
- **Reports out** — POSTed to `TEASK_BACKEND_URL + /webhooks/charger/report` as
  `{ event, deviceId, cmd, serialNo, data, … }` where `event` ∈
  `login | charging_started | realtime | order_complete | time | raw`.
  `order_complete` carries `money` (consumed cents) — what the backend deducts.

## Prerequisites

JDK 8 + Maven were installed under `~/tools` during setup (Corretto 8 for Apple
Silicon, since Temurin/Homebrew have no JDK 8 arm64 bottle). `env.sh` points at them.

`charger-iot-sdk` must be in the local Maven repo:

```bash
source env.sh
( cd "../../charger-iot-sdk" && mvn -q install -DskipTests )
```

## Build & run

```bash
source env.sh
mvn -q -DskipTests package         # → target/charger-tcp-gateway-1.0.0.jar (uber jar)

./run-gateway.sh                   # terminal 1
./run-simulator.sh 10000064        # terminal 2 (a fake charger)

# terminal 3 — start a charge on slot 3 for RM5.00
curl -X POST http://localhost:8080/devices/10000064/start \
  -H 'Content-Type: application/json' -d '{"slotNo":3,"money":500}'
```

The simulator will confirm start (`0x0D`), charge for ~3s, then send order-complete
(`0x11`). The gateway acks each and forwards to the backend (a "connection refused"
log is expected until the Node backend exists — Phase 3).

## Configuration (env vars)

| Var | Default | Meaning |
|---|---|---|
| `GATEWAY_TCP_PORT` | `6666` | charger TCP port |
| `GATEWAY_HTTP_PORT` | `8080` | control-plane HTTP port |
| `TEASK_BACKEND_URL` | `http://localhost:3001` | Node backend base URL |
| `TEASK_BACKEND_REPORT_PATH` | `/webhooks/charger/report` | report webhook path |

## Layout

```
src/main/java/cn/nblinks/teask/
  gateway/
    GatewayApp.java          entry point
    GatewayConfig.java       env-driven config
    GatewayServer.java       Netty bootstrap (same frame decoder as the original)
    DeviceHandler.java       per-connection dispatch + auto-ack + forward
    DeviceRegistry.java      in-memory deviceId → channel (replaces Redis)
    ControlHttpServer.java   HTTP control plane (replaces RocketMQ inbound)
    BackendClient.java       forwards reports to the Node backend (replaces Feign)
    Sender.java              encode-form → framed bytes → channel
  simulator/
    ChargerSimulator.java    fake charger for hardware-free testing
docs/golden-frames.txt       known-good hex frames (test vectors for any future port)
```

## Status

Phases 1 (slim gateway) and 2 (simulator) of the plan are **done and green** —
a full login → start → charge → order-complete loop runs locally with no hardware.
Next: Phase 3 wires the TEASK Node backend to these HTTP hops.
