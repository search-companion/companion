package org.searchcompanion.core;

import org.apache.camel.Exchange;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.test.blueprint.CamelBlueprintTestSupport;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.Collectors;

public class TaskDispatcherRouteBuilderTest extends CamelBlueprintTestSupport {

    private static final Logger LOG = LoggerFactory.getLogger(TaskDispatcherRouteBuilderTest.class);

    @Override
    protected String getBlueprintDescriptor() {
        return "OSGI-INF/blueprint/companion-core.xml";
    }

    @Override
    public boolean isUseAdviceWith() {
        return true;
    }

    @Before
    public void setUpCamelContext() throws Exception {
        context.setTracing(false);
    }

    @Test
    public void initializeTest() throws Exception {
        AdviceWith.adviceWith(
                context,
                "t0-init-app",
                a -> a.weaveAddLast().to("mock:t0-init-app")
        );
        startCamelContext();
        MockEndpoint mock = getMockEndpoint("mock:t0-init-app");
        mock.expectedMessageCount(1);
        assertMockEndpointsSatisfied();
    }

    @Test
    public void singleThreadTest() throws Exception {
        AdviceWith.adviceWith(
                context,
                "t0-init-app",
                a -> a.replaceFromWith("direct:no-init")
        );
        context.addRoutes(
                new RouteBuilder() {
                    @Override
                    public void configure() throws Exception {
                        from("timer:test-entry?delay=100&period=20&repeatCount=10&synchronous=false")
                                .setProperty("TaskId", simple("TIMER-${exchangeProperty.CamelTimerCounter}"))
                                .setProperty("DelayTimeOut", constant(100L))
                                .bean("handler", "setSingleThreadProcess")
                                .to("direct:t1-dispatch-task");
                    }
                }
        );
        AdviceWith.adviceWith(
                context,
                "t1-dispatch-task",
                a -> {
                    a.weaveAddLast().to("mock:t1-dispatch-task");
                    a.weaveByToString(".*task REJECTED.*").after().to("mock:t1-dispatch-task-exception");
                }
        );
        AdviceWith.adviceWith(
                context,
                "t2-single-task",
                a -> a.weaveAddLast().to("mock:t2-single-task")
        );
        AdviceWith.adviceWith(
                context,
                "t2-parallel-task",
                a -> a.weaveAddLast().to("mock:t2-parallel-task")
        );
        AdviceWith.adviceWith(
                context,
                "t3-process-task",
                a -> a.weaveAddLast().to("mock:t3-process-task")
        );
        startCamelContext();
        MockEndpoint mockT1Dispatch = getMockEndpoint("mock:t1-dispatch-task");
        MockEndpoint mockT1Exception = getMockEndpoint("mock:t1-dispatch-task-exception");
        MockEndpoint mockT2Single = getMockEndpoint("mock:t2-single-task");
        MockEndpoint mockT2Parallel = getMockEndpoint("mock:t2-parallel-task");
        MockEndpoint mockT3Process = getMockEndpoint("mock:t3-process-task");
        mockT3Process.expectedMessageCount(7);
        assertMockEndpointsSatisfied();
        assertEquals("Received messages mockT1Dispatch", 7, mockT1Dispatch.getReceivedCounter());
        assertEquals("Rejected messages mockT1Exception", 13, mockT1Exception.getReceivedCounter()); // onexception gathers all + exception
        assertEquals("Received messages mockT2Single", 2 * 2, mockT2Single.getReceivedCounter()); // 2 in blocking queue (double submission to block queue)
        assertEquals("Received messages mockT2Parallel", 5, mockT2Parallel.getReceivedCounter()); // 5 in parallel
        assertEquals("Received messages mockT3Process", 7, mockT3Process.getReceivedCounter()); // 7 total processed
        Integer[] actualRejected = mockT1Exception.getReceivedExchanges().stream().map(exchange -> exchange.getProperty(Exchange.TIMER_COUNTER, Integer.class)).collect(Collectors.toList()).toArray(new Integer[0]);
        assertArrayEquals("Expected rejected", new Integer[] {1,2,3,4,4,5,6,6,7,8,9,10,10}, actualRejected);
    }

}