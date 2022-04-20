package org.searchcompanion.dataimport;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.component.solr.SolrConstants;
import org.apache.camel.spi.PropertiesComponent;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.util.StrUtils;

import java.lang.reflect.InvocationTargetException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;

public class ImportTaskHandler {

    private String solrCollection = null;
    private List<String> tableNames = null;
    private final Map<String, String> tablesSql = new HashMap<>();
    private final Map<String, Map<String, String>> tablesFieldsMap = new HashMap<>();

    private SolrMapper solrMapper = null;
    private ImportTask importTask = null;
    private ImportTask lastImportTaskFromFile = null;
    private boolean isApplyAliasHandling = false;
    private boolean isApplySolrCommit = false;
    private int nrOfCollectionsToKeep = 0;
    private String idFieldName = null;

    public void initializeWith(CamelContext context) throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException {
        PropertiesComponent propertiesComponent = context.getPropertiesComponent();
        solrCollection = propertiesComponent.resolveProperty("solr.collection").get();
        isApplyAliasHandling = Boolean.parseBoolean(propertiesComponent.resolveProperty("solr.alias").orElse("false"));
        isApplySolrCommit = Boolean.parseBoolean(propertiesComponent.resolveProperty("solr.commit").orElse("true"));
        nrOfCollectionsToKeep = Integer.parseInt(propertiesComponent.resolveProperty("solr.collection.keep").orElse("0"));
        String tables = propertiesComponent.resolveProperty("sql.data.tables").get();
        List<String> entries = Arrays.asList(tables.split(","));
        List<String> newTableNames = new ArrayList<>();
        for (String entry : entries) {
            if (!entry.trim().isEmpty()) {
                newTableNames.add(entry.trim());
            }
        }
        tableNames = Collections.unmodifiableList(newTableNames);
        for (String s : tableNames) {
            String sqlString = propertiesComponent.resolveProperty(String.format("sql.data.%s", s)).get();
            tablesSql.put(s, sqlString);
            String fieldsMapString = propertiesComponent.resolveProperty(String.format("mapper.%s.fields", s)).orElse("*");
            tablesFieldsMap.put(s, getMapperFields(fieldsMapString));
        }
        String fieldNameWithDeleteFlag = propertiesComponent.resolveProperty("mapper.field.deleteflag").get();
        if (fieldNameWithDeleteFlag != null
                && !fieldNameWithDeleteFlag.isEmpty()) {
            tablesFieldsMap.put(SolrMapper.DELETEFLAG_TABLE_PLACEHOLDER, Collections.singletonMap(SolrMapper.DELETEFLAG_FIELD_PLACEHOLDER, fieldNameWithDeleteFlag));
        }
        if (tableNames.isEmpty()
                || isNull(tablesSql.get(tableNames.get(0)))
                || tablesSql.get(tableNames.get(0)).isEmpty()) {
            throw new IllegalArgumentException("SQL statements to retrieve data from database tables not configured.");
        }
        SolrMapper solrMapper = context.getRegistry().lookupByNameAndType("solrMapper", SolrMapper.class);
        this.solrMapper = (solrMapper == null) ?
                (SolrMapper) Class.forName(
                        propertiesComponent
                                .resolveProperty("solr.mapper.class")
                                .orElse("org.searchcompanion.dataimport.SolrMapperImpl")
                ).getConstructor().newInstance() :
                solrMapper;
        idFieldName = propertiesComponent
                .resolveProperty("solr.uniquekey")
                .orElse("id");
    }

    public SolrMapper getSolrMapper() {
        return solrMapper;
    }

    public Map<String, String> getMapperFields(String mapperFieldString) {
        Map<String, String> mapperFields = new LinkedHashMap<>();
        List<String> entries = Arrays.asList(mapperFieldString.split(","));
        for (String entry : entries) {
            if (entry.contains(":")) {
                String[] entries1 = entry.split(":");
                if (!entries1[0].trim().isEmpty() && !entries1[0].trim().isEmpty()) {
                    mapperFields.put(entries1[0].trim(), entries1[1].trim());
                }
            } else {
                if (!entry.trim().isEmpty()) {
                    mapperFields.put(entry.trim(), entry.trim());
                }
            }
        }
        return mapperFields;
    }

