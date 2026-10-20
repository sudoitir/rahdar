package io.github.sudoitir.rahdar.support;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A Jolokia agent that answers like one Artemis broker, whatever order the broker SDK asks in: searches
 * find the broker and the queues it holds, reads return the attributes set here (or plain defaults), and
 * an operation returns what it was given. For tests about what Rahdar does with the answers, not about
 * the exact requests; {@link #operations()} says which operations ran.
 */
public final class FakeArtemis {

    public static final String BROKER = "org.apache.activemq.artemis:broker=\"primary\"";

    private static final Pattern QUEUE = Pattern.compile("queue=\"?([^\",]+)\"?");

    private final JsonMapper json = new JsonMapper();
    private final Map<String, String> queues = new HashMap<>();
    private final Map<String, Object> attributes = new HashMap<>();
    private final Map<String, Object> results = new HashMap<>();
    private final List<String> operations = new CopyOnWriteArrayList<>();

    /** A queue on the broker, bound to an address of the same name. */
    public FakeArtemis queue(String name) {
        queues.put(name, name);
        return this;
    }

    /** What a read of this attribute of any MBean returns. */
    public FakeArtemis attribute(String name, Object value) {
        attributes.put(name, value);
        return this;
    }

    /** What this operation returns, named without its signature ({@code removeMessages}). */
    public FakeArtemis operation(String name, Object value) {
        results.put(name, value);
        return this;
    }

    /** The operations that ran, by name, in order. */
    public List<String> operations() {
        return List.copyOf(operations);
    }

    /** An agent that answers as this broker. */
    public FixtureJolokia agent() {
        return new FixtureJolokia().otherwise(this::answer);
    }

    private String answer(String body) {
        JsonNode request = json.readTree(body);
        if (request.isArray()) {
            ArrayNode out = json.createArrayNode();
            request.forEach(r -> out.add(answerOne(r)));
            return out.toString();
        }
        return answerOne(request).toString();
    }

    private ObjectNode answerOne(JsonNode request) {
        ObjectNode response = json.createObjectNode();
        response.set("request", request);
        response.put("status", 200);
        String mbean = request.path("mbean").asString("");
        switch (request.path("type").asString("")) {
            case "search" -> {
                ArrayNode found = json.createArrayNode();
                Matcher queue = QUEUE.matcher(mbean);
                if (queue.find()) {
                    String name = queue.group(1);
                    if (queues.containsKey(name)) {
                        found.add(queueMBean(name));
                    }
                } else {
                    found.add(BROKER);
                }
                response.set("value", found);
            }
            case "read" -> {
                JsonNode attribute = request.path("attribute");
                if (attribute.isArray()) {
                    ObjectNode values = json.createObjectNode();
                    attribute.forEach(a -> values.set(a.asString(), json.valueToTree(read(a.asString(), mbean))));
                    response.set("value", values);
                } else if (attribute.isString()) {
                    response.set("value", json.valueToTree(read(attribute.asString(), mbean)));
                } else {
                    response.set("value", json.createObjectNode());
                }
            }
            case "exec" -> {
                String operation = request.path("operation").asString("");
                String name = operation.contains("(") ? operation.substring(0, operation.indexOf('(')) : operation;
                operations.add(name);
                response.set("value", json.valueToTree(results.get(name)));
            }
            default -> response.set("value", json.nullNode());
        }
        return response;
    }

    private Object read(String attribute, String mbean) {
        if (attributes.containsKey(attribute)) {
            return attributes.get(attribute);
        }
        Matcher queue = QUEUE.matcher(mbean);
        String name = queue.find() ? queue.group(1) : "";
        return switch (attribute) {
            case "ID" -> 1;
            case "Name", "Address" -> queues.getOrDefault(name, name);
            case "RoutingType" -> "ANYCAST";
            case "Version" -> "2.44.0";
            case "Paused", "Temporary", "Exclusive" -> false;
            case "Durable" -> true;
            default -> 0;
        };
    }

    private String queueMBean(String queue) {
        return "org.apache.activemq.artemis:address=\"" + queues.get(queue) + "\",broker=\"primary\","
                + "component=addresses,queue=\"" + queue + "\",routing-type=\"anycast\",subcomponent=queues";
    }
}
