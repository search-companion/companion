package org.searchcompanion.dataimport;

import org.apache.camel.EndpointInject;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.Produce;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.builder.ExchangeBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.component.solr.SolrConstants;
import org.apache.camel.component.sql.SqlComponent;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.spi.PropertiesComponent;
import org.apache.camel.test.blueprint.CamelBlueprintTestSupport;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingServer;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.SolrInputField;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

public class DataImportTest extends CamelBlueprintTestSupport {

    private static final List<String> SQL_CREATE_STATEMENTS =
            Arrays.asList(
                    new String[]{
                            "create table %1$s\n" +
                                    "(\n" +
                                    "\tID_FIELD VARCHAR(36) not null,\n" +
                                    "\tLAST_MODIFIED timestamp default CURRENT TIMESTAMP,\n" +
                                    "\tDATA VARCHAR(100)\n" +
                                    ")",
                            "create unique index %1$s_ID_UINDEX\n" +
                                    "\ton %1$s (ID_FIELD)",
                            "alter table %1$s\n" +
                                    "\tadd constraint %1$s_PK\n" +
                                    "\t\tprimary key (ID_FIELD)"
                    }
            );

    private static final String SQL_INSERT_STATEMENT =
                            "insert into ##TABLE_NAME##\n" +
                                    "\t(ID_FIELD, LAST_MODIFIED, DATA)\n" +
                                    "\tvalues ('##DUMMY_ID##', '##CURRENT_TIMESTAMP##', '##DATA##')";

    public static final DateTimeFormatter DATE_TIME_FORMATTER = new DateTimeFormatterBuilder()
                    .parseCaseInsensitive()
                    .parseLenient()
                    .appendInstant()
                    .toFormatter(Locale.ROOT)
                    .withZone(ZoneId.of("Z"));

    @EndpointInject("mock:data-import-process")
    protected MockEndpoint mockDataImportProcess;
    @EndpointInject("mock:dummy-process")
    protected MockEndpoint mockDummyProcess;

    @Produce("direct:data-import-full")
    protected ProducerTemplate templateDataImportFull;
    @Produce("direct:data-import-delta")
    protected ProducerTemplate templateDataImportDelta;
    @Produce("direct:data-import-process-id")
    protected ProducerTemplate templateDataImportProcessId;
    @Produce("direct:data-import-properties-save")
    protected ProducerTemplate templatePropertiesSave;
    @Produce("direct:dummy-process")
    protected ProducerTemplate templateDummyProcess;
    @Produce("direct:monitor-zk-client")
    protected ProducerTemplate templateMonitorZkClient;

    SqlComponent sqlComponent = null;
    static TestingServer zkServer;
    static CuratorFramework client;
    static File dataDir;
    LocalDateTime initialLocalDateTime = truncateFrom(LocalDateTime.now());
    RouteDefinition propertiesGetRouteDefinition;

    @Override
    public boolean isUseAdviceWith() {
        return true;
    }

    @Override
    public boolean isUseDebugger() {
        return false;
    }

    @Override
    protected void debugBefore(Exchange exchange, Processor processor, ProcessorDefinition<?> definition, String id, String label) {
        log.info("\nExchange " + exchange.getExchangeId() + " (" + exchange.getPattern().name() + ") BEFORE " + definition
                + " with body " + exchange.getIn().getBody()
                + "\nHeaders = " + exchange.getMessage().getHeaders()
                + "\nProperties = " + exchange.getMessage().getExchange().getProperties());
    }

    @Override
    protected void debugAfter(Exchange exchange, Processor processor, ProcessorDefinition<?> definition, String id, String label, long timeTaken) {
        log.info("\nExchange " + exchange.getExchangeId() + " (" + exchange.getPattern().name() + ") AFTER " + definition
                + " with body " + exchange.getIn().getBody()
                + "\nHeaders = " + exchange.getMessage().getHeaders()
                + "\nProperties = " + exchange.getMessage().getExchange().getProperties());
    }

    @Override
    protected String getBlueprintDescriptor() {
        return "OSGI-INF/blueprint/companion-dataimport-test-datasource.xml,OSGI-INF/blueprint/companion-dataimport-context.xml,OSGI-INF/blueprint/companion-dataimport-test.xml";
    }

