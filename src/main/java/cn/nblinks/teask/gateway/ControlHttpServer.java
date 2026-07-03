package cn.nblinks.teask.gateway;

import cn.nblinks.iot.dataform.encode.Snd0x08EncodeForm;
import cn.nblinks.iot.dataform.encode.Snd0x14EncodeForm;
import cn.nblinks.iot.utils.ByteUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * HTTP control plane the TEASK Node backend calls to drive a device.
 * Replaces the original RocketMQ inbound path (MqMessageListener): instead of a
 * business service publishing to a queue, it POSTs here and we push over TCP.
 *
 *   GET  /health
 *   GET  /devices
 *   POST /devices/{deviceId}/start   body: { slotNo, money, flowNo?, chargeType? }
 *   POST /devices/{deviceId}/stop    body: { slotNo }
 */
public final class ControlHttpServer {

    private static final Logger log = LoggerFactory.getLogger(ControlHttpServer.class);

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(GatewayConfig.HTTP_PORT), 0);
        server.createContext("/health", this::handleHealth);
        server.createContext("/devices", this::handleDevices);
        server.setExecutor(null); // default executor
        server.start();
        log.info("HTTP control plane listening on port {}", GatewayConfig.HTTP_PORT);
    }

    private void handleHealth(HttpExchange ex) throws IOException {
        JSONObject j = new JSONObject();
        j.put("ok", true);
        j.put("tcpPort", GatewayConfig.TCP_PORT);
        j.put("online", DeviceRegistry.get().onlineDeviceIds());
        respond(ex, 200, j);
    }

    private void handleDevices(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();       // /devices or /devices/{id}/{action}
        String[] parts = path.split("/");                  // ["", "devices", id, action]

        // GET /devices — list who's online
        if (parts.length <= 2) {
            JSONObject j = new JSONObject();
            j.put("online", DeviceRegistry.get().onlineDeviceIds());
            respond(ex, 200, j);
            return;
        }

        if (parts.length < 4) {
            respond(ex, 404, error("unknown route"));
            return;
        }

        Integer deviceId;
        try {
            deviceId = Integer.valueOf(parts[2]);
        } catch (NumberFormatException e) {
            respond(ex, 400, error("bad deviceId"));
            return;
        }
        String action = parts[3];

        Channel channel = DeviceRegistry.get().channelOf(deviceId);
        if (channel == null || !channel.isActive()) {
            respond(ex, 404, error("device offline: " + deviceId));
            return;
        }

        JSONObject body = readBody(ex);

        switch (action) {
            case "start": handleStart(ex, deviceId, channel, body); break;
            case "stop":  handleStop(ex, deviceId, channel, body);  break;
            default:      respond(ex, 404, error("unknown action: " + action));
        }
    }

    private void handleStart(HttpExchange ex, Integer deviceId, Channel channel, JSONObject body)
            throws IOException {
        int slotNo     = body.getIntValue("slotNo");
        int money      = body.getIntValue("money");        // budget in cents
        int chargeType = body.containsKey("chargeType") ? body.getIntValue("chargeType") : 0;
        String flowNo  = body.getString("flowNo");
        if (flowNo == null || flowNo.length() != 18) {
            flowNo = newFlowNo();                            // 18 hex chars = 9 bytes
        }

        int serial = DeviceRegistry.get().nextSerial(deviceId);
        // chargeScheme=null (device uses its own tariff), cardNo="", count=0, cardBalance=0 for scan charge.
        Snd0x08EncodeForm cmd = new Snd0x08EncodeForm(
                deviceId, null, serial, slotNo, chargeType, flowNo, money, null, "", 0, 0);
        Sender.send(channel, cmd);
        log.info("start → device {} slot {} money {} flowNo {}", deviceId, slotNo, money, flowNo);

        JSONObject j = new JSONObject();
        j.put("ok", true);
        j.put("deviceId", deviceId);
        j.put("slotNo", slotNo);
        j.put("flowNo", flowNo);
        j.put("serialNo", serial);
        respond(ex, 200, j);
    }

    private void handleStop(HttpExchange ex, Integer deviceId, Channel channel, JSONObject body)
            throws IOException {
        int slotNo = body.getIntValue("slotNo");
        int serial = DeviceRegistry.get().nextSerial(deviceId);
        Sender.send(channel, new Snd0x14EncodeForm(deviceId, null, serial, slotNo));
        log.info("stop → device {} slot {}", deviceId, slotNo);

        JSONObject j = new JSONObject();
        j.put("ok", true);
        j.put("deviceId", deviceId);
        j.put("slotNo", slotNo);
        j.put("serialNo", serial);
        respond(ex, 200, j);
    }

    /** 18 hex chars (9 bytes), matching the flowNo width the SDK reads on 0x0D/0x11. */
    private static String newFlowNo() {
        return ByteUtil.decimal2fitHex(System.currentTimeMillis(), 18);
    }

    private static JSONObject readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            byte[] raw = readAll(is);
            if (raw.length == 0) return new JSONObject();
            return JSON.parseObject(new String(raw, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static byte[] readAll(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static JSONObject error(String msg) {
        JSONObject j = new JSONObject();
        j.put("ok", false);
        j.put("error", msg);
        return j;
    }

    private static void respond(HttpExchange ex, int code, JSONObject body) throws IOException {
        byte[] out = body.toJSONString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    private ControlHttpServer() {}

    public static ControlHttpServer create() {
        return new ControlHttpServer();
    }
}
