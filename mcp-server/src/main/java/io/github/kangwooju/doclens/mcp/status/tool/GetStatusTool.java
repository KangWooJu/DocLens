package io.github.kangwooju.doclens.mcp.status.tool;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.mcp.global.mcp.McpToolEndpoint;
import io.github.kangwooju.doclens.mcp.global.response.ToolArguments;
import io.github.kangwooju.doclens.mcp.global.response.ToolResponses;
import io.github.kangwooju.doclens.mcp.status.dto.StatusResult;
import io.github.kangwooju.doclens.mcp.status.facade.StatusFacade;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import lombok.RequiredArgsConstructor;

/**
 * get_status Tool 진입부: 준비 상태·프로그램·프로토콜 버전 조회
 */
@Component
@RequiredArgsConstructor
public class GetStatusTool implements McpToolEndpoint {

    /** Tool 이름 */
    public static final String NAME = "get_status";

    private static final Set<String> ARGUMENTS = Set
            .of(ToolArguments.MAX_CONTEXT_TOKENS);

    private final StatusFacade statusFacade;

    private final ToolResponses toolResponses;

    @Override
    public SyncToolSpecification specification() {

        return SyncToolSpecification
                .builder()
                .tool(definition())
                .callHandler((exchange, request) -> toolResponses
                        .guard(
                                NAME,
                                () -> call(request
                                        .arguments())
                        ))
                .build();
    }

    // 인자 검사 뒤 상태 조회와 예산 확인 응답
    private CallToolResult call(Map<String, Object> arguments) {

        Map<String, Object> safeArguments = arguments == null
                ? Map
                        .of()
                : arguments;
        ToolArguments
                .requireOnly(
                        safeArguments,
                        ARGUMENTS
                );
        int budget = ToolArguments
                .maxContextTokens(safeArguments);
        StatusResult status = statusFacade
                .status();

        return toolResponses
                .success(
                        status
                                .readiness(),
                        null,
                        List
                                .of(status),
                        budget
                );
    }

    // Tool 이름·설명·입력 schema·읽기 전용 표시
    private static Tool definition() {

        return Tool
                .builder(
                        NAME,
                        inputSchema()
                )
                .title("DocLens status")
                .description("Readiness of the local DocLens server, program and protocol version, installed builds.")
                .annotations(ToolAnnotations
                        .builder()
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .build();
    }

    // maxContextTokens 하나만 받는 닫힌 입력 schema
    private static Map<String, Object> inputSchema() {

        Map<String, Object> maxContextTokens = Map
                .of(
                        "type",
                        "integer",
                        "minimum",
                        ToolArguments.MIN_BUDGET,
                        "maximum",
                        ToolArguments.MAX_BUDGET,
                        "default",
                        ToolArguments.DEFAULT_BUDGET
                );

        return Map
                .of(
                        "type",
                        "object",
                        "additionalProperties",
                        false,
                        "properties",
                        Map
                                .of(
                                        ToolArguments.MAX_CONTEXT_TOKENS,
                                        maxContextTokens
                                ),
                        "required",
                        List
                                .of()
                );
    }
}