    @Override
    protected String[] loadConfigAdminConfigurationFile() {
        return new String[]{
                "./src/test/resources/etc/companion.dataimport.test.cfg",
                "companion.dataimport",
                "./src/test/resources/etc/companion.dataimport.test.datasource.cfg",
                "companion.dataimport.test.datasource"
        };
    }

    @BeforeClass
    public static void setUpZk() throws Exception {
        Properties p = System.getProperties();
        p.setProperty("derby.stream.error.file", "./target/derby.log");
        dataDir = Files.createTempDirectory("zk-").toFile();
        deleteDirectory(dataDir);
        zkServer = new TestingServer(32181, dataDir, true);
        client = CuratorFrameworkFactory.newClient(zkServer.getConnectString(), new ExponentialBackoffRetry(1000, 3));
        client.start();
    }

    @AfterClass
    public static void tearDownZk() throws Exception {
        client.close();
        client = null;
        zkServer.stop();
    }

    @Before
    public void setUpCamelContext() throws Exception {
        sqlComponent = context.getComponent("sql", SqlComponent.class);
        createDbTable();
        initialLocalDateTime = truncateFrom(LocalDateTime.now());
        executeSQLInsertStatementWith(UUID.randomUUID().toString(), initialLocalDateTime);
        AdviceWith.adviceWith(
                context,
                "data-import-full",
                a -> a.replaceFromWith("direct:data-import-full")
        );
        AdviceWith.adviceWith(
                context,
                "data-import-delta",
                a -> a.replaceFromWith("direct:data-import-delta")
        );
        AdviceWith.adviceWith(context(), "data-import-process-id", a -> a.weaveAddLast().to("mock:data-import-process-id"));
        AdviceWith.adviceWith(
                context,
                "data-import-aggregate",
                a -> a.weaveByToUri("direct:solr-route").replace().to("mock:direct:solr-route")
        );
        AdviceWith.adviceWith(
                context,
                "data-import-aggregate-deleteddocquery",
                a -> a.weaveByToUri("direct:solr-route").replace().to("mock:direct:solr-route-delete")
        );
        AdviceWith.adviceWith(
                context,
                "data-import-final-commit",
                a -> a.weaveByToUri("direct:solr-route").replace().to("mock:direct:solr-route-commit")
        );
        AdviceWith.adviceWith(context, "data-import-process", a -> a.weaveAddLast().to(mockDataImportProcess));
        AdviceWith.adviceWith(context, "monitor-zk-client", a -> a.replaceFromWith("direct:monitor-zk-client"));
        AdviceWith.adviceWith(context, "data-import-process-start", a -> a.weaveAddLast().to("mock:data-import-process-start"));
        AdviceWith.adviceWith(context, "data-import-process-start", a -> a.weaveByToUri("controlbus:route?routeId=data-import-properties*").replace().delay(200L));
        AdviceWith.adviceWith(context, "solr-collections-api", a -> a.weaveById("endpoint-solr-collections-api").replace().to("mock:http-solr-collections-api"));
        propertiesGetRouteDefinition = context.getRouteDefinition("data-import-properties-get");
        propertiesGetRouteDefinition.autoStartup(false);
        context.setTracing(true);
        context.start();
    }

    @Override
    public void tearDown() {
        try {
            executeSQLStatement("drop table master");
            executeSQLStatement("drop table master2");
            executeSQLStatement("drop table master3");
            if (context != null) {
                context.stop();
            }
        } catch (Exception e) {}
    }

    public void createDbTable() throws SQLException {
        for (String sqlStatement : SQL_CREATE_STATEMENTS) {
            executeSQLStatement(String.format(sqlStatement, "MASTER"));
            executeSQLStatement(String.format(sqlStatement, "MASTER2"));
            executeSQLStatement(String.format(sqlStatement, "MASTER3"));
        }
    }

    public void prepareImportTaskFromFile(ImportTask importTask) throws InterruptedException {
        Exchange importTaskExchange = ExchangeBuilder.anExchange(context).withProperty("ImportTask", importTask).build();
        templatePropertiesSave.send(importTaskExchange);
        Thread.sleep(300L);
    }

    public void executeSQLInsertStatementWith(String idField, LocalDateTime localDateTime) throws SQLException {
        executeSQLInsertStatementWith("MASTER", UUID.randomUUID().toString(), localDateTime);
    }

