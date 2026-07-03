package cn.nblinks.teask.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point. Starts the TCP server (for chargers) and the HTTP control plane
 * (for the TEASK backend), then blocks on the TCP channel.
 */
public final class GatewayApp {

    private static final Logger log = LoggerFactory.getLogger(GatewayApp.class);

    public static void main(String[] args) throws Exception {
        log.info("Starting TEASK charger TCP gateway...");
        log.info("  TCP (chargers)   : port {}", GatewayConfig.TCP_PORT);
        log.info("  HTTP (control)   : port {}", GatewayConfig.HTTP_PORT);
        log.info("  Backend reports  : {}{}", GatewayConfig.BACKEND_URL, GatewayConfig.BACKEND_REPORT_PATH);

        GatewayServer tcp = new GatewayServer();
        ControlHttpServer.create().start();

        Runtime.getRuntime().addShutdownHook(new Thread(tcp::stop));

        // Bind and block until the server socket closes.
        tcp.start().channel().closeFuture().sync();
    }

    private GatewayApp() {}
}