    public void isZkClientConnected(Exchange exchange) {
        // check if roundtrip via zookeeper is active
        // allow 60s for roundtrip
        boolean isZkClientNotConnected =
                lastImportTaskFromFile == null
                    || importTask == null
                || importTask.getModified().minus(60, ChronoUnit.SECONDS).isAfter(lastImportTaskFromFile.getModified());
        String logMessageTemplate =
                isZkClientNotConnected ?
                     "importtask received from zookeeper [modified=%s, timerCounter=%d] is out of sync with used importtask [modified=%s, timerCounter=%d]; restarting routes" :
                     "Importbatch received from zookeeper [modified=%s, timerCounter=%d] is in sync with used importtask [modified=%s, timerCounter=%d]";
        exchange.getMessage().setHeader("isZkClientNotConnected", isZkClientNotConnected);
        exchange.getMessage().setBody(
                String.format(
                        logMessageTemplate,
                        lastImportTaskFromFile == null ? null : lastImportTaskFromFile.getModified(),
                        lastImportTaskFromFile == null ? -1 : lastImportTaskFromFile.getTimerCounter(),
                        importTask == null ? null : importTask.getModified(),
                        importTask == null ? -1 : importTask.getTimerCounter()
                )
        );
    }

    public ImportTask getImportTask() {
        return importTask;
    }

    // test only
    public void setImportTask(ImportTask importTask) {
        this.importTask = importTask;
    }

    public void setImportTaskFromFile(Exchange exchange) {
        lastImportTaskFromFile = ImportTask.unmarshalFromJson(exchange.getMessage().getBody(String.class));
        if (importTask != null) {
            return;
        }
        importTask = lastImportTaskFromFile;
        importTask.setRetrievedFromFile(true);
        importTask.setProcessing(false); // ensure reset of processing flag
        exchange.setProperty("ImportTask", importTask);
    }

    public boolean isImportTaskProcessing(Exchange exchange) {
        boolean isAlreadyProcessing = importTask == null || importTask.isProcessing();
        if (isAlreadyProcessing) {
            String requestInfo;
            if (exchange.getMessage().getHeader("isZkClientNotConnected") != null
                    && exchange.getMessage().getHeader("isZkClientNotConnected", Boolean.class)) {
                requestInfo = "restart zookeeper client";
            } else if (exchange.getProperty("ImportType") != null &&
                    exchange.getProperty("ImportType", String.class).equals(ImportTask.ImportType.FULL.name())) {
                requestInfo = "FULL";
            } else {
                requestInfo = String.format("DELTA (timerCounter=%d)", exchange.getProperty(Exchange.TIMER_COUNTER));
            }
            exchange.getMessage().setHeader(
                    "skipProcessingMessage",
                    String.format(
                            "Data import request (%s) is skipped in context '%s' as import batch is busy: %s",
                            requestInfo,
                            exchange.getContext().getName(),
                            importTask
                    )
            );
        }
        return isAlreadyProcessing;
    }

    public boolean isImportTaskSetFromFile(Exchange exchange) {
        if (importTask == null) {
            exchange.setProperty("ImportTask", new ImportTask());
        }
        return importTask != null;
    }