    public void executeSQLInsertStatementWith(String tableName, String idField, LocalDateTime localDateTime) throws SQLException {
        executeSQLInsertStatementWith(tableName, idField, localDateTime, "some data,more data,test");
    }

    public void executeSQLInsertStatementWith(String tableName, String idField, LocalDateTime localDateTime, String data) throws SQLException {
        executeSQLStatement(
                SQL_INSERT_STATEMENT
                        .replace("##TABLE_NAME##", tableName)
                        .replace("##DUMMY_ID##", idField)
                        .replace("##CURRENT_TIMESTAMP##", Timestamp.valueOf(localDateTime).toString())
                        .replace("##DATA##", data)
        );
    }

    public void executeSQLStatement(String sqlStatement) throws SQLException {
        if (sqlComponent == null) {
            return;
        }
        int result = sqlComponent.getDataSource().getConnection().createStatement().executeUpdate(sqlStatement);
        log.info(String.format("setUpDB() SQL statement executed (result: %d):\n%s", result, sqlStatement));
    }

    @Test
    public void fullImportPageSizeTest() throws Exception {
        PropertiesComponent pc = context.getPropertiesComponent();
        String dateTimeFormat = pc.resolveProperty("solr.collection.dateformat").orElse("yyyyMMdd_HHmm");
        // test 1st full
        context.getRouteController().startRoute("data-import-properties-get");
        prepareImportTaskFromFile(new ImportTask());
        context.getRouteController().startRoute("data-import-full");
        Exchange exchange1 =
                ExchangeBuilder
                        .anExchange(context)
                        .withProperty("CamelTimerCounter", 81)
                        .build();
        templateDataImportFull.send(exchange1);
        mockDataImportProcess.expectedMinimumMessageCount(1);
        mockDataImportProcess.assertIsSatisfied();
        ImportTask importTask1 = mockDataImportProcess.getReceivedExchanges().get(0).getProperty("ImportTask", ImportTask.class);
        assertEquals("Import type not expected", ImportTask.ImportType.FULL, importTask1.getImportType());
        assertEquals("Dataimport-properties file not used", true, importTask1.isRetrievedFromFile());
        assertEquals("Number of documents processed not expected", 1, importTask1.getDocumentCounter());
        assertEquals("Import counter not expected", 81, importTask1.getTimerCounter().intValue());
        assertEquals("Processing state not expected", false, importTask1.isProcessing());
        assertEquals("From not expected", ImportTask.MIN_DATE_TIME, importTask1.getFrom());
        assertEquals("To not expected", initialLocalDateTime, importTask1.getTo());
        assertEquals("Committed not expected", initialLocalDateTime, importTask1.getCommitted());
        assertEquals("Modified must be after committed", true, importTask1.getModified().isAfter(initialLocalDateTime));
        assertEquals("Unexpected totalCount", Integer.valueOf(1), importTask1.getTotalCount());

        // test 2nd full
        mockDataImportProcess.reset();
        context.getRouteController().startRoute("data-import-full");
        LocalDateTime localDateTime1 = truncateFrom(LocalDateTime.now());
        executeSQLInsertStatementWith(UUID.randomUUID().toString(), localDateTime1);
        exchange1 =
                ExchangeBuilder
                        .anExchange(context)
                        .withProperty("CamelTimerCounter", 82)
                        .build();
        templateDataImportFull.send(exchange1);
        mockDataImportProcess.expectedMinimumMessageCount(1);
        mockDataImportProcess.assertIsSatisfied();
        importTask1 = mockDataImportProcess.getReceivedExchanges().get(0).getProperty("ImportTask", ImportTask.class);
        // String expectedCollection = String.format("gettingstarted_%s", localDateTime1.format((new DateTimeFormatterBuilder()).appendPattern(dateTimeFormat).toFormatter()));
        //assertEquals("Collection not expected", expectedCollection, importTask1.getCollection());
        assertEquals("Import type not expected", ImportTask.ImportType.FULL, importTask1.getImportType());
        assertEquals("Dataimport-properties file not used", true, importTask1.isRetrievedFromFile());
        assertEquals("Number of documents processed not expected", 2, importTask1.getDocumentCounter());
        assertEquals("Import counter not expected", 82, importTask1.getTimerCounter().intValue());
        assertEquals("Processing state not expected", false, importTask1.isProcessing());
        assertEquals("From not expected", ImportTask.MIN_DATE_TIME, importTask1.getFrom());
        assertEquals("To not expected", localDateTime1, importTask1.getTo());
        assertEquals("Committed not expected", localDateTime1, importTask1.getCommitted());
        assertEquals("Modified must be after committed", true, importTask1.getModified().isAfter(localDateTime1));
        assertEquals("Unexpected totalCount", Integer.valueOf(2), importTask1.getTotalCount());

        // test 1st delta after full
        mockDataImportProcess.reset();
        LocalDateTime localDateTime2 = truncateFrom(LocalDateTime.now());
        executeSQLInsertStatementWith(UUID.randomUUID().toString(), localDateTime2);
        exchange1 =
                ExchangeBuilder
                        .anExchange(context)
                        .withProperty("CamelTimerCounter", 93)
                        .build();
        templateDataImportDelta.send(exchange1);
        mockDataImportProcess.expectedMinimumMessageCount(1);
        mockDataImportProcess.assertIsSatisfied();
        importTask1 = mockDataImportProcess.getReceivedExchanges().get(0).getProperty("ImportTask", ImportTask.class);
        assertEquals("Import type not expected", ImportTask.ImportType.DELTA, importTask1.getImportType());
        assertEquals("Dataimport-properties file not used", true, importTask1.isRetrievedFromFile());
        assertEquals("Number of documents processed not expected", 1, importTask1.getDocumentCounter());
        assertEquals("Import counter not expected", 93, importTask1.getTimerCounter().intValue());
        assertEquals("Processing state not expected", false, importTask1.isProcessing());
        assertEquals("From not expected", localDateTime1, importTask1.getFrom());
        assertEquals("To not expected", localDateTime2, importTask1.getTo());
        assertEquals("Committed not expected", localDateTime2, importTask1.getCommitted());
        assertEquals("Modified must be after committed", true, importTask1.getModified().isAfter(localDateTime2));
        assertEquals("Unexpected totalCount", Integer.valueOf(1), importTask1.getTotalCount());

    }

