package io.github.kangwooju.doclens.mcp.search.tool;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.mcp.global.input.ToolArguments;
import io.github.kangwooju.doclens.mcp.global.input.ToolDefinitions;
import io.github.kangwooju.doclens.mcp.global.mcp.McpToolEndpoint;
import io.github.kangwooju.doclens.mcp.global.response.ToolResponses;
import io.github.kangwooju.doclens.mcp.global.response.exception.InvalidToolInputException;
import io.github.kangwooju.doclens.mcp.global.response.exception.ToolFailureException;
import io.github.kangwooju.doclens.mcp.global.response.type.ToolErrorCode;
import io.github.kangwooju.doclens.mcp.search.dto.SearchPage;
import io.github.kangwooju.doclens.mcp.search.dto.SearchRequest;
import io.github.kangwooju.doclens.mcp.search.facade.SearchFacade;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;

/** search_docs Tool, 설치 Pack 하나에서 BM25 검색 */
@Component
@RequiredArgsConstructor
public class SearchDocsTool implements McpToolEndpoint {

    /** Tool 이름 */
    public static final String NAME = "search_docs";

    /** 질의 UTF-8 최대 바이트 */
    public static final int MAX_QUERY_BYTES = 2048;

    private static final String QUERY = "query";
    private static final String PROJECT = "project";
    private static final String EXACT_VERSION = "exactVersion";
    private static final String BUILD_ID = "buildId";
    private static final String TOP_K = "topK";
    private static final String DOCUMENT_TYPES = "documentTypes";
    private static final int MAX_PROJECT_LENGTH = 128;
    private static final int MAX_VERSION_LENGTH = 64;
    private static final int MAX_BUILD_ID_LENGTH = 64;
    private static final int MAX_TOP_K = 20;
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_DOCUMENT_TYPES = 10;

    private static final Set<String> ARGUMENTS = Set
            .of(
                    ToolArguments.MAX_CONTEXT_TOKENS,
                    QUERY,
                    PROJECT,
                    EXACT_VERSION,
                    BUILD_ID,
                    TOP_K,
                    DOCUMENT_TYPES
            );

    private final SearchFacade searchFacade;

    private final ToolResponses toolResponses;

    /** search_docs Tool 등록 정보 */
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

    // 인자 검사 뒤 검색과 예산 확인 응답
    private CallToolResult call(Map<String, Object> arguments) {

        Map<String, Object> safeArguments = ToolArguments
                .requireOnly(
                        arguments,
                        ARGUMENTS
                );
        int budget = ToolArguments
                .maxContextTokens(safeArguments);
        SearchPage page = searchFacade
                .search(request(safeArguments));

        return toolResponses
                .success(
                        page
                                .readiness(),
                        page
                                .resolved(),
                        page
                                .results(),
                        budget,
                        false
                );
    }

    // 검사한 인자의 검색 요청 변환, 질의 바이트 초과는 QUERY_TOO_LONG
    private static SearchRequest request(Map<String, Object> arguments) {

        String query = ToolArguments
                .text(
                        arguments,
                        QUERY,
                        Integer.MAX_VALUE
                );

        if (query
                .getBytes(StandardCharsets.UTF_8).length > MAX_QUERY_BYTES) {
            throw new ToolFailureException(ToolErrorCode.QUERY_TOO_LONG);
        }

        return new SearchRequest(
                query,
                ToolArguments
                        .text(
                                arguments,
                                PROJECT,
                                MAX_PROJECT_LENGTH
                        ),
                ToolArguments
                        .text(
                                arguments,
                                EXACT_VERSION,
                                MAX_VERSION_LENGTH
                        ),
                ToolArguments
                        .optionalText(
                                arguments,
                                BUILD_ID,
                                MAX_BUILD_ID_LENGTH
                        ),
                ToolArguments
                        .integer(
                                arguments,
                                TOP_K,
                                1,
                                MAX_TOP_K,
                                DEFAULT_TOP_K
                        ),
                documentTypes(arguments)
        );
    }

    // 알려진 Pack 문서 유형만 담은 documentTypes 인자
    private static List<String> documentTypes(Map<String, Object> arguments) {

        List<String> documentTypes = ToolArguments
                .textList(
                        arguments,
                        DOCUMENT_TYPES,
                        MAX_DOCUMENT_TYPES
                );

        if (!SearchRequest.DOCUMENT_TYPES
                .containsAll(documentTypes)) {
            throw new InvalidToolInputException("unknown document type");
        }

        return documentTypes;
    }

    // Tool 정의(이름, 설명, 입력 schema)
    private static Tool definition() {

        return ToolDefinitions
                .readOnly(
                        NAME,
                        "Search documentation",
                        "BM25 search in one installed Pack (exact project version). "
                                + "Chunks whose identifiers exactly match the query or one of its words come first. "
                                + "Results carry citations from the Pack.",
                        Map
                                .of(
                                        ToolArguments.MAX_CONTEXT_TOKENS,
                                        ToolDefinitions
                                                .budgetProperty(),
                                        QUERY,
                                        ToolDefinitions
                                                .textProperty(MAX_QUERY_BYTES),
                                        PROJECT,
                                        ToolDefinitions
                                                .textProperty(MAX_PROJECT_LENGTH),
                                        EXACT_VERSION,
                                        ToolDefinitions
                                                .textProperty(MAX_VERSION_LENGTH),
                                        BUILD_ID,
                                        ToolDefinitions
                                                .textProperty(MAX_BUILD_ID_LENGTH),
                                        TOP_K,
                                        ToolDefinitions
                                                .integerProperty(
                                                        1,
                                                        MAX_TOP_K,
                                                        DEFAULT_TOP_K
                                                ),
                                        DOCUMENT_TYPES,
                                        Map
                                                .of(
                                                        "type",
                                                        "array",
                                                        "items",
                                                        Map
                                                                .of(
                                                                        "enum",
                                                                        SearchRequest.DOCUMENT_TYPES
                                                                ),
                                                        "maxItems",
                                                        MAX_DOCUMENT_TYPES
                                                )
                                ),
                        List
                                .of(
                                        QUERY,
                                        PROJECT,
                                        EXACT_VERSION
                                )
                );
    }
}