    public void initializeImportTask(Exchange exchange) throws ClassNotFoundException, InvocationTargetException, NoSuchMethodException, InstantiationException, IllegalAccessException {
        if (tableNames == null) {
            initializeWith(exchange.getContext());
        }
        if (tableNames == null || tableNames.isEmpty()) {
            throw new IllegalArgumentException("SQL statements to retrieve data from database tables not configured.");
        }
        // determine importType
        ImportTask.ImportType importType =
                ImportTask.ImportType.FULL
                        .equals(exchange.getProperty("ImportType", ImportTask.ImportType.class)) ?
                        ImportTask.ImportType.FULL :
                        ImportTask.ImportType.DELTA;
        // always initialize time range for FULL import
        if (ImportTask.ImportType.FULL.equals(importType)) {
            importTask.setFrom(ImportTask.MIN_DATE_TIME);
            importTask.setTo(ImportTask.MAX_DATE_TIME);
            importTask.setCommitted(ImportTask.MIN_DATE_TIME);
        }
        // initialize new importTask
        importTask.setImportType(importType);
        importTask.setOffSet(0);
        importTask.setTotalCount(-1);
        if (exchange.getProperty("CamelTimerCounter") != null) {
            importTask.setTimerCounter(exchange.getProperty("CamelTimerCounter", Integer.class));
        } else {
            importTask.setTimerCounter(0);
        }
        // initialize target solr collection
        importTask.setCollection(
                isApplyAliasHandling && exchange.getProperty("newTargetSolrCollection") != null ?
                        exchange.getProperty("newTargetSolrCollection", String.class) :
                        (ImportTask.ImportType.FULL.equals(importType)
                                        && importTask.getCollection() != null) ?
                                importTask.getCollection() :
                                solrCollection
        );
        // store importTask on exchange
        exchange.setProperty("ImportTask", importTask);
    }

    public void setLastTimeStampFromDb(Exchange exchange) {
        if (exchange.getMessage().getBody() == null) {
            throw new RuntimeException(
                    String.format(
                            "Last timestamp field not set/found in DB (%s)",
                            exchange.getMessage().getHeaders().toString()
                    )
            );
        }
        LocalDateTime lastTimeStamp = exchange.getMessage().getBody(Timestamp.class).toLocalDateTime();
        ImportTask importTask = exchange.getProperty("ImportTask", ImportTask.class);
        LocalDateTime lastCommitted = importTask.getCommitted() == null ? ImportTask.MIN_DATE_TIME : importTask.getCommitted();
        // import cycle: from to be initialized for DELTA
        if (ImportTask.ImportType.DELTA.equals(importTask.getImportType())) {
            // first run: from set to latest record - 1ms
            if (ImportTask.MIN_DATE_TIME.equals(lastCommitted)) {
                importTask.setFrom(lastTimeStamp.minus(1, ChronoUnit.MILLIS));
            } else {
                importTask.setFrom(lastCommitted);
            }
        }
        // import cycle: to
        if (lastTimeStamp.isAfter(lastCommitted)) {
            importTask.setTo(lastTimeStamp);
            importTask.setProcessing(true);
        }
        exchange.getMessage().setHeader("TimeStampFrom", Timestamp.valueOf(importTask.getFrom()));
        exchange.getMessage().setHeader("TimeStampFromISO", importTask.getFrom().format(DateTimeFormatter.ISO_DATE_TIME));
        exchange.getMessage().setHeader("TimeStampTo", Timestamp.valueOf(importTask.getTo()));
        exchange.getMessage().setHeader("TimeStampToISO", importTask.getTo().format(DateTimeFormatter.ISO_DATE_TIME));
        setOffSetAndPageSizeOnHeader(exchange, importTask);
    }

    public void setStartPage(Exchange exchange, int totalCount, int pageSize) {
        ImportTask importTask = exchange.getProperty("ImportTask", ImportTask.class);
        importTask.setTotalCount(totalCount);
        importTask.setPageSize(pageSize);
        setOffSetAndPageSizeOnHeader(exchange, importTask);
    }

    public boolean isRunNextPage(Exchange exchange) {
        ImportTask importTask = exchange.getProperty("ImportTask", ImportTask.class);
        // 1st page - reset documentCounter
        if (importTask.getOffSet() == 0) {
            importTask.resetDocumentCounter();
        }
        // no paging - only 1st page returns true
        if (importTask.getPageSize() <= 0) {
            return (importTask.getOffSet() == 0);
        }
        // return true as long as setEOF not received
        return importTask.getOffSet() != -1;
    }

