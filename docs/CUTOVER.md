# Cutover: Simulator → Real Battery Charger

How to move from the local charger simulator to a **real charger pointing at the
TCP gateway**, what will happen during the process, and the gotchas to expect.

Companion docs:
- [`DEPLOY_GCP.md`](./DEPLOY_GCP.md) — putting the gateway on a public server
- [`golden-frames.txt`](./golden-frames.txt) — known-good protocol frames
- `../../TEASK.v2/TCP_LOCAL_PLAN.md` — overall plan & status

---

## 0. Code-check verdict (pre-cutover review)

The simulator → TCP server pointing is **correct**: the simulator connects to
`localhost:6666` by default and takes `[deviceId] [host] [port]` args, the
gateway binds `0.0.0.0:6666`, and both sides use the same `charger-iot-sdk`
codec. The full E2E (member balance 4000 → charge 500 → 3500, all webhooks 200)
is green.

Three gaps that the simulator masked (real firmware would have hit them) were
found and **fixed** in the gateway during this review:

1. **Missing acks** — real chargers send `0x03` module-info right after login,
   plus `0x1B` events, `0x0B` local-starts, and `0x29` self-check results; each
   expects an ack. Unacked, firmware retries and can stall. The gateway now acks
   all of them (`0x04`/`0x1C`/`0x0C`/`0x2A`) and forwards them to the backend as
   `module_info` / `event` / `offline_start` / `self_check_result`.
2. **Dead 4G connections lingered** — nothing closed idle channels, so a
   silently-dropped cellular link stayed "online" and a start command would be
   written into a void. The gateway now closes any channel silent for ~85s; the
   device reconnects and re-logs-in.
3. **Simulator didn't heartbeat** — real firmware pings `FE0000` every 30s; the
   simulator now does too (otherwise fix #2 would drop it).

---

## 1. Cutover steps

### Step 1 — Deploy the gateway publicly first
Follow [`DEPLOY_GCP.md`](./DEPLOY_GCP.md): VM + reserved static IP + firewall
opening TCP **6666**. A 4G device cannot reach a laptop on a LAN.

### Step 2 — Prove the cloud path with the simulator (key de-risking trick)
Before touching hardware:

```bash
./run-simulator.sh 10000064 STATIC_IP 6666
```

If the simulator logs in **over the internet** and completes a charge, the
server, firewall, and framing are all proven — any later failure is device-side,
not yours.

### Step 3 — Point the backend at the cloud gateway
- Backend env: `GATEWAY_URL=http://STATIC_IP:8080` (or run the backend on the
  same VM and keep `http://localhost:8080`).
- Gateway env on the VM: `TEASK_BACKEND_URL=<wherever the Node backend lives>`.

### Step 4 — Reconfigure the charger's server address
Set the device's server address to `STATIC_IP:6666`. **This is a vendor step** —
ask NBLinks (or your supplier):
- exactly *how* the server address is set (config tool / SMS or AT command to the
  SIM / a config command in the protocol), and
- the device's factory **pile number** (its 10-digit device ID).

### Step 5 — Watch the first login
```bash
journalctl -u teask-gateway -f
```
Success looks like:
```
device online: <pileNumber> (slots=N)
```
followed by heartbeats. This moment is the real proof of protocol fidelity.

### Step 6 — First real charge, small budget
- Build the QR URL for one socket:
  `/charging/start?deviceId=<pileNumber>&slotNo=1`
- Pay a small amount (RM1–5), verify the socket **physically energizes**, then
  verify `order_complete` arrives with sane cost/energy numbers.

### Step 7 — Retire the simulator
Remove it from the production path; keep it for regression testing against the
cloud gateway.

---

## 2. What will happen when a real device first connects

```
TCP connect
  → 0x01 login   (device id = its 10-digit pile number)
  ← 0x02 ack
  → 0x03 module info      ← now acked (0x04)
  → possibly 0x05 time sync  ← answered (0x06)
  → FE0000 heartbeats every ~30s
```
From there it's identical to the simulator flow: `0x08` start → `0x0D` started →
`0x0F` realtime (~every 20 min) → `0x11` order complete.

---

## 3. Gotchas — roughly in the order they'll bite

| # | Gotcha | What to do |
|---|---|---|
| 1 | **SIM/APN whitelist** (most common blocker). Many vendor SIMs use a private APN or only allow the vendor's servers. | If the device never appears in your logs after repointing, suspect the SIM before your code. Ask the SIM supplier. |
| 2 | **One server at a time.** Repointing takes the device **off the vendor's Pile Platform** — their app/portal stops seeing it. | Have the *un*-repointing (rollback) procedure in hand **before** you start. |
| 3 | **Device ID must match your QR stickers.** The ID is the physical pile number, not something you choose. | Generate socket QRs only after you've seen the real `device online: <id>` log. |
| 4 | **First login is the CRC moment of truth.** The codec came from the vendor's own SDK so it should match, but firmware protocol versions can differ. | If you see `decode failed` / CRC errors on first contact, check the `transVersion` the device reports vs SDK v2.0.0 with the vendor. |
| 5 | **Billing scheme is currently `null`.** The start command sends only the *budget*; the device bills by its locally configured tariff. | Sanity-check the reported cost on the first real order. If wrong, push tariff params (`0x16`) or attach a `ChargeScheme` to the start — formats are in the vendor PDFs (`api docs/`). |
| 6 | **Budget caps at RM655.35** — the money field is 2 bytes (cents). | Fine for e-bikes; validate amounts server-side. |
| 7 | **Real-time data is slow.** Real firmware reports `0x0F` about every 20 minutes, not continuously. | The live screen shows energy/cost mostly at start and completion — that's expected, not a bug. |
| 8 | **4G reconnect gaps.** After a gateway restart or link drop, a device may take a few minutes to reconnect. | Starts in that window return `409 device offline` — surface as "charger unavailable, try again shortly". |
| 9 | **Keep port 8080 private.** Anyone reaching the control plane can start/stop chargers. | Only 6666 internet-facing. Do the Phase-9 hardening (webhook auth, rate limits) before real money flows. |

---

## Quick checklist

- [ ] Gateway deployed on public VM, static IP, TCP 6666 open (`DEPLOY_GCP.md`)
- [ ] Simulator completes a full charge **against the cloud gateway**
- [ ] Backend `GATEWAY_URL` + gateway `TEASK_BACKEND_URL` point at each other
- [ ] Vendor procedure + pile number obtained; rollback procedure known
- [ ] Device repointed → `device online: <pileNumber>` in logs
- [ ] Small-budget real charge: socket energizes, `order_complete` numbers sane
- [ ] Billing verified against the device's tariff (gotcha #5)
- [ ] QR stickers generated with the real deviceId + slot numbers
- [ ] Simulator retired from production path
