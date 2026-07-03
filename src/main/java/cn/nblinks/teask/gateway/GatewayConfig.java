package cn.nblinks.teask.gateway;

/**
 * All gateway tunables in one place. Everything is overridable via environment
 * variables so the same jar runs locally, in CI, or against a remote backend.
 */
public final class GatewayConfig {

    /** TCP port the chargers connect to. Matches the original charger-iot-api NettyConfig.PORT. */
    public static final int TCP_PORT = envInt("GATEWAY_TCP_PORT", 6666);

    /** HTTP port the TEASK Node backend calls to inject start/stop commands. */
    public static final int HTTP_PORT = envInt("GATEWAY_HTTP_PORT", 8080);

    /** Base URL of the TEASK Node backend that receives device reports. */
    public static final String BACKEND_URL =
            env("TEASK_BACKEND_URL", "http://localhost:3001");

    /** Path on the backend that receives every decoded device report. */
    public static final String BACKEND_REPORT_PATH =
            env("TEASK_BACKEND_REPORT_PATH", "/webhooks/charger/report");

    /** Max TCP frame length (bytes). Matches the original NettyConfig.MAX_FRAME_LENGTH. */
    public static final int MAX_FRAME_LENGTH = 512;

    /** Heartbeat cycle (seconds). Matches the original NettyConfig.PING_CYCLE. */
    public static final int PING_CYCLE_SECONDS = 30;

    private GatewayConfig() {}

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isEmpty()) ? def : v;
    }

    private static int envInt(String key, int def) {
        try {
            return Integer.parseInt(env(key, Integer.toString(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
