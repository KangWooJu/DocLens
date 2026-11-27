package io.github.kangwooju.doclens.mcp.status.tool;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.mcp.global.input.ToolArguments;
import io.github.kangwooju.doclens.mcp.global.input.ToolDefinitions;
import io.github.kangwooju.doclens.mcp.global.mcp.McpToolEndpoint;
import io.github.kangwooju.doclens.mcp.global.response.ToolResponses;
import io.github.kangwooju.doclens.mcp.status.dto.StatusResult;
import io.github.kangwooju.doclens.mcp.status.facade.StatusFacade;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;

/** get_status Tool, 서버 상태 조회 */
@Component
@RequiredArgsConstructor
public class GetStatusTool implements McpToolEndpoint {

    /** Tool 이름 */
    public static final String NAME = "get_status";

    private static final Set<String> ARGUMENTS = Set
            .of(ToolArguments.MAX_CONTEXT_TOKENS);

    private final StatusFacade statusFacade;

    private final ToolResponses toolResponses;

    /** get_status Tool 등록 정보 */
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

        Map<String, Object> safeArguments = ToolArguments
                .requireOnly(
                        arguments,
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
                        budget,
                        false
                );
    }

    // Tool 정의(이름, 설명, 입력 schema)
    private static Tool definition() {

        return ToolDefinitions
                .readOnly(
                        NAME,
                        "DocLens status",
                        "Readiness of the local DocLens server, program and protocol version, installed builds.",
                        Map
                                .of(
                                        ToolArguments.MAX_CONTEXT_TOKENS,
                                        ToolDefinitions
                                                .budgetProperty()
                                ),
                        List
                                .of()
                );
    }
}