    public void setEOF(Exchange exchange) {
        ImportTask importTask = exchange.getProperty("ImportTask", ImportTask.class);
        importTask.setTotalCount(importTask.getDocumentCounter());
        importTask.setOffSet(-1);
    }

    public void incrementOffSet(Exchange exchange) {
        ImportTask importTask = exchange.getProperty("ImportTask", ImportTask.class);
        if (importTask.getPageSize() > 0) {
            importTask.setOffSet(importTask.getOffSet() + importTask.getPageSize());
            setOffSetAndPageSizeOnHeader(exchange, importTask);
        } else {
            setEOF(exchange);
        }
    }

    public boolean isValidSolrInput(Exchange exchange) {
        String solrOperation = exchange.getMessage().getHeader("SolrOperation", String.class);
        return (exchange.getMessage().getBody() != null
                    && (exchange.getMessage().getBody() instanceof String)
                            || (exchange.getMessage().getBody() instanceof SolrInputDocument))
                    && (solrOperation != null
                            && (solrOperation.equals(SolrConstants.OPERATION_DELETE_BY_QUERY)
                                    || solrOperation.equals(SolrConstants.OPERATION_INSERT_STREAMING)));
    }

    public boolean isRunSolrCommit(Exchange exchange) {
        ImportTask importTask = exchange.getProperty("ImportTask", ImportTask.class);
        return isApplySolrCommit && importTask.getDocumentCounter() > 0;
    }

    public void setCommitted(Exchange exchange) {
        ImportTask importTask = exchange.getProperty("ImportTask", ImportTask.class);
        importTask.setCommitted(importTask.getTo());
    }

    public void setStopProcessing(Exchange exchange) {
        ImportTask importTask = exchange.getProperty("ImportTask", ImportTask.class);
        importTask.setProcessing(false);
        importTask.setOffSet(0);
    }

    public void setOffSetAndPageSizeOnHeader(Exchange exchange, ImportTask importTask) {
        exchange.getMessage().setHeader("OffSet0", importTask.getOffSet());
        exchange.getMessage().setHeader("OffSet", importTask.getOffSet() + 1);
        exchange.getMessage().setHeader("PageSize", importTask.getPageSize());
    }

    public void setNextSql(Exchange exchange, Integer nextTableNr) {
        String nextSql = null;
        if (nextTableNr < tableNames.size()) {
            nextSql = tablesSql.get(tableNames.get(nextTableNr));
        }
        if (nextSql != null) {
            exchange.getMessage().setHeader("TableNr", nextTableNr);
            exchange.getMessage().setHeader("NextTable", true);
            exchange.getMessage().setHeader("CamelSqlQuery", nextSql);
        } else {
            exchange.getMessage().setHeader("TableNr", Integer.MAX_VALUE);
            exchange.getMessage().setHeader("NextTable", false);
            exchange.getMessage().removeHeader("CamelSqlQuery");
        }
    }

    public boolean hasNextTable(Exchange exchange) throws ClassNotFoundException, InvocationTargetException, NoSuchMethodException, InstantiationException, IllegalAccessException {
        ImportTableDataProcessor importTableDataProcessor = exchange.getMessage().getHeader("importTableDataProcessor", ImportTableDataProcessor.class);
        if (tableNames == null) {
            initializeWith(exchange.getContext());
        }
        if (importTableDataProcessor == null) {
            importTableDataProcessor = new ImportTableDataProcessor(tableNames);
            exchange.getMessage().setHeader("importTableDataProcessor", importTableDataProcessor);
        }
        exchange.getMessage().setBody(null);
        if (!importTableDataProcessor.hasNextTable()) {
            exchange.getMessage().removeHeader("CamelSqlQuery");
            return false;
        }
        exchange.getMessage().setHeader("CamelSqlQuery", tablesSql.get(importTableDataProcessor.getCurrentTableName()));
        return true;
    }

