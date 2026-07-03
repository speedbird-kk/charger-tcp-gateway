package cn.nblinks.teask.gateway;

import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Forwards decoded device reports to the TEASK Node backend over HTTP.
 *
 * This replaces the original Feign call to the missing `protocolApi` service
 * (ProtocolFromIotClient / charger-protocol-client). Fire-and-forget: if the
 * backend is down the gateway keeps running and just logs — a device staying
 * connected must never depend on the business backend being up.
 */
public final class BackendClient {

    private static final Logger log = LoggerFactory.getLogger(BackendClient.class);
    private static final ExecutorService pool = Executors.newFixedThreadPool(4);
    private static final String URL_STR = GatewayConfig.BACKEND_URL + GatewayConfig.BACKEND_REPORT_PATH;

    private BackendClient() {}

    /** POST a report to the backend asynchronously. */
    public static void postReport(JSONObject payload) {
        pool.submit(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(URL_STR);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                byte[] body = payload.toJSONString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }
                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    log.info("→ backend {} ({})", payload.getString("event"), code);
                } else {
                    log.warn("→ backend {} returned {}", payload.getString("event"), code);
                }
            } catch (Exception e) {
                // Expected until the Node backend (Phase 3) exists / is running.
                log.info("→ backend unreachable for {} ({})",
                        payload.getString("event"), e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }
}
