package org.searchcompanion.core;

import org.apache.camel.Exchange;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.component.timer.TimerEndpoint;
import org.apache.camel.test.blueprint.CamelBlueprintTestSupport;
import org.junit.Before;
import org.junit.Test;

import java.util.stream.Collectors;

public class TaskOrchestrationRouteBuilderTest extends CamelBlueprintTestSupport {

    @Override
    protected String getBlueprintDescriptor() {
        return "OSGI-INF/blueprint/companion-core.xml";
    }

    @Override
    public String[] loadConfigAdminConfigurationFile() {
        return new String[]{
                "src/main/resources/companion.core.cfg",
                "companion.core"
        };
    }
    @Override
    public boolean isUseAdviceWith() {
        return true;
    }

    @Override
    public boolean isDumpRouteCoverage() {
        return true;
    }

    @Before
    public void setUpCamelContext() {
        context.setTracing(false);
    }

    @Test
    public void initializeTest() throws Exception {
        AdviceWith.adviceWith(
                context,
                "initialize",
                a -> a.weaveAddLast().to("mock:initialize")
        );
        startCamelContext();
        MockEndpoint mock = getMockEndpoint("mock:initialize");
        mock.expectedMessageCount(1);
        assertMockEndpointsSatisfied();
    }

    @Test
    public void singleThreadTest() throws Exception {
        context.addRoutes(
                new RouteBuilder() {
                    @Override
                    public void configure() {
                        from("timer:test-entry?delay=100&period=20&repeatCount=10&synchronous=false")
                                .routeId("test-init")
                                .setProperty("TaskId", simple("TIMER-${exchangeProperty.CamelTimerCounter}"))
                                .setProperty("DelayTimeOut", constant(100L))
                                .process(exchange -> {
                                    int counter = exchange.getProperty(Exchange.TIMER_COUNTER, Integer.class);
                                    exchange.setProperty ("isSingleThreadProcess", (counter & 1) == 0);
                                })
                                .to("direct:dispatch-task");
                    }
                }
        );
        AdviceWith.adviceWith(
                context,
                "dispatch-task",
                a -> a.weaveAddLast().to("mock:dispatch-task")
        );
        AdviceWith.adviceWith(
                context,
                "dispatch-task-rejected",
                a -> a.weaveAddLast().to("mock:dispatch-task-rejected")
        );
        AdviceWith.adviceWith(
                context,
                "process-task",
                a -> a.weaveAddLast().to("mock:process-task")
        );
        startCamelContext();
        MockEndpoint mockP1 = getMockEndpoint("mock:dispatch-task");
        MockEndpoint mockP1Exception = getMockEndpoint("mock:dispatch-task-rejected");
        MockEndpoint mockP2 = getMockEndpoint("mock:process-task");
        Integer[] expectedRejected = new Integer[] {4,6,10};
        mockP1Exception.expectedMessageCount(expectedRejected.length);
        TimerEndpoint timerEndpoint = ((TimerEndpoint) context.getEndpoints().stream().filter(ep -> ep instanceof TimerEndpoint).findFirst().orElse(context.getEndpoint("timer://dummy")));
        int exchangesCount = Long.valueOf(timerEndpoint.getRepeatCount()).intValue();
        mockP2.expectedMessageCount(exchangesCount - expectedRejected.length);
        assertMockEndpointsSatisfied();
        assertEquals("Received messages mockP1", exchangesCount - expectedRejected.length, mockP1.getReceivedCounter());
        assertEquals("Rejected messages mockP1Exception", expectedRejected.length, mockP1Exception.getReceivedCounter()); // 3 rejected
        assertEquals("Received messages mockT3Process", exchangesCount - expectedRejected.length, mockP2.getReceivedCounter()); // 7 total processed
        Integer[] actualRejected = mockP1Exception.getReceivedExchanges().stream().map(exchange -> exchange.getProperty(Exchange.TIMER_COUNTER, Integer.class)).toArray(Integer[]::new);
        assertArrayEquals("Expected rejected", expectedRejected, actualRejected);
    }

}