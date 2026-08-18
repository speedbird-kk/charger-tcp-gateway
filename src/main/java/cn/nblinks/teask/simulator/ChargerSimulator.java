package cn.nblinks.teask.simulator;

import cn.nblinks.iot.BaseProtocol;
import cn.nblinks.iot.constants.CMD;
import cn.nblinks.iot.dataform.BaseMessageForm;
import cn.nblinks.iot.dataform.SendMessageForm;
import cn.nblinks.iot.utils.ByteUtil;
import cn.nblinks.teask.constants.Status;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A fake charger. Speaks the exact wire protocol a real device would, using the
 * SAME charger-iot-sdk codec the gateway uses — so a green run here proves the
 * gateway's encode/decode/CRC path end to end, and produces golden hex frames.
 *
 * Flow it plays:
 *   connect → 0x01 login → (wait) → on 0x08 start: 0x0D started → 0x11 order-end
 *   on 0x14 stop: 0x11 order-end immediately.
 *
 * Usage: ChargerSimulator [deviceId] [host] [tcpPort]
 */
public final class ChargerSimulator {

    private final int deviceId;
    private final String host;
    private final int port;
    private final ExecutorService worker = Executors.newCachedThreadPool();

    private OutputStream out;
    private volatile int serial = 0;

    public ChargerSimulator(int deviceId, String host, int port) {
        this.deviceId = deviceId;
        this.host = host;
        this.port = port;
    }

    public static void main(String[] args) throws Exception {
        int deviceId = args.length > 0 ? Integer.parseInt(args[0]) : 10000064;
        String host  = args.length > 1 ? args[1] : "localhost";
        int port     = args.length > 2 ? Integer.parseInt(args[2]) : 6666;
        new ChargerSimulator(deviceId, host, port).run();
    }

    public void run() throws Exception {
        ScheduledExecutorService pinger = Executors.newSingleThreadScheduledExecutor();
        try (Socket socket = new Socket(host, port)) {
            this.out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            System.out.println("[sim] connected to " + host + ":" + port + " as device " + deviceId);

            sendFrame(CMD.REC_0x01_LOGIN, nextSerial(), buildLoginData());
            System.out.println("[sim] sent login");

            // Real firmware heartbeats every 30s; the gateway closes idle channels.
            pinger.scheduleAtFixedRate(this::sendPing, 30, 30, TimeUnit.SECONDS);

            readLoop(in);
        } finally {
            pinger.shutdownNow();
            worker.shutdownNow();
        }
    }

    private void sendPing() {
        try {
            synchronized (this) {
                out.write(ByteUtil.hexStr2bytes(cn.nblinks.iot.constants.PROTOCOLS.PING));
                out.flush();
            }
        } catch (Exception e) {
            System.out.println("[sim] ping failed: " + e.getMessage());
        }
    }

    // ── inbound (server → device) ─────────────────────────────────────────────

    private void readLoop(InputStream in) throws Exception {
        while (true) {
            byte[] frame = readFrame(in);
            if (frame == null) {
                System.out.println("[sim] connection closed by gateway");
                return;
            }
            if (BaseProtocol.isPingMsg(frame)) continue; // gateway heartbeat echo

            BaseMessageForm form;
            try {
                form = BaseProtocol.decode(deviceId, frame);
            } catch (Exception e) {
                System.out.println("[sim] decode failed: " + e.getMessage());
                continue;
            }
            handle(form);
        }
    }

