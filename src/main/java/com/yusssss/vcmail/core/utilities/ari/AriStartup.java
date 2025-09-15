package com.yusssss.vcmail.core.utilities.ari;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class AriStartup implements ApplicationRunner {

    private final AriConnectionManager ariConnectionManager;
    private final Logger logger = LoggerFactory.getLogger(AriStartup.class);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public AriStartup(AriConnectionManager ariConnectionManager) {
        this.ariConnectionManager = ariConnectionManager;
    }

    @Override
    public void run(ApplicationArguments args) {
        logger.info("ApplicationRunner started. Scheduling periodic ARI connection attempts.");

        Runnable connectTask = () -> {
            if (ariConnectionManager.isConnected()) {
                return;
            }
            ariConnectionManager.connect();
        };
        scheduler.scheduleAtFixedRate(connectTask, 5, 30, TimeUnit.SECONDS);
    }
}