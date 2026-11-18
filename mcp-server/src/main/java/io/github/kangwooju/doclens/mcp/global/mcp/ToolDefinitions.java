package io.github.kangwooju.doclens.mcp.global.mcp;

import java.util.List;
import java.util.Map;

import io.github.kangwooju.doclens.mcp.global.response.ToolArguments;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

/**
 * 읽기 전용 Tool 정의와 공통 입력 schema 조각
 */
public final class ToolDefinitions {

    private ToolDefinitions() {
    }

    /** 닫힌 입력 schema를 가진 읽기 전용·멱등 Tool 정의 */
    public static Tool readOnly(
            String name,
            String title,
            String description,
            Map<String, Object> properties,
            List<String> required
    ) {

        return Tool
                .builder(
                        name,
                        Map
                                .of(
                                        "type",
                                        "object",
                                        "additionalProperties",
                                        false,
                                        "properties",
                                        properties,
                                        "required",
                                        required
                                )
                )
                .title(title)
                .description(description)
                .annotations(ToolAnnotations
                        .builder()
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .build();
    }

    /** maxContextTokens 입력 schema */
    public static Map<String, Object> budgetProperty() {

        return integerProperty(
                ToolArguments.MIN_BUDGET,
                ToolArguments.MAX_BUDGET,
                ToolArguments.DEFAULT_BUDGET
        );
    }

    /** limit 입력 schema */
    public static Map<String, Object> limitProperty() {

        return integerProperty(
                ToolArguments.MIN_LIMIT,
                ToolArguments.MAX_LIMIT,
                ToolArguments.DEFAULT_LIMIT
        );
    }

    /** 범위와 기본값이 있는 정수 입력 schema */
    public static Map<String, Object> integerProperty(
            int minimum,
            int maximum,
            int defaultValue
    ) {

        return Map
                .of(
                        "type",
                        "integer",
                        "minimum",
                        minimum,
                        "maximum",
                        maximum,
                        "default",
                        defaultValue
                );
    }

    /** 길이 제한 문자열 입력 schema */
    public static Map<String, Object> textProperty(int maxLength) {

        return Map
                .of(
                        "type",
                        "string",
                        "minLength",
                        1,
                        "maxLength",
                        maxLength
                );
    }
}
