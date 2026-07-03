# Deploying the TEASK Charger TCP Gateway on Google Cloud

This guide sets up a public server on Google Cloud Compute Engine to host the
`charger-tcp-gateway`, so that **physical chargers (on 4G/cellular) can connect
to it** over TCP.

Real chargers in the field can't reach a laptop on a home/office LAN — they need
a server with a **public, static IP**. On Google Cloud that means a **Compute
Engine VM** with a reserved external IP and a firewall rule opening TCP **6666**.

> One nice property: the gateway ships as a single self-contained **uber jar**
> (~6.8 MB, bundling Netty + the protocol SDK + fastjson). You upload one file and
> run it — no Maven needed on the server.

---

## Architecture

```
charger (4G) ──▶ [ STATIC_IP:6666 ]  GCP VM (Compute Engine)
                                       running charger-tcp-gateway-1.0.0.jar
                                       │ HTTP → TEASK Node backend (reports)
                                       └ :8080 control plane (localhost only)
```

---

## Step 1 — Project + enable Compute Engine

1. In the [Cloud Console](https://console.cloud.google.com), pick or create a
   project (top bar → project dropdown → **New Project**).
2. Go to **Compute Engine → VM instances**. The first visit prompts you to
   **Enable the Compute Engine API** — click it (takes ~1 minute).

## Step 2 — Reserve a static external IP

The device config must point at a fixed IP, so don't rely on the default
ephemeral one.

- **VPC network → IP addresses → Reserve external static address**.
- Name: `teask-gateway-ip`, Type: **Regional**, Region: pick one near your
  chargers (e.g. `asia-southeast1` for Malaysia/Singapore). Leave "attached to"
  empty for now.

## Step 3 — Create the VM

**Compute Engine → VM instances → Create instance**:

- **Name**: `teask-gateway`
- **Region/Zone**: same region as the IP (e.g. `asia-southeast1-b`)
- **Machine type**: `e2-small` (2 GB) is plenty; `e2-micro` qualifies for the
  free tier if you're in an eligible US region.
- **Boot disk**: Ubuntu 22.04 LTS (or Debian 12)
- **Networking** (expand): under **Network interfaces**, set **External IPv4
  address** → your reserved `teask-gateway-ip`. Add a **Network tag**:
  `teask-gateway` (the firewall rule targets this tag).
- Leave "Allow HTTP/HTTPS" **off** (this server isn't serving web pages).
- **Create**.

## Step 4 — Firewall rule for TCP 6666

**VPC network → Firewall → Create firewall rule**:

- Name: `allow-charger-tcp`
- Direction: **Ingress**, Action: **Allow**
- **Targets**: Specified target tags → `teask-gateway`
- **Source IPv4 ranges**: `0.0.0.0/0` (chargers arrive from various carrier IPs)
- **Protocols/ports**: **TCP → 6666**
- **Create**.

> Keep the control-plane port **8080 closed to the internet** — only your backend
> calls it, and if the backend runs on the same VM it reaches `localhost:8080`.
> SSH (22) is open by default.

Equivalent in one command (from **Cloud Shell** — the `>_` icon, top-right):

```bash
gcloud compute firewall-rules create allow-charger-tcp \
  --direction=INGRESS --action=ALLOW --rules=tcp:6666 \
  --source-ranges=0.0.0.0/0 --target-tags=teask-gateway
```

## Step 5 — Install Java 8 + run the gateway

SSH into the VM (the **SSH** button next to the instance). It's x86-64 Linux, so
install Amazon Corretto 8 from its apt repo:

```bash
wget -O - https://apt.corretto.aws/corretto.key | sudo gpg --dearmor -o /usr/share/keyrings/corretto.gpg
echo "deb [signed-by=/usr/share/keyrings/corretto.gpg] https://apt.corretto.aws stable main" | sudo tee /etc/apt/sources.list.d/corretto.list
sudo apt-get update && sudo apt-get install -y java-1.8.0-amazon-corretto-jdk
java -version   # should show 1.8.0
```

Upload the jar from your Mac (run **locally**, replace the zone if different):

```bash
gcloud compute scp \
  "TEASK.v3 with backend 1Jul/charger-tcp-gateway/target/charger-tcp-gateway-1.0.0.jar" \
  teask-gateway:~/gateway.jar --zone=asia-southeast1-b
```

Run it as a **systemd service** so it survives reboots and crashes. On the VM,
create `/etc/systemd/system/teask-gateway.service` (replace `YOUR_USER` with your
Linux username, e.g. the one shown in the shell prompt):

```ini
[Unit]
Description=TEASK charger TCP gateway
After=network.target

[Service]
Environment=TEASK_BACKEND_URL=http://localhost:3001
ExecStart=/usr/bin/java -cp /home/YOUR_USER/gateway.jar cn.nblinks.teask.gateway.GatewayApp
Restart=always
User=YOUR_USER

[Install]
WantedBy=multi-user.target
```

Then enable and start it:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now teask-gateway
sudo systemctl status teask-gateway          # expect "listening on port 6666"
journalctl -u teask-gateway -f               # live logs
```

## Step 6 — Point the battery at it

This is the **vendor/firmware step, outside Google Cloud**: set the charger's
server address to `STATIC_IP` and port `6666` (via the vendor's config tool / an
SMS command / the device config protocol). Once it connects you'll see
`device online: <id>` in the gateway logs.

---

## Decisions & caveats

- **Where the backend runs.** The gateway forwards device reports to
  `TEASK_BACKEND_URL`. Either run the Node backend on the **same VM**
  (`localhost:3001`) or host it elsewhere and point the env var at its URL.
- **Security.** Port 6666 is open to the world; the protocol only authenticates
  via device login. If your SIM carrier provides a known IP range, narrow
  `--source-ranges` to it. Consider `fail2ban`, and eventually TLS or a VPN if the
  vendor firmware supports it.
- **Cost.** `e2-small` ≈ USD $13/month plus a few dollars for the static IP.
  `e2-micro` in an eligible US region can fall under the free tier.
- **Reachability caveat.** Some IoT SIMs use a private APN that can't reach the
  public internet, or whitelist only the vendor's servers. If a device won't
  connect after repointing, that's the likely cause — check with whoever supplies
  the SIM cards.

---

## Quick verification checklist

- [ ] VM running with the reserved static IP attached
- [ ] Firewall rule `allow-charger-tcp` allows TCP 6666 to tag `teask-gateway`
- [ ] `java -version` shows 1.8.0 on the VM
- [ ] `systemctl status teask-gateway` shows active + "listening on port 6666"
- [ ] From your Mac: `nc -vz STATIC_IP 6666` connects
- [ ] Device repointed to `STATIC_IP:6666` → `device online` appears in the logs
