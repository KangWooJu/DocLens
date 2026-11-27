package io.github.kangwooju.doclens.mcp.global.response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.kangwooju.doclens.mcp.global.response.dto.Budget;
import io.github.kangwooju.doclens.mcp.global.response.dto.ToolError;
import io.github.kangwooju.doclens.mcp.global.response.dto.ToolSuccess;
import io.github.kangwooju.doclens.mcp.global.response.exception.InvalidToolInputException;
import io.github.kangwooju.doclens.mcp.global.response.exception.ToolFailureException;
import io.github.kangwooju.doclens.mcp.global.response.type.Readiness;
import io.github.kangwooju.doclens.mcp.global.response.type.ToolErrorCode;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("Tool 응답 조립")
class ToolResponsesTests {

    private final McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(JsonMapper
            .builder()
            .build());

    private final ToolResponses toolResponses = new ToolResponses(jsonMapper);

    @Test
    @DisplayName("응답 줄 전체 바이트와 일치하는 예산 보고")
    void reportsWireBytesOfWholeResponse() {

        CallToolResult result = toolResponses
                .success(
                        Readiness.NOT_READY,
                        null,
                        List
                                .of(Map
                                        .of(
                                                "kind",
                                                "STATUS"
                                        )),
                        Budget.DEFAULT,
                        false
                );
        ToolSuccess success = (ToolSuccess) result
                .structuredContent();

        assertFalse(result
                .isError());
        assertEquals(
                toolResponses
                        .wireBytes(
                                result,
                                ToolResponses.RESERVED_ID
                        ),
                success
                        .budget()
                        .wireBytes()
        );
        assertEquals(
                (success
                        .budget()
                        .wireBytes() + 2) / 3,
                success
                        .budget()
                        .estimatedTokens()
        );
        assertEquals(
                ToolResponses.CONTENT_TEXT,
                jsonMapper
                        .convertValue(
                                result
                                        .content()
                                        .getFirst(),
                                Map.class
                        )
                        .get("text")
        );
    }

    @Test
    @DisplayName("실제 id 기준 예산 재계산: 짧은 정수 id의 정확한 wireBytes, 긴 문자열 id의 예산 초과 처리")
    void finalizesBudgetForRealRequestId() {

        CallToolResult reserved = toolResponses
                .success(
                        Readiness.NOT_READY,
                        null,
                        List
                                .of(Map
                                        .of(
                                                "kind",
                                                "STATUS"
                                        )),
                        Budget.MIN,
                        false
                );
        CallToolResult forSmallId = toolResponses
                .finalizeFor(
                        reserved,
                        7
                );
        ToolSuccess exact = (ToolSuccess) forSmallId
                .structuredContent();

        assertFalse(forSmallId
                .isError());
        assertEquals(
                toolResponses
                        .wireBytes(
                                forSmallId,
                                7
                        ),
                exact
                        .budget()
                        .wireBytes()
        );
        assertTrue(exact
                .budget()
                .wireBytes() < ((ToolSuccess) reserved
                        .structuredContent())
                        .budget()
                        .wireBytes());

        String exoticId = "x"
                .repeat(2000);
        CallToolResult forExoticId = toolResponses
                .finalizeFor(
                        reserved,
                        exoticId
                );

        assertTrue(forExoticId
                .isError());
        assertEquals(
                ToolErrorCode.BUDGET_TOO_SMALL,
                ((ToolError) forExoticId
                        .structuredContent())
                        .code()
        );

        CallToolResult error = toolResponses
                .error(ToolError
                        .of(ToolErrorCode.NOT_READY));

        assertEquals(
                error,
                toolResponses
                        .finalizeFor(
                                error,
                                exoticId
                        )
        );
    }

    @Test
    @DisplayName("첫 결과도 넘치는 응답의 BUDGET_TOO_SMALL과 최소 예산")
    void rejectsOversizedResultWithMinimumBudget() {

        List<Map<String, String>> results = List
                .of(Map
                        .of(
                                "text",
                                "x"
                                        .repeat(1200)
                        ));

        CallToolResult tooSmall = toolResponses
                .success(
                        Readiness.READY,
                        null,
                        results,
                        Budget.MIN,
                        false
                );
        ToolError error = (ToolError) tooSmall
                .structuredContent();

        assertTrue(tooSmall
                .isError());
        assertEquals(
                ToolErrorCode.BUDGET_TOO_SMALL,
                error
                        .code()
        );
        assertFalse(error
                .unservableAtHardCap());

        CallToolResult fits = toolResponses
                .success(
                        Readiness.READY,
                        null,
                        results,
                        Math
                                .toIntExact(error
                                        .minBudget()),
                        false
                );
        CallToolResult justBelow = toolResponses
                .success(
                        Readiness.READY,
                        null,
                        results,
                        Math
                                .toIntExact(error
                                        .minBudget() - 1),
                        false
                );

        assertFalse(fits
                .isError());
        assertTrue(justBelow
                .isError());
    }

    @Test
    @DisplayName("예산을 넘는 뒤쪽 결과 제외와 truncated 표시, 빈 결과의 성공")
    void dropsTrailingResultsOverBudget() {

        List<Map<String, String>> results = Collections
                .nCopies(
                        3,
                        Map
                                .of(
                                        "text",
                                        "x"
                                                .repeat(400)
                                )
                );

        CallToolResult trimmed = toolResponses
                .success(
                        Readiness.READY,
                        null,
                        results,
                        500,
                        false
                );
        ToolSuccess success = (ToolSuccess) trimmed
                .structuredContent();

        assertFalse(trimmed
                .isError());
        assertEquals(
                2,
                success
                        .results()
                        .size()
        );
        assertTrue(success
                .truncated());
        assertTrue(success
                .budget()
                .fitsRequested());

        CallToolResult empty = toolResponses
                .success(
                        Readiness.NOT_READY,
                        null,
                        List
                                .of(),
                        Budget.MIN,
                        false
                );

        assertFalse(empty
                .isError());
        assertFalse(((ToolSuccess) empty
                .structuredContent())
                .truncated());
    }

    @Test
    @DisplayName("예산 필드 없는 일반 오류 직렬화")
    void omitsBudgetFieldsOnPlainError() throws Exception {

        String json = jsonMapper
                .writeValueAsString(ToolError
                        .of(ToolErrorCode.INVALID_INPUT));

        assertEquals(
                "{\"schemaVersion\":1,\"status\":\"ERROR\",\"code\":\"INVALID_INPUT\"}",
                json
        );
    }

    @Test
    @DisplayName("입력 위반의 INVALID_INPUT, 처리 실패의 해당 코드, 그 밖 예외의 INTERNAL_ERROR 변환")
    void guardsToolCalls() {

        CallToolResult invalid = toolResponses
                .guard(
                        "test",
                        () -> {
                            throw new InvalidToolInputException("bad");
                        }
                );
        CallToolResult notFound = toolResponses
                .guard(
                        "test",
                        () -> {
                            throw new ToolFailureException(ToolErrorCode.NOT_FOUND);
                        }
                );
        CallToolResult internal = toolResponses
                .guard(
                        "test",
                        () -> {
                            throw new IllegalStateException("secret query text");
                        }
                );

        assertEquals(
                ToolErrorCode.INVALID_INPUT,
                ((ToolError) invalid
                        .structuredContent())
                        .code()
        );
        assertEquals(
                ToolErrorCode.NOT_FOUND,
                ((ToolError) notFound
                        .structuredContent())
                        .code()
        );
        assertEquals(
                ToolErrorCode.INTERNAL_ERROR,
                ((ToolError) internal
                        .structuredContent())
                        .code()
        );
    }
}