    public Object getProcessId(Exchange exchange) {
        if (exchange.getMessage().getBody() instanceof Map) {
            Map map = exchange.getMessage().getBody(Map.class);
            if (map.containsKey("ProcessId")) {
                return map.get("ProcessId");
            } else {
                return map.get(map.keySet().iterator().next());
            }
        } else {
            return exchange.getMessage().getBody();
        }
    }

    public void appendDataToTablesProcessor(Exchange exchange) {
        ImportTableDataProcessor importTableDataProcessor = exchange.getMessage().getHeader("importTableDataProcessor", ImportTableDataProcessor.class);
        List<Map<String, Object>> records = exchange.getMessage().getBody(List.class);
        if (records == null) {
            throw new IllegalStateException(
                    String.format(
                            "Error retrieving data records for document ID '%s' from table '%s'",
                            exchange.getMessage().getHeader("ProcessId"),
                            importTableDataProcessor.getCurrentTableName()
                    )
            );
        }
        importTableDataProcessor.appendToTablesMap(importTableDataProcessor.getCurrentTableName(), records);
        int nextTable = importTableDataProcessor.incrementTableIndex();
    }

    public void mapTablesToSolrDocument(Exchange exchange) {
        // Perform mapping
        ImportTableDataProcessor importTableDataProcessor = exchange.getMessage().getHeader("importTableDataProcessor", ImportTableDataProcessor.class);
        SolrMapperResult solrMapperResult = solrMapper.mapTablesDataToSolrMapperResult(exchange, importTableDataProcessor.getTablesMap(), tablesFieldsMap);
        exchange.getMessage().setHeader("SolrOperation", solrMapperResult.getSolrOperation());
        exchange.getMessage().setBody(solrMapperResult.getObject());
        // clean processor from exchange
        exchange.getMessage().removeHeader("importTableDataProcessor");
        // increment document counter on ImportTask
        if (exchange.getProperty("ImportTask") != null) {
            exchange.getProperty("ImportTask", ImportTask.class).incrementDocumentCounter();
        }
    }

    public boolean isUseAliasHandling(Exchange exchange) {
        ImportTask importTask = exchange.getProperty("ImportTask", ImportTask.class);
        return isApplyAliasHandling
                    && ImportTask.ImportType.FULL.name().equals(exchange.getProperty("ImportType", String.class))
                    && !importTask.getCollection().equals(solrCollection);
    }

    public void validateCollectionsToRemove(Exchange exchange) {
        Map<String, Object> listCollectionsResult = exchange.getMessage().getBody(Map.class);
        if (!isApplyAliasHandling
                || nrOfCollectionsToKeep == 0
                || listCollectionsResult == null) {
            exchange.getMessage().setBody(Collections.emptyList());
            return;
        }
        List<String> collectionsFound = (List<String>) listCollectionsResult.get("collections");
        if (collectionsFound == null || collectionsFound.isEmpty()) {
            exchange.getMessage().setBody(Collections.emptyList());
            return;
        }
        List<String> candidatesToRemove =
                collectionsFound
                        .stream()
                        .filter(s -> s.startsWith(solrCollection.concat("_")))
                        .sorted()
                        .collect(Collectors.toList());
        int nrCandidatesToRemove = candidatesToRemove.size() - nrOfCollectionsToKeep;
        List<String> collectionsToRemove =
                nrCandidatesToRemove > 0 ?
                        candidatesToRemove.subList(0, nrCandidatesToRemove)
                        : Collections.emptyList();
        exchange.getMessage().setBody(collectionsToRemove);
    }

    public void mapGroupedListToSolrDeleteQuery(Exchange exchange) {
        List<Object> idList = exchange.getMessage().getBody(List.class);
        String deleteQuery =
                String.format(
                        "%s:(%s)",
                        idFieldName,
                        StrUtils.join(idList,' ')
                );
        exchange.getMessage().setBody(deleteQuery);
    }

    public static List<String> getStringListFromProperty(String propertyValue) {
        return Arrays
                .stream(propertyValue.split(","))
                .map(String::trim)
                .filter(s -> s.length() > 0)
                .collect(Collectors.toList());
    }

}