    @Test
    public void deltaImportGenericTest() throws Exception {
        context.getRouteController().startRoute("data-import-properties-get");
        prepareImportTaskFromFile(new ImportTask());
        Exchange exchange1 =
                ExchangeBuilder
                    .anExchange(context)
                        .withProperty("CamelTimerCounter", 91)
                        .build();
        templateDataImportDelta.send(exchange1);
        mockDataImportProcess.expectedMinimumMessageCount(1);
        mockDataImportProcess.assertIsSatisfied();
        ImportTask importTask1 = mockDataImportProcess.getReceivedExchanges().get(0).getProperty("ImportTask", ImportTask.class);
        assertEquals("Import type not expected", ImportTask.ImportType.DELTA, importTask1.getImportType());
        assertEquals("Dataimport-properties file not used", true, importTask1.isRetrievedFromFile());
        assertEquals("Number of documents processed not expected", 1, importTask1.getDocumentCounter());
        assertEquals("Import counter not expected", 91, importTask1.getTimerCounter().intValue());
        assertEquals("Processing state not expected", false, importTask1.isProcessing());
        assertEquals("From not expected", initialLocalDateTime.minus(1, ChronoUnit.MILLIS), importTask1.getFrom());
        assertEquals("To not expected", initialLocalDateTime, importTask1.getTo());
        assertEquals("Committed not expected", initialLocalDateTime, importTask1.getCommitted());
        assertEquals("Modified must be after committed", true, importTask1.getModified().isAfter(initialLocalDateTime));
        assertEquals("Unexpected totalCount", Integer.valueOf(1), importTask1.getTotalCount());
        assertEquals("ImportTask in zkNode not saved", importTask1.toString(), getImportTaskFromZkNode("/data-import-gettingstarted").toString());

        mockDataImportProcess.reset();
        LocalDateTime localDateTime1 = truncateFrom(LocalDateTime.now());
        executeSQLInsertStatementWith(UUID.randomUUID().toString(), localDateTime1);
        exchange1 =
                ExchangeBuilder
                        .anExchange(context)
                        .withProperty("CamelTimerCounter", 92)
                       .build();
        templateDataImportDelta.send(exchange1);
        mockDataImportProcess.expectedMinimumMessageCount(1);
        mockDataImportProcess.assertIsSatisfied();
        importTask1 = mockDataImportProcess.getReceivedExchanges().get(0).getProperty("ImportTask", ImportTask.class);
        assertEquals("Import type not expected", ImportTask.ImportType.DELTA, importTask1.getImportType());
        assertEquals("Dataimport-properties file used", true, importTask1.isRetrievedFromFile());
        assertEquals("Number of documents processed not expected", 1, importTask1.getDocumentCounter());
        assertEquals("Import counter not expected", 92, importTask1.getTimerCounter().intValue());
        assertEquals("Processing state not expected", false, importTask1.isProcessing());
        assertEquals("From not expected", initialLocalDateTime, importTask1.getFrom());
        assertEquals("To not expected", localDateTime1, importTask1.getTo());
        assertEquals("Committed not expected", localDateTime1, importTask1.getCommitted());
        assertEquals("Modified must be after committed", true, importTask1.getModified().isAfter(localDateTime1));
        assertEquals("Unexpected totalCount", Integer.valueOf(1), importTask1.getTotalCount());
        assertEquals("ImportTask in zkNode not saved", importTask1.toString(), getImportTaskFromZkNode("/data-import-gettingstarted").toString());

        // test no change with insert
        mockDataImportProcess.reset();
        LocalDateTime localDateTime2 = truncateFrom(LocalDateTime.now().minus(1L, ChronoUnit.HOURS));
        executeSQLInsertStatementWith(UUID.randomUUID().toString(), localDateTime2);
        exchange1 =
                ExchangeBuilder
                        .anExchange(context)
                        .withProperty("CamelTimerCounter", 93)
                       .build();
        templateDataImportDelta.send(exchange1);
        mockDataImportProcess.expectedMinimumMessageCount(1);
        mockDataImportProcess.assertIsSatisfied();
        importTask1 = mockDataImportProcess.getReceivedExchanges().get(0).getProperty("ImportTask", ImportTask.class);
        assertEquals("Import type not expected", ImportTask.ImportType.DELTA, importTask1.getImportType());
        assertEquals("Dataimport-properties file used", true, importTask1.isRetrievedFromFile());
        assertEquals("Import counter not expected", 93, importTask1.getTimerCounter().intValue());
        assertEquals("Processing state not expected", false, importTask1.isProcessing());
        assertEquals("From not expected", localDateTime1, importTask1.getFrom());
        assertEquals("To not expected", localDateTime1, importTask1.getTo());
        assertEquals("Committed not expected", localDateTime1, importTask1.getCommitted());
        assertEquals("Modified must be after committed", true, importTask1.getModified().isAfter(localDateTime1));
        // 0 or -1 depending on id count active or not
        assertTrue("Unexpected totalCount", Integer.valueOf(0).compareTo(importTask1.getTotalCount()) >= 0);
        // not updated (from prev run)
        assertEquals("Number of documents processed not expected", 1, importTask1.getDocumentCounter());
        assertEquals("ImportTask in zkNode not saved", importTask1.toString(), getImportTaskFromZkNode("/data-import-gettingstarted").toString());

        // test no change without insert
        mockDataImportProcess.reset();
        exchange1 =
                ExchangeBuilder
                        .anExchange(context)
                        .withProperty("CamelTimerCounter", 94)
                        .build();
        templateDataImportDelta.send(exchange1);
        mockDataImportProcess.expectedMinimumMessageCount(1);
        mockDataImportProcess.assertIsSatisfied();
        importTask1 = mockDataImportProcess.getReceivedExchanges().get(0).getProperty("ImportTask", ImportTask.class);
        assertEquals("Import type not expected", ImportTask.ImportType.DELTA, importTask1.getImportType());
        assertEquals("Dataimport-properties file used", true, importTask1.isRetrievedFromFile());
        assertEquals("Import counter not expected", 94, importTask1.getTimerCounter().intValue());
        assertEquals("Processing state not expected", false, importTask1.isProcessing());
        assertEquals("From not expected", localDateTime1, importTask1.getFrom());
        assertEquals("To not expected", localDateTime1, importTask1.getTo());
        assertEquals("Committed not expected", localDateTime1, importTask1.getCommitted());
        assertEquals("Modified must be after committed", true, importTask1.getModified().isAfter(localDateTime1));
        // 0 or -1 depending on id count active or not
        assertTrue("Unexpected totalCount", Integer.valueOf(0).compareTo(importTask1.getTotalCount()) >= 0);
        // not updated (from prev run)
        assertEquals("Number of documents processed not expected", 1, importTask1.getDocumentCounter());
        assertEquals("ImportTask in zkNode not saved", importTask1.toString(), getImportTaskFromZkNode("/data-import-gettingstarted").toString());

    }

