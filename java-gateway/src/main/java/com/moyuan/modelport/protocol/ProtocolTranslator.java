package com.moyuan.modelport.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.moyuan.modelport.config.GatewayProperties.Protocol;
import com.moyuan.modelport.web.GatewayException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

@Component
public class ProtocolTranslator {
    private final ObjectMapper mapper;

    public ProtocolTranslator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ObjectNode request(Protocol client, Protocol upstream, ObjectNode source, String model) {
        if (client == upstream) {
            ObjectNode copy = source.deepCopy();
            copy.put("model", model);
            return copy;
        }
        return client == Protocol.OPENAI
                ? openAiRequestToAnthropic(source, model)
                : anthropicRequestToOpenAi(source, model);
    }

    public JsonNode response(Protocol upstream, Protocol client, JsonNode source, String exposedModel) {
        if (upstream == client) return source;
        return upstream == Protocol.ANTHROPIC
                ? anthropicResponseToOpenAi(source, exposedModel)
                : openAiResponseToAnthropic(source, exposedModel);
    }

    ObjectNode openAiRequestToAnthropic(ObjectNode source, String model) {
        ObjectNode target = mapper.createObjectNode();
        target.put("model", model);
        target.put("max_tokens", positiveInt(source, "max_tokens", 1024));
        copyIfPresent(source, target, "temperature", "top_p");
        if (source.has("stop")) target.set("stop_sequences", openAiStops(source.get("stop")));
        if (source.has("tools")) target.set("tools", openAiToolsToAnthropic(source.get("tools")));
        JsonNode toolChoice = openAiToolChoiceToAnthropic(source.get("tool_choice"));
        if (toolChoice != null) target.set("tool_choice", toolChoice);
        ArrayNode messages = target.putArray("messages");
        StringBuilder system = new StringBuilder();
        for (JsonNode message : requireMessages(source)) {
            String role = message.path("role").asText();
            if ("system".equals(role)) {
                if (!system.isEmpty()) system.append('\n');
                system.append(textContent(message.path("content")));
                continue;
            }
            if ("tool".equals(role)) {
                ObjectNode converted = messages.addObject();
                converted.put("role", "user");
                ObjectNode result = converted.putArray("content").addObject();
                result.put("type", "tool_result");
                result.put("tool_use_id", message.path("tool_call_id").asText());
                result.put("content", textContent(message.path("content")));
                continue;
            }
            ObjectNode converted = messages.addObject();
            converted.put("role", "assistant".equals(role) ? "assistant" : "user");
            converted.set("content", convertOpenAiContent(message));
        }
        if (!system.isEmpty()) target.put("system", system.toString());
        target.put("stream", source.path("stream").asBoolean(false));
        return target;
    }

    ObjectNode anthropicRequestToOpenAi(ObjectNode source, String model) {
        ObjectNode target = mapper.createObjectNode();
        target.put("model", model);
        target.put("max_tokens", positiveInt(source, "max_tokens", 1024));
        copyIfPresent(source, target, "temperature", "top_p");
        if (source.has("stop_sequences")) target.set("stop", source.get("stop_sequences"));
        if (source.has("tools")) target.set("tools", anthropicToolsToOpenAi(source.get("tools")));
        JsonNode toolChoice = anthropicToolChoiceToOpenAi(source.get("tool_choice"));
        if (toolChoice != null) target.set("tool_choice", toolChoice);
        ArrayNode messages = target.putArray("messages");
        if (source.has("system")) {
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", textContent(source.get("system")));
        }
        for (JsonNode message : requireMessages(source)) {
            ObjectNode converted = messages.addObject();
            converted.put("role", message.path("role").asText("user"));
            converted.set("content", convertAnthropicContent(message.path("content"), converted));
        }
        target.put("stream", source.path("stream").asBoolean(false));
        return target;
    }

