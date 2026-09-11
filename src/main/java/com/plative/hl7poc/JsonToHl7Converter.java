package com.plative.hl7poc;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.GenericParser;
import ca.uhn.hl7v2.validation.impl.ValidationContextFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Converts JSON (in the shape produced by {@link Hl7ToJsonConverter}) back into an HL7
 * v2.x ER7 (pipe-delimited) message.
 *
 * <p>The JSON is walked field-by-field to build a raw ER7 string, which is then parsed
 * and re-encoded with the open source HAPI HL7v2 library ({@code ca.uhn.hapi:hapi-base}):
 * that round trip through {@link GenericParser} validates segment/field structure and
 * lets HAPI's own encoder produce the canonical output, rather than trusting hand-rolled
 * string concatenation as the final answer.
 *
 * <p>Expected JSON shape:
 * <pre>{@code
 * {
 *   "segments": [
 *     { "segment": "MSH", "fields": { "MSH-9": ["ADT", "A01"], "MSH-10": "MSG00001", ... } },
 *     { "segment": "PID", "fields": { "PID-5": ["JONES", "WILLIAM"], ... } }
 *   ]
 * }
 * }</pre>
 *
 * <p><b>Known limitation:</b> a field value that is a flat JSON array of strings (e.g.
 * {@code ["JONES", "WILLIAM"]}) is interpreted as one composite field's components, not
 * as repetitions of a primitive value, because the two are structurally identical in
 * this JSON schema. This matches how {@link Hl7ToJsonConverter} emits the far more common
 * case (composite fields), so a message that goes HL7 -&gt; JSON -&gt; HL7 round-trips
 * correctly except for the rare field that repeats a plain (non-composite) value. A
 * repeating field is only unambiguous when its repetitions are themselves arrays, e.g.
 * {@code [["PATID1", "MR"], ["987654", "SS"]]} for a repeating composite field.
 *
 * <p>Called from a Mule flow via the Java module, e.g.
 * {@code java:invoke-static class="com.plative.hl7poc.JsonToHl7Converter" method="convertToHl7(String)"}.
 */
public final class JsonToHl7Converter {

    private static final char FIELD_SEPARATOR = '|';
    private static final char COMPONENT_SEPARATOR = '^';
    private static final char REPETITION_SEPARATOR = '~';
    private static final char SUBCOMPONENT_SEPARATOR = '&';
    private static final char ESCAPE_CHARACTER = '\\';

    private JsonToHl7Converter() {
    }

    /**
     * Parses JSON in the {@link Hl7ToJsonConverter} shape and returns the equivalent
     * HL7 ER7 message text (segments terminated with CR).
     *
     * @param json the JSON document to convert
     * @return the HL7 v2.x message text
     * @throws IllegalArgumentException if the JSON is null/blank, malformed, or missing a "segments" array
     * @throws IllegalStateException    if HAPI cannot parse the ER7 text built from the JSON
     */
    public static String convertToHl7(String json) {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("JSON content is empty");
        }

