package com.moyuan.modelport.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.moyuan.modelport.config.GatewayProperties.Protocol;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProtocolTranslatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProtocolTranslator translator = new ProtocolTranslator(mapper);

    @Test
    void translatesAnthropicToolUseIntoOpenAiToolCallWithoutDroppingArguments() throws Exception {
        ObjectNode response = (ObjectNode) mapper.readTree("""
                {
                  "id":"msg_1",
                  "content":[{"type":"tool_use","id":"tool_1","name":"quote","input":{"symbol":"600000"}}],
                  "stop_reason":"tool_use",
                  "usage":{"input_tokens":10,"output_tokens":6}
                }
                """);

        var converted = translator.response(Protocol.ANTHROPIC, Protocol.OPENAI, response, "finance-agent");

        assertThat(converted.at("/choices/0/message/tool_calls/0/function/name").asText()).isEqualTo("quote");
        assertThat(converted.at("/choices/0/message/tool_calls/0/function/arguments").asText())
                .contains("600000");
        assertThat(converted.at("/choices/0/finish_reason").asText()).isEqualTo("tool_calls");
        assertThat(converted.at("/usage/total_tokens").asInt()).isEqualTo(16);
    }

    @Test
    void translatesToolSchemasAndForcedToolChoiceInBothDirections() throws Exception {
        ObjectNode openAi = (ObjectNode) mapper.readTree("""
                {
                  "model":"alias",
                  "messages":[{"role":"user","content":"quote it"}],
                  "tools":[{
                    "type":"function",
                    "function":{
                      "name":"quote",
                      "description":"Get a quote",
                      "parameters":{
                        "type":"object",
                        "properties":{"symbol":{"type":"string"}},
                        "required":["symbol"]
                      }
                    }
                  }],
                  "tool_choice":{"type":"function","function":{"name":"quote"}}
                }
                """);

        var anthropic = translator.request(Protocol.OPENAI, Protocol.ANTHROPIC, openAi, "upstream");
        assertThat(anthropic.at("/tools/0/name").asText()).isEqualTo("quote");
        assertThat(anthropic.at("/tools/0/input_schema/properties/symbol/type").asText()).isEqualTo("string");
        assertThat(anthropic.at("/tool_choice/type").asText()).isEqualTo("tool");
        assertThat(anthropic.at("/tool_choice/name").asText()).isEqualTo("quote");

        var roundTrip = translator.request(Protocol.ANTHROPIC, Protocol.OPENAI, anthropic, "upstream");
        assertThat(roundTrip.at("/tools/0/function/parameters/required/0").asText()).isEqualTo("symbol");
        assertThat(roundTrip.at("/tool_choice/function/name").asText()).isEqualTo("quote");
    }
}
