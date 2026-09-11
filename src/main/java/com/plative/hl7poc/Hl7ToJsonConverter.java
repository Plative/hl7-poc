package com.plative.hl7poc;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Composite;
import ca.uhn.hl7v2.model.Group;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.Primitive;
import ca.uhn.hl7v2.model.Segment;
import ca.uhn.hl7v2.model.Structure;
import ca.uhn.hl7v2.model.Type;
import ca.uhn.hl7v2.model.Varies;
import ca.uhn.hl7v2.parser.GenericParser;
import ca.uhn.hl7v2.util.Terser;
import ca.uhn.hl7v2.validation.impl.ValidationContextFactory;

/**
 * Converts an HL7 v2.x message (ER7 / pipe-delimited) into a JSON string using the
 * open source HAPI HL7v2 library (ca.uhn.hapi:hapi-base) for parsing.
 *
 * <p>Only hapi-base is on the classpath, so parsing goes through HAPI's
 * {@link GenericParser}: no version-specific structure jars (hapi-structures-v2x) are
 * required. HAPI represents every segment as a generic, untyped structure, which this
 * class walks generically (segment -&gt; field -&gt; repetition -&gt; component -&gt; subcomponent)
 * to build the JSON tree, so it works across HL7 versions and message types without
 * per-message-type mapping code.
 *
 * <p>Called from a Mule flow via the Java module, e.g.
 * {@code java:invoke-static class="com.plative.hl7poc.Hl7ToJsonConverter" method="convertToJson(String)"}.
 */
public final class Hl7ToJsonConverter {

    private Hl7ToJsonConverter() {
    }

    /**
     * Parses a raw HL7 v2.x message and returns its JSON representation.
     *
     * @param hl7Message the raw HL7 message text (segments separated by CR, LF, or CRLF)
     * @return a JSON string: {"messageType": "...", "segments": [ {"segment": "MSH", "fields": {...}}, ... ]}
     * @throws IllegalArgumentException if the input is null/blank
     * @throws IllegalStateException    if HAPI cannot parse the message
     */
    public static String convertToJson(String hl7Message) {
        if (hl7Message == null || hl7Message.trim().isEmpty()) {
            throw new IllegalArgumentException("HL7 message content is empty");
        }

        String normalized = normalizeSegmentTerminators(hl7Message);

        try {
            GenericParser parser = new GenericParser();
            // Lenient: this is a parsing POC, not a conformance validator. Malformed but
            // well-formed-enough messages should still come back as JSON.
            parser.setValidationContext(ValidationContextFactory.noValidation());

            Message message = parser.parse(normalized);

            StringBuilder json = new StringBuilder();
            json.append('{');
            json.append("\"messageType\":");
            appendJsonString(resolveMessageType(message), json);
            json.append(",\"segments\":");
            appendGroupAsJson(message, json);
            json.append('}');
            return json.toString();
        } catch (HL7Exception e) {
            throw new IllegalStateException("Failed to parse HL7 message: " + e.getMessage(), e);
        }
    }

    private static String normalizeSegmentTerminators(String raw) {
        return raw.replace("\r\n", "\r").replace("\n", "\r").trim();
    }

    private static String resolveMessageType(Message message) {
        try {
            Terser terser = new Terser(message);
            String messageCode = terser.get("MSH-9-1");
            String triggerEvent = terser.get("MSH-9-2");
            StringBuilder type = new StringBuilder();
            if (messageCode != null) {
                type.append(messageCode);
            }
            if (triggerEvent != null && !triggerEvent.isEmpty()) {
                if (type.length() > 0) {
                    type.append('_');
                }
                type.append(triggerEvent);
            }
            return type.length() > 0 ? type.toString() : "UNKNOWN";
        } catch (HL7Exception e) {
            return "UNKNOWN";
        }
    }