        Object root = new JsonReader(json).readValue();
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException("Expected a JSON object with a \"segments\" array");
        }

        Object segmentsValue = ((Map<?, ?>) root).get("segments");
        if (!(segmentsValue instanceof List)) {
            throw new IllegalArgumentException("JSON is missing a \"segments\" array");
        }

        String rawEr7 = buildEr7((List<?>) segmentsValue);

        try {
            GenericParser parser = new GenericParser();
            parser.setValidationContext(ValidationContextFactory.noValidation());
            Message message = parser.parse(rawEr7);
            return parser.encode(message);
        } catch (HL7Exception e) {
            throw new IllegalStateException("Failed to build a valid HL7 message from the JSON: " + e.getMessage(), e);
        }
    }

    private static String buildEr7(List<?> segments) {
        StringBuilder hl7 = new StringBuilder();
        for (Object segmentEntry : segments) {
            if (!(segmentEntry instanceof Map)) {
                continue;
            }
            Map<?, ?> segmentMap = (Map<?, ?>) segmentEntry;
            Object segmentName = segmentMap.get("segment");
            if (segmentName == null) {
                continue;
            }

            SortedMap<Integer, Object> fieldsByNumber = fieldsByNumber(segmentMap.get("fields"));
            String name = String.valueOf(segmentName);

            hl7.append(name);
            int maxFieldNumber = fieldsByNumber.isEmpty() ? 0 : fieldsByNumber.lastKey();

            int firstOrdinaryField = 1;
            if (isHeaderSegment(name)) {
                // MSH-1 (and BHS-1/FHS-1) *is* the field separator character, and MSH-2 the
                // encoding characters - both are written literally, with no delimiter of
                // their own, rather than as pipe-prefixed/escaped fields like everything else.
                hl7.append(literalFieldValue(fieldsByNumber.get(1), String.valueOf(FIELD_SEPARATOR)));
                hl7.append(literalFieldValue(fieldsByNumber.get(2), "" + COMPONENT_SEPARATOR + REPETITION_SEPARATOR + ESCAPE_CHARACTER + SUBCOMPONENT_SEPARATOR));
                firstOrdinaryField = 3;
            }

            for (int fieldNumber = firstOrdinaryField; fieldNumber <= maxFieldNumber; fieldNumber++) {
                hl7.append(FIELD_SEPARATOR);
                Object value = fieldsByNumber.get(fieldNumber);
                if (value != null) {
                    hl7.append(fieldToEr7(value));
                }
            }
            hl7.append('\r');
        }
        return hl7.toString();
    }

    private static boolean isHeaderSegment(String segmentName) {
        return "MSH".equals(segmentName) || "BHS".equals(segmentName) || "FHS".equals(segmentName);
    }

    /** Returns a header segment's field 1/2 value as a literal string (unescaped), falling back if absent. */
    private static String literalFieldValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        return value instanceof String ? (String) value : String.valueOf(value);
    }

    /** Reads a segment's "fields" map (keys like "PID-5") into field number -&gt; JSON value, in order. */
    private static SortedMap<Integer, Object> fieldsByNumber(Object fieldsValue) {
        SortedMap<Integer, Object> result = new TreeMap<>();
        if (!(fieldsValue instanceof Map)) {
            return result;
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) fieldsValue).entrySet()) {
            String key = String.valueOf(entry.getKey());
            int dash = key.lastIndexOf('-');
            if (dash < 0 || dash == key.length() - 1) {
                continue;
            }
            try {
                int fieldNumber = Integer.parseInt(key.substring(dash + 1));
                result.put(fieldNumber, entry.getValue());
            } catch (NumberFormatException e) {
                // key isn't in "SEGMENT-N" form; skip it rather than fail the whole message
            }
        }
        return result;
    }

    private static String fieldToEr7(Object value) {
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            if (containsNestedArray(list)) {
                // Element(s) are arrays -> this field repeats; each element is one repetition.
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        sb.append(REPETITION_SEPARATOR);
                    }
                    Object repetition = list.get(i);
                    sb.append(repetition instanceof List
                            ? joinEscaped((List<?>) repetition, COMPONENT_SEPARATOR)
                            : escape(String.valueOf(repetition)));
                }
                return sb.toString();
            }
            // Flat array -> one repetition, composite field: join components with "^".
            return joinEscaped(list, COMPONENT_SEPARATOR);
        }
        return escape(String.valueOf(value));
    }

    private static boolean containsNestedArray(List<?> list) {
        for (Object item : list) {
            if (item instanceof List) {
                return true;
            }
        }
        return false;
    }

    /** Joins a list of components (each a String, or a List of subcomponents) with the given separator. */
    private static String joinEscaped(List<?> parts, char separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(separator);
            }
            Object part = parts.get(i);
            if (part instanceof List) {
                sb.append(joinEscaped((List<?>) part, SUBCOMPONENT_SEPARATOR));
            } else if (part != null) {
                sb.append(escape(String.valueOf(part)));
            }
        }
        return sb.toString();
    }

    /** Escapes HL7 delimiter characters that appear inside a leaf value, per the standard HL7 escape rules. */
    private static String escape(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case FIELD_SEPARATOR:
                    sb.append("\\F\\");
                    break;
                case COMPONENT_SEPARATOR:
                    sb.append("\\S\\");
                    break;
                case REPETITION_SEPARATOR:
                    sb.append("\\R\\");
                    break;
                case SUBCOMPONENT_SEPARATOR:
                    sb.append("\\T\\");
                    break;
                case ESCAPE_CHARACTER:
                    sb.append("\\E\\");
                    break;
                case '\r':
                case '\n':
                    sb.append(' ');
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Minimal recursive-descent JSON reader: objects, arrays, strings, numbers, booleans, null. */
    private static final class JsonReader {
        private final String source;
        private int pos;

        JsonReader(String source) {
            this.source = source;
            this.pos = 0;
        }

        Object readValue() {
            skipWhitespace();
            char c = peek();
            if (c == '{') {
                return readObject();
            }
            if (c == '[') {
                return readArray();
            }
            if (c == '"') {
                return readString();
            }
            if (c == 't' || c == 'f') {
                return readBoolean();
            }
            if (c == 'n') {
                expectLiteral("null");
                return null;
            }
            return readNumber();
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            expectChar('{');
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expectChar(':');
                Object value = readValue();
                map.put(key, value);
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    break;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("Malformed JSON near position " + pos);
                }
            }
            return map;
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            expectChar('[');
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    break;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("Malformed JSON near position " + pos);
                }
            }
            return list;
        }

        private String readString() {
            expectChar('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    break;
                }
                if (c == '\\') {
                    char escaped = next();
                    switch (escaped) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            String hex = source.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                            break;
                        default:
                            sb.append(escaped);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Object readNumber() {
            int start = pos;
            while (pos < source.length() && "-+.eE0123456789".indexOf(source.charAt(pos)) >= 0) {
                pos++;
            }
            String number = source.substring(start, pos);
            if (number.isEmpty()) {
                throw new IllegalArgumentException("Malformed JSON near position " + pos);
            }
            if (number.contains(".") || number.contains("e") || number.contains("E")) {
                return Double.parseDouble(number);
            }
            return Long.parseLong(number);
        }

        private Boolean readBoolean() {
            if (source.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (source.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Malformed JSON near position " + pos);
        }

        private void expectLiteral(String literal) {
            if (!source.startsWith(literal, pos)) {
                throw new IllegalArgumentException("Malformed JSON near position " + pos);
            }
            pos += literal.length();
        }

        private void expectChar(char expected) {
            skipWhitespace();
            char c = next();
            if (c != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' near position " + (pos - 1));
            }
        }

        private char peek() {
            skipWhitespace();
            if (pos >= source.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            return source.charAt(pos);
        }

        private char next() {
            if (pos >= source.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            return source.charAt(pos++);
        }

        private void skipWhitespace() {
            while (pos < source.length() && Character.isWhitespace(source.charAt(pos))) {
                pos++;
            }
        }
    }
}
