package com.yusssss.vcmail.core.utilities.rtp;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RtpListenerFactory {

    private final Map<String, RtpListener> activeListeners = new ConcurrentHashMap<>();

    private int nextPort = 10000;


    public synchronized RtpListener createListener(String callId) {
        // TODO: Portun kullanımda olup olmadığını kontrol etme mantığı eklenebilir.
        int portToUse = nextPort;
        nextPort += 2;

        if (nextPort > 20000) {
            nextPort = 10000;
        }

        RtpListener listener = new RtpListener(callId, portToUse);
        activeListeners.put(callId, listener);

        return listener;
    }

    public void stopListener(String callId) {
        if (activeListeners.containsKey(callId)) {
            activeListeners.get(callId).stop();
            activeListeners.remove(callId);
        }
    }
}