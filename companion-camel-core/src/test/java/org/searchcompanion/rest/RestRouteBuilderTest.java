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
                "src/test/resources/companion.core.cfg",
                "companion.core"
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
                from("direct:task-init")
                        .id("test-rest-task")
                        .log(LoggingLevel.INFO, "Task request received from REST API:\nheaders: ${headers}\npayload: ${body}")
                        .to("mock:test-rest-task")
                        .process(exchange -> {
                            Map<String, Object> response = new LinkedHashMap<>();
                            response.put("status", SC_OK);
                            response.put("action", exchange.getMessage().getHeader("action"));
                            response.put("collection", exchange.getMessage().getHeader("collection"));
                            exchange.getMessage().setBody(response);
                        });
                from("direct:test-generate-http-client-request")
                        .setHeader(Exchange.HTTP_QUERY, constant("action=testAction"))
                        .to("http://localhost:{{rest.port}}{{rest.contextPath}}/c/testCollection1")
                        .setBody(simple("${bodyAs(String)}"))
                        .to("mock:test-http-client-response")
                        .log(LoggingLevel.INFO, "Task response received from REST API:\nheaders: ${headers}\npayload: ${body}");
            }
        });
    }

    @Test
    public void restTest() throws Exception {
        assertTrue(context.getStatus().isStarted());

        MockEndpoint mockRestAction = getMockEndpoint("mock:test-rest-task");
        mockRestAction.expectedMessageCount(1);

        ProducerTemplate template1 = context.createProducerTemplate();
        template1.sendBody("direct:test-generate-http-client-request", null);
        assertMockEndpointsSatisfied(60, TimeUnit.SECONDS);

        MockEndpoint mockHttpResponse = getMockEndpoint("mock:test-http-client-response");
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