    private ImportTask getImportTaskFromZkNode(String s) throws Exception {
        return ImportTask.unmarshalFromJson(
                new String(
                        client.getData().forPath("/data-import-gettingstarted"),
                        Charset.defaultCharset()
                )
        );
    }

    @Test
    public void importDeleteByQueryTest() throws Exception {
        context.getRouteController().startRoute("data-import-properties-get");
        prepareImportTaskFromFile(new ImportTask());

        // process initial record for delta
        templateDataImportDelta.send(
                ExchangeBuilder
                    .anExchange(context)
                    .withProperty("CamelTimerCounter", 91)
                    .build()
        );
        mockDataImportProcess.expectedMinimumMessageCount(1);
        mockDataImportProcess.assertIsSatisfied();

        // reset after initial round
        mockDataImportProcess.reset();
        MockEndpoint mock1 = getMockEndpoint("mock:direct:solr-route-delete");
        mock1.reset();
        mock1.expectedMinimumMessageCount(1);

        // generate new exchange to test delete
        String testID = UUID.randomUUID().toString();
        LocalDateTime localDateTime1 = truncateFrom(LocalDateTime.now());
        executeSQLInsertStatementWith("MASTER", testID, localDateTime1, "123");
        executeSQLInsertStatementWith("MASTER2", testID, localDateTime1.plus(10L, ChronoUnit.SECONDS));

        // start import
        Exchange exchangetest =
                ExchangeBuilder
                        .anExchange(context)
                        .withProperty("CamelTimerCounter", 92)
                        .build();
        templateDataImportDelta.send(exchangetest);

        mock1.assertIsSatisfied();
        Exchange exchange1 = mock1.getReceivedExchanges().get(0);
        assertEquals("Unexpected solr operation", SolrConstants.OPERATION_DELETE_BY_QUERY, exchange1.getMessage().getHeader(SolrConstants.OPERATION));
        assertEquals("Unexpected delete query", String.format("id:(%s)", testID), exchange1.getMessage().getBody(String.class));
    }