    private void handle(BaseMessageForm form) {
        switch (form.getCmd()) {
            case CMD.SND_0x02_LOGIN_RESP:
                System.out.println("[sim] login accepted — waiting for a start command...");
                break;

            case CMD.SND_0x08_ONLINE_START_CHARGE: {
                String flowNo = form.getData().substring(0, 18);
                int slotNo    = ByteUtil.hexStr2Int(form.getData().substring(18, 20));
                int money     = ByteUtil.hexStr2Int(form.getData().substring(22, 26));
                System.out.printf("[sim] START received: slot=%d budget=%d flowNo=%s%n", slotNo, money, flowNo);

                Session session = new Session(flowNo, money);
                
                Future<?> workerFuture = worker.submit(() -> runChargeSession(slotNo, session));

                session.setStatus(Status.CHARGING);
                session.setWorker(workerFuture);

                break;
            }

            case CMD.SND_0x14_STOP_CHARGE: {
                int slotNo = ByteUtil.hexStr2Int(form.getData().substring(0, 2));

                Session sessionToStop = sessions.get(slotNo);

                if (sessionToStop == null
                    || !sessionToStop.getStatus().equals(Status.CHARGING)) {

                    System.out.println(
                        "[sim] STOP ignored - no active charging session for slot " + slotNo);
                } else {
                    sessionToStop.setStatus(Status.STOPPED);

                    System.out.println("[sim] STOP received for slot " + slotNo);

                    // A real device would end the active order; emit a completion.
                    worker.submit(() -> sendOrderEnd(
                        sessionToStop.getFlowNo(),
                        slotNo,
                        sessionToStop.getBudget(),
                        sessionToStop.getBudget() / 2
                    ));

                    // Stop the execution of the worker of the session to stop.
                    sessionToStop.stopSession();

                    sessions.remove(slotNo);
                }

                break;
            }

            default:
                System.out.println("[sim] <- cmd 0x" + Integer.toHexString(form.getCmd()));
        }
    }

    // ── charge session simulation ─────────────────────────────────────────────

    private final Map<Integer, Session> sessions = new ConcurrentHashMap<>();

    private static final long CHARGE_DURATION_MS = 15_000;

    private void runChargeSession(int slotNo, Session session) {
        try {
            sessions.putIfAbsent(slotNo, session);

            // Confirm the socket energized.
            sendFrame(
                CMD.REC_0x0D_START_CHARGE_REPORT,
                nextSerial(),
                buildStartReport(session.getFlowNo(), slotNo, session.getBudget())
            );

            System.out.println("[sim] charging... (0x0D sent)");

            // Simulate a short charge, then the battery reaches full.
            Thread.sleep(CHARGE_DURATION_MS);

            session.setStatus(Status.COMPLETED);

            int consumed = session.getBudget(); // used the full budget in this simulated session
            sendOrderEnd(session.getFlowNo(), slotNo, session.getBudget(), consumed);

            System.out.println(
                "[sim] battery full — order complete (0x11 sent, slot="
                + slotNo
                + ", consumed="
                + consumed
                + ")"
            );
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.out.println("[sim] session error: " + e.getMessage());
        }
    }

    private void sendOrderEnd(String flowNo, int slotNo, int budget, int consumed) {
        try {
            sessions.remove(slotNo);

            sendFrame(CMD.REC_0x11_ORDER_END_REPORT, nextSerial(),
                    buildOrderEnd(flowNo, slotNo, budget, consumed));
        } catch (Exception e) {
            System.out.println("[sim] order-end error: " + e.getMessage());
        }
    }

    // ── device→server payload builders (hex strings) ──────────────────────────

    private String buildLoginData() {
        StringBuilder d = new StringBuilder();
        d.append(String.format("%010d", deviceId));       // 10-char DECIMAL device id
        d.append(ByteUtil.decimal2fitHex(10, 2));         // slotCount = 10
        d.append(ByteUtil.decimal2fitHex(1, 2));          // transVersion
        d.append(ByteUtil.decimal2fitHex(0, 2));          // deviceType: single-4G charger
        d.append(padHex(ByteUtil.convertStringToHex("TEASK-SIM"), 52)); // softVersion (26 bytes)
        d.append("00000000");                             // funcMask
        d.append(ByteUtil.decimal2fitHex(3500, 4));       // slotMaxPower (W)
        return d.toString();
    }

