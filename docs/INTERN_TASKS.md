# Intern Tasks — Charger TCP Gateway (Java)

A learning ladder for working on `charger-tcp-gateway` and reading
`charger-iot-sdk`. Ordered from easy to hard; each task ends with something you
can demo.

**Ground rules**

- Work on a **branch**, submit PRs for review — no direct commits.
- **The simulator is your playground. The SDK is read-only.**
  `charger-iot-sdk` (`BaseProtocol`, `Crc16Util`, all encode/decode forms) is
  vendor-validated protocol truth — changes there risk silent wire breakage.
- Onboarding reading: this project's `README.md`, then
  `../../TEASK.v2/TCP_LOCAL_PLAN.md` (the overall plan), then
  `docs/CUTOVER.md` (how this meets real hardware).

**Setup** (once): `source env.sh`, then
`( cd "../../charger-iot-sdk" && mvn -q install -DskipTests )`,
then `mvn -q -DskipTests package` here.


### Task 1 — Run the loop and narrate it
Run in three terminals:

```bash
./run-gateway.sh
./run-simulator.sh 10000064
curl -X POST http://localhost:8080/devices/10000064/start \
  -H 'Content-Type: application/json' -d '{"slotNo":3,"money":500}'
```

**Done when:** you can explain, from the two logs, every message in the sequence
`login (0x01) → ack (0x02) → start (0x08) → charging (0x0D) → ack (0x0E) →
order complete (0x11) → ack (0x12)` — who sends it, why, and what's in it.

### Task 2 — Decode a frame by hand (the key exercise)
Take `SND_0x08_START` from `docs/golden-frames.txt`. On paper, split it into
`SOP | LEN | SERIAL | CTR | CMD | DATA | CRC` using
`charger-iot-sdk/.../constants/PROTOCOLS.java`, then check your split against
`BaseProtocol.enCode()`. Decode the DATA section into flowNo / slotNo /
chargeType / money using `Snd0x08EncodeForm.getData()`.

**Done when:** you can write out the byte layout of that frame from memory and
explain what the CRC covers (everything after SOP, excluding the CRC itself).
Everything else in this project is built on this frame format.


### Task 3 — Unit tests against the golden frames  ⭐ best first PR
The project has **no tests**. Add JUnit (add the dependency to `pom.xml`) and
write tests that:

1. `BaseProtocol.enCode(...)` reproduces each frame in `docs/golden-frames.txt`
   byte-for-byte (build the same forms with the same field values).
2. Round-trip: `decode(hexStr2bytes(enCode(form)))` returns the original
   deviceId / serialNo / cmd / data.
3. `Crc16Util.checkCrc` returns **false** when any single byte of a valid frame
   is corrupted.
4. `BaseProtocol.isPingMsg` accepts `FE0000` and rejects everything else.

**Done when:** `mvn test` runs green and a corrupted-frame test proves the CRC
actually protects the payload. Real value, zero production risk — these frames
were captured precisely to be test vectors.

### Task 4 — Fix a real bug: simulator single-session state
`src/main/java/cn/nblinks/teask/simulator/ChargerSimulator.java` tracks only one
session (`lastFlowNo` / `lastBudget`). If slot 3 is charging and a STOP arrives
for slot 4, the wrong session is ended.

Replace it with a proper per-slot state machine — `Map<Integer, Session>` where
`Session` holds flowNo, budget, start time, and status. STOP for a slot with no
active session should be ignored (log it).

**Done when:** you can start charges on slot 3 and slot 5 concurrently (two curl
starts), stop only slot 5, and slot 3 keeps charging to completion. Mind the
threading: the read loop and the `worker` executor both touch the state.

### Task 5 — Simulator: emit 0x0F realtime reports
Real firmware reports live socket data (`0x0F`) periodically while charging; the
simulator never does, so the app's live screen has nothing to show mid-charge.

Study how `Rec0x0FDecodeForm` **parses** the nested structure (device count →
per-device status/signal/voltage → slot count → per-slot power/time/elec/fee),
then write the encoder in reverse in the simulator: while any slot is charging,
send a `0x0F` frame every 10 seconds with plausible ramping values.

**Done when:** the gateway logs show `realtime` events being forwarded every
~10s during a simulated charge, and the gateway's `0x10` ack comes back. Verify
your encoding by feeding your own frame through `Rec0x0FDecodeForm` in a unit
test first (composes with Task 3).


## BONUS (Optional)

### Task 6 — Gateway: parse 0x0F structurally
`DeviceHandler` currently forwards `0x0F` as raw hex (`event: "realtime"`,
`data: <hex>`). Use `Rec0x0FDecodeForm` to decode it and forward structured
JSON: per-slot `{slotNo, power, usedTime, usedElec, usedFee}`.

**Done when:** the Node backend receives structured realtime JSON (pairs with
Task 5 — together they light up the live charging screen in the app).

### Task 7 — Command resend until confirmation
Cellular links drop frames. `SendMessageForm` already carries `reSendCount` /
`reSendDelay` fields (the original system used Redis for this). Implement
in-memory retry in the gateway: when `/start` pushes `0x08`, schedule a resend
(same flowNo, same serial) every 3s up to 3 tries, cancelled when the device's
`0x0D` for that flowNo arrives.

**Done when:** a test proves it — e.g. add a simulator flag to ignore the first
`0x08`, and show the charge still starts on the retry. Teaches timeouts,
scheduling, and idempotency — core reliability thinking for IoT.

### Task 8 — Load test: 100 simulated devices
Write a runner that spawns 50–100 `ChargerSimulator` instances (distinct
deviceIds) against one gateway, starts a charge on each, and reports: connect
success rate, time-to-login, and completed sessions.

**Done when:** you can state how many concurrent devices one gateway instance
handles on your machine and where it degrades first (threads? file descriptors?
backend forwarding pool?).

### Task 9 — Control-plane auth (real security roadmap item)
The `:8080` control plane is unauthenticated — anyone who can reach it can start
or stop chargers. Add a shared-secret check: an `X-Gateway-Token` header
validated against a `GATEWAY_TOKEN` env var (skip the check when unset, for
local dev). Update the Node backend's `src/lib/gateway.mjs` to send it.

**Done when:** requests without the token get `401`, the E2E flow still passes
with both sides configured, and the README documents the new env var.

---

## Suggested review checkpoints

| After | Demo |
|---|---|
| Task 2 | Whiteboard walk-through of one golden frame |
| Task 3 | `mvn test` green, corrupted-CRC test shown |
| Task 4 | Two concurrent slots, stop one, other completes |
| Task 5+6 | Live realtime JSON arriving at the backend every 10s |
| Task 7 | Dropped-first-0x08 test passing |
| Task 8 | One-page findings: max devices + first bottleneck |
| Task 9 | 401 without token, green E2E with it |