    @Test
    public void ensureTablesDataInSequence() throws Exception {
        // TODO
    }

    @Test
    public void ensureTablesDataHasMastertableFirst() throws Exception {
        // TODO
    }

    @Test
    public void importByIDTest() throws Exception {
        ImportTaskController importTaskController = (ImportTaskController) context.getRegistry().lookupByName("handler");
        importTaskController.initializeWith(context);
        MockEndpoint mockDataImportProcessId = getMockEndpoint("mock:data-import-process-id");
        mockDataImportProcessId.expectedMinimumMessageCount(1);
        // invalid ID
        templateDataImportProcessId.sendBody("Dummyrecord");
        // test ID
        String testID = UUID.randomUUID().toString();
        executeSQLInsertStatementWith("MASTER", testID, initialLocalDateTime);
        executeSQLInsertStatementWith("MASTER2", testID, initialLocalDateTime.plus(10L, ChronoUnit.SECONDS));
        templateDataImportProcessId.sendBody(testID);

        mockDataImportProcessId.assertIsSatisfied();
        Exchange exchange1 = mockDataImportProcessId.getReceivedExchanges().get(0);
        SolrInputDocument solrInputDocument1 = exchange1.getMessage().getBody(SolrInputDocument.class);
        assertEquals("Unexpected ID field in SolrInputDocument", testID, solrInputDocument1.getFieldValue("id"));
        assertEquals("Unexpected date modified field in SolrInputDocument", initialLocalDateTime, getLocalDateTimeFrom(solrInputDocument1.getField("date_d")));
        assertEquals("Unexpected data in CONTRIBUTORS field in SolrInputDocument", "some data,more data,test", solrInputDocument1.getFieldValue("CONTRIBUTORS"));
        assertEquals("Unexpected loop index for table", 2, exchange1.getProperty("CamelLoopIndex"));
    }