    private String buildStartReport(String flowNo, int slotNo, int money) {
        StringBuilder d = new StringBuilder();
        d.append(flowNo);                                 // 18
        d.append(ByteUtil.decimal2fitHex(slotNo, 2));
        d.append(ByteUtil.decimal2fitHex(0, 2));          // chargeType: scan
        d.append(ByteUtil.decimal2fitHex(money, 4));
        d.append(ByteUtil.decimal2fitHex(System.currentTimeMillis() / 1000, 8)); // startTime
        d.append(ByteUtil.decimal2fitHex(0, 4));          // power
        d.append(ByteUtil.decimal2fitHex(0, 2));          // chargeScheme length = 0
        return d.toString();
    }

    private String buildOrderEnd(String flowNo, int slotNo, int budget, int consumed) {
        long now = System.currentTimeMillis() / 1000;
        StringBuilder d = new StringBuilder();
        d.append(flowNo);                                 // 18
        d.append(ByteUtil.decimal2fitHex(slotNo, 2));
        d.append(ByteUtil.decimal2fitHex(0, 2));          // chargeType: scan
        d.append(ByteUtil.decimal2fitHex(0, 2));          // reason: normal end
        d.append(ByteUtil.decimal2fitHex(0, 4));          // errorCode
        d.append(ByteUtil.decimal2fitHex(now - 3, 8));    // startTime
        d.append(ByteUtil.decimal2fitHex(now, 8));        // endTime
        d.append(ByteUtil.decimal2fitHex(1, 4));          // time (minutes)
        d.append(ByteUtil.decimal2fitHex(50, 4));         // elec (0.50 kWh in 0.01 units)
        d.append(ByteUtil.decimal2fitHex(budget, 4));     // totalMoney (budget cents)
        d.append(ByteUtil.decimal2fitHex(consumed, 4));   // money consumed cents
        d.append(ByteUtil.decimal2fitHex(300, 4));        // maxPower (W)
        d.append(ByteUtil.decimal2fitHex(0, 2));          // extraData length = 0
        d.append(ByteUtil.decimal2fitHex(0, 2));          // chargeScheme length = 0
        return d.toString();
    }

    // ── framing helpers ───────────────────────────────────────────────────────

    /** Build a full frame with the SDK encoder and write it to the socket. */
    private synchronized void sendFrame(int cmd, int serialNo, String dataHex) throws Exception {
        SendMessageForm sm = new SendMessageForm();
        sm.setDeviceId(deviceId);
        sm.setSubDeviceId(null);
        sm.setSerialNo(serialNo);
        sm.setCmd(cmd);
        sm.setData(dataHex);
        String hex = BaseProtocol.enCode(sm);
        out.write(ByteUtil.hexStr2bytes(hex));
        out.flush();
    }

    /** Read one length-prefixed frame: FE | LEN(2) | (LEN bytes). Returns null on EOF. */
    private static byte[] readFrame(InputStream in) throws Exception {
        int sop = in.read();
        if (sop == -1) return null;
        if ((sop & 0xFF) != 0xFE) return readFrame(in); // resync to next SOP
        int hi = in.read(), lo = in.read();
        if (hi == -1 || lo == -1) return null;
        int len = ((hi & 0xFF) << 8) | (lo & 0xFF);
        byte[] rest = readN(in, len);
        if (rest == null) return null;
        byte[] frame = new byte[3 + len];
        frame[0] = (byte) 0xFE;
        frame[1] = (byte) hi;
        frame[2] = (byte) lo;
        System.arraycopy(rest, 0, frame, 3, len);
        return frame;
    }

    private static byte[] readN(InputStream in, int n) throws Exception {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r == -1) return null;
            off += r;
        }
        return buf;
    }

    private static String padHex(String hex, int len) {
        if (hex.length() >= len) return hex.substring(0, len);
        StringBuilder sb = new StringBuilder(hex);
        while (sb.length() < len) sb.append('0');
        return sb.toString();
    }

    private int nextSerial() {
        serial = (serial + 1) & 0xFFFF;
        return serial;
    }
}
