package org.searchcompanion.dataimport;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.solr.common.SolrInputDocument;

import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;
import java.util.Map;

public abstract class SolrMapperBase implements SolrMapper {

	private static final String UNDERSCORE = "_";
    private static final String EMPTY_STRING = "";

    public static final DateTimeFormatter DATE_TIME_FORMATTER = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();;

    public boolean isValueForDelete(Object deleteFieldValue) {
        if (deleteFieldValue == null) {
            return false;
        }
        boolean result;
        if (deleteFieldValue instanceof Boolean) {
            result = (Boolean) deleteFieldValue;
        } else if (deleteFieldValue instanceof Integer) {
            result = Integer.valueOf((Integer) deleteFieldValue) == 1;
        } else {
            result = Boolean.parseBoolean((String) deleteFieldValue);
        }
        return result;
    }

    public abstract SolrMapperResult mapTablesDataToSolrMapperResult(Exchange exchange, Map<String, List<Map<String, Object>>> tablesData, Map<String, Map<String, String>> tablesFieldsMap);

    public static Object retrieve(Map<String, Object> record, String fieldName) {
        return record.get(fieldName.toUpperCase());
    }

    public static void addField(SolrInputDocument doc, String field, String extension, Object value) {
        String lvalue = parseObject(value);
        if (lvalue == null || lvalue.isEmpty()) {
            return;
        }
        String lfield = (extension == null ? field : combineStrings(field, extension));
        doc.addField(lfield, lvalue);
    }

    public static void addField(SolrInputDocument doc, String field, Object value) {
        addField(doc, field, null, value);
    }

    public static boolean parseFlag(Object object) {
        return parseObject(object).equalsIgnoreCase("Y");
    }

    public static String parseObject(Object object) {
        return parseObject(object, EMPTY_STRING);
    }

    public static String parseObject(Object object, String defaultValue) {
        if (object == null) {
            return defaultValue;
        }
        if (object instanceof Timestamp) {
            return ((Timestamp) object).toLocalDateTime().atZone(ZoneId.systemDefault()).format(DATE_TIME_FORMATTER);
        }
        return String.valueOf(object).trim();
    }

    public static String combineStrings(String string1, String string2) {
        boolean st1 = (string1 == null || string1.isEmpty());
        boolean st2 = (string2 == null || string2.isEmpty());
        if (st1 && st2)
            return null;
        if (st1)
            return string2;
        if (st2)
            return string1;
        return string1.concat(UNDERSCORE).concat(string2);
    }

}
