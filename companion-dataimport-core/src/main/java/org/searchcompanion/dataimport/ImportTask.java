package org.searchcompanion.dataimport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.util.StdDateFormat;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

public class ImportTask {

    public final static LocalDateTime MIN_DATE_TIME = LocalDateTime.of(2000, 01, 01, 00, 00, 00);
    public final static LocalDateTime MAX_DATE_TIME = LocalDateTime.of(2999, 12, 31, 23, 59, 59);
    private final static ObjectMapper OBJECT_MAPPER;

    private String collection = null;
    private ImportType importType = ImportType.FULL;
    private boolean retrievedFromFile = false;
    private boolean processing = false;
    private Integer timerCounter = 0;
    private Integer offSet = 0;
    private Integer totalCount = 0;
    private Integer pageSize = 0;
    private AtomicInteger documentCounter = new AtomicInteger(0);
    private LocalDateTime from = MIN_DATE_TIME;
    private LocalDateTime to = MAX_DATE_TIME;
    private LocalDateTime committed = null;
    private LocalDateTime modified = LocalDateTime.now();

    static {
        OBJECT_MAPPER = new ObjectMapper();
        OBJECT_MAPPER.registerModule(new JavaTimeModule());
        OBJECT_MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        OBJECT_MAPPER.setDateFormat(new StdDateFormat().withColonInTimeZone(true));
        OBJECT_MAPPER.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public String getCollection() {
        return collection;
    }

    public void setCollection(String collection) {
        this.collection = collection;
    }

    public ImportType getImportType() {
        return importType;
    }

    public void setImportType(ImportType importType) {
        if (ImportType.FULL.equals(importType)) {
            from = MIN_DATE_TIME;
            to = MAX_DATE_TIME;
        }
        this.importType = importType;
        setModified();
    }

    public boolean isRetrievedFromFile() {
        return retrievedFromFile;
    }

    public void setRetrievedFromFile(boolean retrievedFromFile) {
        this.retrievedFromFile = retrievedFromFile;
    }

    public boolean isProcessing() {
        return processing;
    }

    public void setProcessing(boolean processing) {
        this.processing = processing;
    }

    public Integer getTimerCounter() {
        return timerCounter;
    }

    public void setTimerCounter(Integer timerCounter) {
        this.timerCounter = timerCounter;
    }

    public Integer getOffSet() {
        return offSet;
    }

    public void setOffSet(Integer offSet) {
        this.offSet = offSet;
    }

    public Integer getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(Integer totalCount) {
        this.totalCount = totalCount;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }

    public void incrementDocumentCounter() {
        documentCounter.incrementAndGet();
    }

    public void resetDocumentCounter() {
        documentCounter.set(0);
    }

    public int getDocumentCounter() {
        return documentCounter.intValue();
    }

    public LocalDateTime getFrom() {
        return from;
    }

    public void setFrom(LocalDateTime from) {
        this.from = from;
        setModified();
    }

    public LocalDateTime getTo() {
        return to;
    }

    public void setTo(LocalDateTime to) {
        this.to = to;
        setModified();
    }

    public LocalDateTime getCommitted() {
        return committed;
    }

    public void setCommitted(LocalDateTime committed) {
        this.committed = committed;
        setModified();
    }

    public LocalDateTime getModified() {
        return modified;
    }

    public void setModified() {
        setModified(LocalDateTime.now());
    }

    public void setModified(LocalDateTime localDateTime) {
        modified = localDateTime;
    }

    static public ImportTask unmarshalFromJson(String body) {
        ImportTask importTask;
        try {
            importTask = OBJECT_MAPPER.readValue(body, ImportTask.class);
            importTask.setProcessing(false);
            importTask.setRetrievedFromFile(true);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return new ImportTask();
        }
        return importTask;
    }

    public String toString() {
        try {
            return OBJECT_MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            return String.format("{ error parsing object '%s' }", this);
        }
    }

    public enum ImportType {
        FULL, DELTA
    }

}