    private LocalDateTime truncateFrom(LocalDateTime localDateTime) {
        return localDateTime.truncatedTo(ChronoUnit.MILLIS);
    }

    private LocalDateTime getLocalDateTimeFrom(SolrInputField date_d) {
        String val = date_d.getValue().toString();
        final int zz = val.indexOf('Z');
        if (zz == -1) {
            throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
                    "Invalid Date String:'" + val + '\'');
        }
        try {
            return DATE_TIME_FORMATTER.parse(val.substring(0, zz + 1), Instant::from).atZone(ZoneId.systemDefault()).toLocalDateTime();
        } catch (DateTimeParseException e) {
            throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
                    "Invalid Date in Date Math String:'" + val + '\'', e);
        }
    }

    @Test
    public void skipProcessingWhenBusyTest() throws Exception {
        ImportTaskController importTaskController = (ImportTaskController) context.getRegistry().lookupByName("handler");
        importTaskController.initializeWith(context);
        ImportTask importTask1 = new ImportTask();
        importTask1.setProcessing(true);
        importTask1.setCollection("mytest");
        importTaskController.setImportTask(importTask1);
        Exchange exchange1 =
                ExchangeBuilder
                        .anExchange(context)
                        .withProperty(Exchange.TIMER_COUNTER, 71)
                        .withHeader("isZkClientNotConnected", false)
                        .build();
        MockEndpoint mock = getMockEndpoint("mock:data-import-process-start");
        mock.expectedMinimumMessageCount(1);
        template.send("direct:data-import-process-start", exchange1);
        mock.assertIsSatisfied();
        List<Exchange> receivedExchanges = mock.getReceivedExchanges();
        assertEquals(1, receivedExchanges.size());
        Exchange receivedExchange = receivedExchanges.get(0);
        assertEquals(71, receivedExchange.getProperty(Exchange.TIMER_COUNTER));
        String expectedSkipProcessingMessage = "Data import request (DELTA (timerCounter=71)) is skipped in context 'solr-dataimport' as import batch is busy: {\n" +
                "  \"collection\" : \"mytest\",\n" +
                "  \"importType\" : \"FULL\",\n" +
                "  \"retrievedFromFile\" : false,\n" +
                "  \"processing\" : true,\n" +
                "  \"timerCounter\" : 0,\n" +
                "  \"offSet\" : 0,\n" +
                "  \"totalCount\" : 0,\n" +
                "  \"pageSize\" : 0,\n" +
                "  \"documentCounter\" : 0,\n" +
                "  \"from\" : \"2000-01-01T00:00:00\",\n" +
                "  \"to\" : \"2999-12-31T23:59:59\",\n" +
                "  \"committed\" : null,\n";
        assertEquals(expectedSkipProcessingMessage, receivedExchange.getMessage().getHeader("skipProcessingMessage", String.class).substring(0, expectedSkipProcessingMessage.length()));
    }

    @Test
    public void zkClientDisconnectedTest() throws InterruptedException {
        MockEndpoint mock = getMockEndpoint("mock:data-import-process-start");
        mock.expectedMinimumMessageCount(1);

        ImportTaskController importTaskController = (ImportTaskController) context.getRegistry().lookupByName("handler");
        ImportTask importTask1 = new ImportTask();
        importTask1.setImportType(ImportTask.ImportType.DELTA);
        importTask1.setTimerCounter(61);
        importTask1.setModified(importTask1.getModified().minus(5, ChronoUnit.MINUTES));
        prepareImportTaskFromFile(importTask1);

        // set import batch from file with modified late
        ImportTask importTask2 = new ImportTask();
        importTask2.setImportType(ImportTask.ImportType.DELTA);
        importTask2.setTimerCounter(65);
        importTaskController.setImportTask(importTask2);

        templateMonitorZkClient.sendBody("trigger");
        mock.assertIsSatisfied();
    }

}