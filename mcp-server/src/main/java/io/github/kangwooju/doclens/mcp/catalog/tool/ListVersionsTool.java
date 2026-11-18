package io.github.kangwooju.doclens.mcp.catalog.tool;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.mcp.catalog.dto.CatalogPage;
import io.github.kangwooju.doclens.mcp.catalog.facade.CatalogFacade;
import io.github.kangwooju.doclens.mcp.global.mcp.McpToolEndpoint;
import io.github.kangwooju.doclens.mcp.global.mcp.ToolDefinitions;
import io.github.kangwooju.doclens.mcp.global.response.ToolArguments;
import io.github.kangwooju.doclens.mcp.global.response.ToolResponses;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;

/**
 * list_versions Tool 진입부: 한 프로젝트의 설치 정확 버전·build 목록
 */
@Component
@RequiredArgsConstructor
public class ListVersionsTool implements McpToolEndpoint {

    /** Tool 이름 */
    public static final String NAME = "list_versions";

    /** 프로젝트 인자 이름 */
    public static final String PROJECT = "project";

    /** 프로젝트 ID 최대 길이 */
    public static final int MAX_PROJECT_LENGTH = 128;

    private static final Set<String> ARGUMENTS = Set
            .of(
                    ToolArguments.MAX_CONTEXT_TOKENS,
                    ToolArguments.LIMIT,
                    PROJECT
            );

    private final CatalogFacade catalogFacade;

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

    // 인자 검사 뒤 버전 목록과 예산 확인 응답
    private CallToolResult call(Map<String, Object> arguments) {

        Map<String, Object> safeArguments = ToolArguments
                .requireOnly(
                        arguments,
                        ARGUMENTS
                );
        int budget = ToolArguments
                .maxContextTokens(safeArguments);
        CatalogPage page = catalogFacade
                .versions(
                        ToolArguments
                                .text(
                                        safeArguments,
                                        PROJECT,
                                        MAX_PROJECT_LENGTH
                                ),
                        ToolArguments
                                .limit(safeArguments)
                );

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

    // Tool 이름·설명·입력 schema·읽기 전용 표시
    private static Tool definition() {

        return ToolDefinitions
                .readOnly(
                        NAME,
                        "Installed versions",
                        "Exact versions and builds of one project installed locally. This is not the latest release list.",
                        Map
                                .of(
                                        ToolArguments.MAX_CONTEXT_TOKENS,
                                        ToolDefinitions
                                                .budgetProperty(),
                                        PROJECT,
                                        ToolDefinitions
                                                .textProperty(MAX_PROJECT_LENGTH),
                                        ToolArguments.LIMIT,
                                        ToolDefinitions
                                                .limitProperty()
                                ),
                        List
                                .of(PROJECT)
                );
    }
}