    ObjectNode anthropicResponseToOpenAi(JsonNode source, String model) {
        ObjectNode target = mapper.createObjectNode();
        target.put("id", source.path("id").asText("chatcmpl-" + UUID.randomUUID()));
        target.put("object", "chat.completion");
        target.put("created", Instant.now().getEpochSecond());
        target.put("model", model);
        ObjectNode choice = target.putArray("choices").addObject();
        choice.put("index", 0);
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        StringBuilder text = new StringBuilder();
        ArrayNode toolCalls = mapper.createArrayNode();
        for (JsonNode block : source.path("content")) {
            if ("text".equals(block.path("type").asText())) text.append(block.path("text").asText());
            if ("tool_use".equals(block.path("type").asText())) {
                ObjectNode call = toolCalls.addObject();
                call.put("id", block.path("id").asText());
                call.put("type", "function");
                ObjectNode function = call.putObject("function");
                function.put("name", block.path("name").asText());
                function.put("arguments", stringify(block.path("input")));
            }
        }
        message.put("content", text.toString());
        if (!toolCalls.isEmpty()) message.set("tool_calls", toolCalls);
        choice.put("finish_reason", mapAnthropicStop(source.path("stop_reason").asText()));
        ObjectNode usage = target.putObject("usage");
        usage.put("prompt_tokens", source.path("usage").path("input_tokens").asInt());
        usage.put("completion_tokens", source.path("usage").path("output_tokens").asInt());
        usage.put("total_tokens", usage.path("prompt_tokens").asInt() + usage.path("completion_tokens").asInt());
        return target;
    }

    ObjectNode openAiResponseToAnthropic(JsonNode source, String model) {
        ObjectNode target = mapper.createObjectNode();
        target.put("id", source.path("id").asText("msg_" + UUID.randomUUID()));
        target.put("type", "message");
        target.put("role", "assistant");
        target.put("model", model);
        JsonNode choice = source.path("choices").path(0);
        JsonNode message = choice.path("message");
        ArrayNode content = target.putArray("content");
        String text = textContent(message.path("content"));
        if (!text.isEmpty()) {
            ObjectNode block = content.addObject();
            block.put("type", "text");
            block.put("text", text);
        }
        for (JsonNode toolCall : message.path("tool_calls")) {
            ObjectNode block = content.addObject();
            block.put("type", "tool_use");
            block.put("id", toolCall.path("id").asText());
            block.put("name", toolCall.path("function").path("name").asText());
            block.set("input", parseObject(toolCall.path("function").path("arguments").asText("{}")));
        }
        target.put("stop_reason", mapOpenAiStop(choice.path("finish_reason").asText()));
        target.putNull("stop_sequence");
        ObjectNode usage = target.putObject("usage");
        usage.put("input_tokens", source.path("usage").path("prompt_tokens").asInt());
        usage.put("output_tokens", source.path("usage").path("completion_tokens").asInt());
        return target;
    }

    private JsonNode convertOpenAiContent(JsonNode message) {
        ArrayNode blocks = mapper.createArrayNode();
        String text = textContent(message.path("content"));
        if (!text.isEmpty()) {
            ObjectNode block = blocks.addObject();
            block.put("type", "text");
            block.put("text", text);
        }
        for (JsonNode toolCall : message.path("tool_calls")) {
            ObjectNode block = blocks.addObject();
            block.put("type", "tool_use");
            block.put("id", toolCall.path("id").asText());
            block.put("name", toolCall.path("function").path("name").asText());
            block.set("input", parseObject(toolCall.path("function").path("arguments").asText("{}")));
        }
        return blocks;
    }

