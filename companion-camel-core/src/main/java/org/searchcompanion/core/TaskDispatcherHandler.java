package org.searchcompanion.core;

import org.apache.camel.Exchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TaskDispatcherHandler {

    public static void delay(Exchange exchange) throws InterruptedException {
        long sleepTimeMillis = exchange.getProperty("DelayTimeOut", 20L, Long.class);
        Thread.sleep(sleepTimeMillis);
    }

    public static void setSingleThreadProcess(Exchange exchange) {
        int counter = exchange.getProperty(Exchange.TIMER_COUNTER, Integer.class);
        exchange.setProperty ("isSingleThreadProcess", (counter & 1) == 0);
    }

}
