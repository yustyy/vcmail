package com.yusssss.vcmail.core.utilities.asterisk;

import org.asteriskjava.fastagi.AgiChannel;
import org.asteriskjava.fastagi.AgiException;
import org.asteriskjava.fastagi.AgiRequest;
import org.asteriskjava.fastagi.BaseAgiScript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class HelloAgiScript extends BaseAgiScript {

    private final Logger logger = LoggerFactory.getLogger(HelloAgiScript.class);


    @Override
    public void service(AgiRequest agiRequest, AgiChannel agiChannel) throws AgiException {
        logger.info("Received incoming call from: {}", agiRequest.getCallerIdNumber());
        streamFile("hello-world");
        sayAlpha("Hos geldiniz");
        sayDigits(agiRequest.getCallerIdNumber());

        waitForDigit(1000);
    }
}