    private JsonNode convertAnthropicContent(JsonNode content, ObjectNode message) {
        if (content.isTextual()) return content;
        StringBuilder text = new StringBuilder();
        ArrayNode toolCalls = mapper.createArrayNode();
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText())) text.append(block.path("text").asText());
            if ("tool_use".equals(block.path("type").asText())) {
                ObjectNode call = toolCalls.addObject();
                call.put("id", block.path("id").asText());
                call.put("type", "function");
                ObjectNode function = call.putObject("function");
                function.put("name", block.path("name").asText());
                function.put("arguments", stringify(block.path("input")));
            }
        }
        if (!toolCalls.isEmpty()) message.set("tool_calls", toolCalls);
        return mapper.getNodeFactory().textNode(text.toString());
    }

    private ArrayNode openAiToolsToAnthropic(JsonNode tools) {
        if (!tools.isArray()) throw new GatewayException(HttpStatus.BAD_REQUEST, "tools must be an array");
        ArrayNode converted = mapper.createArrayNode();
        for (JsonNode tool : tools) {
            JsonNode function = tool.path("function");
            if (!"function".equals(tool.path("type").asText()) || function.path("name").asText().isBlank()) {
                throw new GatewayException(HttpStatus.BAD_REQUEST, "OpenAI tools must contain a named function");
            }
            ObjectNode target = converted.addObject();
            target.put("name", function.path("name").asText());
            if (function.has("description")) target.set("description", function.get("description"));
            target.set("input_schema", function.has("parameters")
                    ? function.get("parameters")
                    : mapper.createObjectNode().put("type", "object"));
        }
        return converted;
    }

    private ArrayNode anthropicToolsToOpenAi(JsonNode tools) {
        if (!tools.isArray()) throw new GatewayException(HttpStatus.BAD_REQUEST, "tools must be an array");
        ArrayNode converted = mapper.createArrayNode();
        for (JsonNode tool : tools) {
            if (tool.path("name").asText().isBlank()) {
                throw new GatewayException(HttpStatus.BAD_REQUEST, "Anthropic tools must contain a name");
            }
            ObjectNode target = converted.addObject();
            target.put("type", "function");
            ObjectNode function = target.putObject("function");
            function.put("name", tool.path("name").asText());
            if (tool.has("description")) function.set("description", tool.get("description"));
            function.set("parameters", tool.has("input_schema")
                    ? tool.get("input_schema")
                    : mapper.createObjectNode().put("type", "object"));
        }
        return converted;
    }

    private JsonNode openAiToolChoiceToAnthropic(JsonNode choice) {
        if (choice == null || choice.isNull() || choice.isMissingNode()) return null;
        if (choice.isTextual()) {
            return switch (choice.asText()) {
                case "auto" -> mapper.createObjectNode().put("type", "auto");
                case "required" -> mapper.createObjectNode().put("type", "any");
                case "none" -> null;
                default -> throw new GatewayException(HttpStatus.BAD_REQUEST, "unsupported OpenAI tool_choice");
            };
        }
        String name = choice.path("function").path("name").asText();
        if (name.isBlank()) throw new GatewayException(HttpStatus.BAD_REQUEST, "tool_choice function name is required");
        return mapper.createObjectNode().put("type", "tool").put("name", name);
    }

    private JsonNode anthropicToolChoiceToOpenAi(JsonNode choice) {
        if (choice == null || choice.isNull() || choice.isMissingNode()) return null;
        return switch (choice.path("type").asText()) {
            case "auto" -> mapper.getNodeFactory().textNode("auto");
            case "any" -> mapper.getNodeFactory().textNode("required");
            case "tool" -> {
                String name = choice.path("name").asText();
                if (name.isBlank()) throw new GatewayException(HttpStatus.BAD_REQUEST, "tool_choice name is required");
                ObjectNode target = mapper.createObjectNode();
                target.put("type", "function");
                target.putObject("function").put("name", name);
                yield target;
            }
            default -> throw new GatewayException(HttpStatus.BAD_REQUEST, "unsupported Anthropic tool_choice");
        };
    }

    private ArrayNode openAiStops(JsonNode stop) {
        if (stop.isArray()) return (ArrayNode) stop.deepCopy();
        if (stop.isTextual()) {
            ArrayNode values = mapper.createArrayNode();
            values.add(stop.asText());
            return values;
        }
        throw new GatewayException(HttpStatus.BAD_REQUEST, "stop must be a string or array");
    }

    private ArrayNode requireMessages(ObjectNode source) {
        JsonNode messages = source.path("messages");
        if (!messages.isArray() || messages.isEmpty()) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "messages must be a non-empty array");
        }
        return (ArrayNode) messages;
    }

    private int positiveInt(ObjectNode source, String field, int fallback) {
        int value = source.path(field).asInt(fallback);
        if (value <= 0) throw new GatewayException(HttpStatus.BAD_REQUEST, field + " must be greater than zero");
        return value;
    }

    private void copyIfPresent(ObjectNode source, ObjectNode target, String... fields) {
        for (String field : fields) if (source.has(field)) target.set(field, source.get(field));
    }

    private String textContent(JsonNode content) {
        if (content == null || content.isNull() || content.isMissingNode()) return "";
        if (content.isTextual()) return content.asText();
        StringBuilder text = new StringBuilder();
        if (content.isArray()) for (JsonNode block : content) {
            if (block.isTextual()) text.append(block.asText());
            else if ("text".equals(block.path("type").asText())) text.append(block.path("text").asText());
        }
        return text.toString();
    }

    private ObjectNode parseObject(String raw) {
        try {
            JsonNode parsed = mapper.readTree(raw);
            return parsed.isObject() ? (ObjectNode) parsed : mapper.createObjectNode();
        } catch (Exception ignored) {
            return mapper.createObjectNode();
        }
    }

    private String stringify(JsonNode value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception error) {
            return "{}";
        }
    }

    private String mapAnthropicStop(String reason) {
        return Map.of("end_turn", "stop", "max_tokens", "length", "tool_use", "tool_calls")
                .getOrDefault(reason, "stop");
    }

    private String mapOpenAiStop(String reason) {
        return Map.of("stop", "end_turn", "length", "max_tokens", "tool_calls", "tool_use")
                .getOrDefault(reason, "end_turn");
    }
}
