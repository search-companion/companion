package org.searchcompanion.rest;

import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.test.blueprint.CamelBlueprintTestSupport;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.apache.http.HttpStatus.SC_OK;

public class RestRouteBuilderTest extends CamelBlueprintTestSupport {

    @Override
    protected String getBlueprintDescriptor() {
        return "OSGI-INF/blueprint/companion-rest.xml";
    }

    @Override
    public String[] loadConfigAdminConfigurationFile() {
        return new String[]{
                "src/main/resources/companion.rest.cfg",
                "companion.rest"
        };
    }

    @Override
    public boolean isUseAdviceWith() {
        return false;
    }

    @Override
    public boolean isDumpRouteCoverage() {
        return true;
    }

    @Before
    public void setUpCamelContext() throws Exception {
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct-vm:rest-task")
                        .id("rest-task")
                        .log(LoggingLevel.INFO, "Task request received from REST API:\nheaders: ${headers}\npayload: ${body}")
                        .to("mock:rest-task")
                        .process(exchange -> {
                            Map<String, Object> response = new LinkedHashMap<>();
                            response.put("status", SC_OK);
                            response.put("action", exchange.getMessage().getHeader("action"));
                            response.put("collection", exchange.getMessage().getHeader("collection"));
                            exchange.getMessage().setBody(response);
                        });
                from("direct:http-test-endpoint")
                        .id("http-test-client-route")
                        .setHeader(Exchange.HTTP_QUERY, constant("action=testAction"))
                        .to("http://localhost:{{rest.port}}{{rest.context-path}}/c/testCollection1")
                        .setBody(simple("${bodyAs(String)}"))
                        .to("mock:http-test-response")
                        .log(LoggingLevel.INFO, "Task response received from REST API:\nheaders: ${headers}\npayload: ${body}");
            }
        });
    }

    @Test
    public void restTest() throws Exception {
        assertTrue(context.getStatus().isStarted());

        MockEndpoint mockRestAction = getMockEndpoint("mock:rest-task");
        mockRestAction.expectedMessageCount(1);

        ProducerTemplate template1 = context.createProducerTemplate();
        template1.sendBody("direct:http-test-endpoint", null);
        assertMockEndpointsSatisfied(60, TimeUnit.SECONDS);

        MockEndpoint mockHttpResponse = getMockEndpoint("mock:http-test-response");
        Exchange httpResponse = mockHttpResponse.getReceivedExchanges().get(0);
        assertEquals(SC_OK, httpResponse.getMessage().getHeader("CamelHttpResponseCode"));
        String expectedHttpResponse = "{\n" +
                "  \"status\" : 200,\n" +
                "  \"action\" : \"testAction\",\n" +
                "  \"collection\" : \"testCollection1\"\n" +
                "}";
        assertEquals(expectedHttpResponse, httpResponse.getMessage().getBody(String.class));
    }

}