    /**
     * Renders every structure directly under a group as a JSON array, in wire order.
     * A generically-parsed message has no known sub-groups, so in practice this only
     * ever sees segments, but groups are handled too in case typed structures
     * (hapi-structures-v2x) are added to the classpath later.
     */
    private static void appendGroupAsJson(Group group, StringBuilder sb) throws HL7Exception {
        sb.append('[');
        boolean first = true;
        for (String name : group.getNames()) {
            for (Structure structure : group.getAll(name)) {
                if (structure == null) {
                    continue;
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                if (structure instanceof Segment) {
                    appendSegmentAsJson((Segment) structure, sb);
                } else if (structure instanceof Group) {
                    sb.append("{\"group\":");
                    appendJsonString(name, sb);
                    sb.append(",\"items\":");
                    appendGroupAsJson((Group) structure, sb);
                    sb.append('}');
                }
            }
        }
        sb.append(']');
    }

    /** Renders one segment as {"segment": "PID", "fields": {"PID-3": "...", "PID-5": [...] }}. */
    private static void appendSegmentAsJson(Segment segment, StringBuilder sb) throws HL7Exception {
        String segmentName = segment.getName();

        sb.append("{\"segment\":");
        appendJsonString(segmentName, sb);
        sb.append(",\"fields\":{");

        boolean firstField = true;
        int numFields = segment.numFields();
        for (int fieldNumber = 1; fieldNumber <= numFields; fieldNumber++) {
            Type[] repetitions = segment.getField(fieldNumber);
            if (repetitions == null || repetitions.length == 0) {
                continue;
            }

            String fieldJson = repetitions.length == 1
                    ? typeToJsonValue(repetitions[0])
                    : repetitionsToJsonArray(repetitions);

            if (fieldJson == null) {
                continue; // field present but empty (no components carried a value) - omit it
            }

            if (!firstField) {
                sb.append(',');
            }
            firstField = false;
            sb.append('"').append(segmentName).append('-').append(fieldNumber).append("\":");
            sb.append(fieldJson);
        }

        sb.append("}}");
    }

    private static String repetitionsToJsonArray(Type[] repetitions) throws HL7Exception {
        StringBuilder values = new StringBuilder();
        boolean any = false;
        for (Type repetition : repetitions) {
            String value = typeToJsonValue(repetition);
            if (value == null) {
                continue;
            }
            if (any) {
                values.append(',');
            }
            values.append(value);
            any = true;
        }
        if (!any) {
            return null;
        }
        return "[" + values + "]";
    }

    /**
     * Converts one HL7 {@link Type} (a field, repetition, or component) to a JSON fragment:
     * a quoted string for a primitive value, a JSON array for a composite's components,
     * or {@code null} if it carries no value (so the caller can omit it).
     */
    private static String typeToJsonValue(Type type) throws HL7Exception {
        if (type == null) {
            return null;
        }

        Type resolved = type;
        if (resolved instanceof Varies) {
            resolved = ((Varies) resolved).getData();
            if (resolved == null) {
                return null;
            }
        }

        if (resolved instanceof Composite) {
            Type[] components = ((Composite) resolved).getComponents();
            // Keep every component's position (an empty interior component, e.g. the blank
            // "^^" in "123456789^^^USSSA^SS", still means something), rendering an empty one
            // as "" rather than dropping it - dropping it would shift every component after
            // it and make JsonToHl7Converter reconstruct the wrong field. Only trailing
            // empty/absent components are dropped, matching how HL7 itself omits them.
            String[] parts = new String[components.length];
            int lastNonEmpty = -1;
            for (int i = 0; i < components.length; i++) {
                parts[i] = typeToJsonValue(components[i]);
                if (parts[i] != null) {
                    lastNonEmpty = i;
                }
            }
            if (lastNonEmpty < 0) {
                return null;
            }
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i <= lastNonEmpty; i++) {
                if (i > 0) {
                    json.append(',');
                }
                json.append(parts[i] == null ? "\"\"" : parts[i]);
            }
            json.append(']');
            return json.toString();
        }

        if (resolved instanceof Primitive) {
            String value = ((Primitive) resolved).getValue();
            if (value == null || value.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            appendJsonString(value, sb);
            return sb.toString();
        }

        return null;
    }

    private static void appendJsonString(String value, StringBuilder sb) {
        sb.append('"');
        if (value != null) {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"':
                        sb.append("\\\"");
                        break;
                    case '\\':
                        sb.append("\\\\");
                        break;
                    case '\b':
                        sb.append("\\b");
                        break;
                    case '\f':
                        sb.append("\\f");
                        break;
                    case '\n':
                        sb.append("\\n");
                        break;
                    case '\r':
                        sb.append("\\r");
                        break;
                    case '\t':
                        sb.append("\\t");
                        break;
                    default:
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                }
            }
        }
        sb.append('"');
    }
}
