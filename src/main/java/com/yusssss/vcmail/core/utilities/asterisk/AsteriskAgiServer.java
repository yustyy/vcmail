package com.yusssss.vcmail.core.utilities.asterisk;

import org.asteriskjava.fastagi.AgiScript;
import org.asteriskjava.fastagi.AgiServerThread;
import org.asteriskjava.fastagi.DefaultAgiServer;
import org.asteriskjava.fastagi.StaticMappingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class AsteriskAgiServer implements CommandLineRunner {

    private final Logger logger = LoggerFactory.getLogger(AsteriskAgiServer.class);


    private HelloAgiScript helloAgiScript;


    public AsteriskAgiServer(HelloAgiScript helloAgiScript) {
        this.helloAgiScript = helloAgiScript;
    }

    @Override
    public void run(String... args) throws Exception {
        logger.info("Starting Asterisk AGI Server...");

        final DefaultAgiServer agiServer = new DefaultAgiServer();


        final StaticMappingStrategy mappingStrategy = new StaticMappingStrategy();
        mappingStrategy.setAgiScript(helloAgiScript);
        agiServer.setMappingStrategy(mappingStrategy);

        agiServer.setPort(4573);

        Thread agiServerThread = new Thread(() -> {
            try {
                logger.info("Agi server thread is starting up!");
                agiServer.startup();
            } catch (Exception e) {
                logger.error("Error starting Asterisk AGI Server", e);
            }
        });

        agiServerThread.setName("AgiServerListenerThread");
        agiServerThread.setDaemon(true);
        agiServerThread.start();

        logger.info("Started Asterisk AGI Server on port 4573");

    }
}
