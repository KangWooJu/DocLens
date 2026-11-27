package io.github.kangwooju.doclens.mcp.catalog.tool;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.mcp.catalog.dto.CatalogPage;
import io.github.kangwooju.doclens.mcp.catalog.facade.CatalogFacade;
import io.github.kangwooju.doclens.mcp.global.input.ToolArguments;
import io.github.kangwooju.doclens.mcp.global.input.ToolDefinitions;
import io.github.kangwooju.doclens.mcp.global.mcp.McpToolEndpoint;
import io.github.kangwooju.doclens.mcp.global.response.ToolResponses;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;

/** list_projects Tool, 설치 Pack이 있는 프로젝트 목록 */
@Component
@RequiredArgsConstructor
public class ListProjectsTool implements McpToolEndpoint {

    /** Tool 이름 */
    public static final String NAME = "list_projects";

    private static final Set<String> ARGUMENTS = Set
            .of(
                    ToolArguments.MAX_CONTEXT_TOKENS,
                    ToolArguments.LIMIT
            );

    private final CatalogFacade catalogFacade;

    private final ToolResponses toolResponses;

    /** list_projects Tool 등록 정보 */
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

    // 인자 검사 뒤 프로젝트 목록과 예산 확인 응답
    private CallToolResult call(Map<String, Object> arguments) {

        Map<String, Object> safeArguments = ToolArguments
                .requireOnly(
                        arguments,
                        ARGUMENTS
                );
        int budget = ToolArguments
                .maxContextTokens(safeArguments);
        CatalogPage page = catalogFacade
                .projects(ToolArguments
                        .limit(safeArguments));

        return toolResponses
                .success(
                        page
                                .readiness(),
                        null,
                        page
                                .results(),
                        budget,
                        page
                                .truncated()
                );
    }

    // Tool 정의(이름, 설명, 입력 schema)
    private static Tool definition() {

        return ToolDefinitions
                .readOnly(
                        NAME,
                        "Installed projects",
                        "Projects that have at least one verified documentation Pack installed locally.",
                        Map
                                .of(
                                        ToolArguments.MAX_CONTEXT_TOKENS,
                                        ToolDefinitions
                                                .budgetProperty(),
                                        ToolArguments.LIMIT,
                                        ToolDefinitions
                                                .limitProperty()
                                ),
                        List
                                .of()
                );
    }
}
