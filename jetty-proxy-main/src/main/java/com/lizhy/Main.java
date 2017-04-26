package com.lizhy;

import com.lizhy.config.JettyProxyConfig;
import com.lizhy.constants.ProjectConstants;
import com.lizhy.server.JettyProxyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Properties;

/**
 * 主函数入口
 * Created by lizhiyang on 2017-04-26 11:18.
 */
public class Main {
    private static Logger logger = LoggerFactory.getLogger(Main.class);
    public static void main(String[] args) {
        try {
            final JettyProxyServer server = new JettyProxyServer(loadConfig());
            server.startServer();

            Runtime.getRuntime().addShutdownHook(new Thread("jetty-proxy-server-shutdown-hook") {
                @Override
                public void run() {
                    try {
                        server.stopServer();
                    } catch (Exception e) {
                        logger.error("stop server exception", e);
                    }
                }
            });
        } catch (Exception e) {
            logger.error("Main execute exeption", e);
        }
    }

    private static JettyProxyConfig loadConfig() throws Exception {
        JettyProxyConfig config = new JettyProxyConfig();
        BufferedReader reader = new BufferedReader(new FileReader(ProjectConstants.JETTY_CONF_FILE));
        Properties properties = new Properties();
        properties.load(reader);
        config.setHttpPort(Integer.parseInt(properties.getProperty(ProjectConstants.JETTY_HTTP_PORT)));
        config.setMinServerThread(Integer.parseInt(properties.getProperty(ProjectConstants.JETTY_MIN_THREAD)));
        config.setMaxServerThread(Integer.parseInt(properties.getProperty(ProjectConstants.JETTY_MAX_THREAD)));

        return config;
    }
}
