package io.github.kangwooju.doclens.mcp.global.response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("ToolResponses")
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
                        ToolArguments.DEFAULT_BUDGET
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
                        ToolArguments.MIN_BUDGET
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
    @DisplayName("예산을 넘는 결과의 BUDGET_TOO_SMALL과 최소 예산")
    void rejectsOversizedResultWithMinimumBudget() {

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

        CallToolResult tooSmall = toolResponses
                .success(
                        Readiness.READY,
                        null,
                        results,
                        ToolArguments.MIN_BUDGET
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
                                        .minBudget())
                );
        CallToolResult justBelow = toolResponses
                .success(
                        Readiness.READY,
                        null,
                        results,
                        Math
                                .toIntExact(error
                                        .minBudget() - 1)
                );

        assertFalse(fits
                .isError());
        assertTrue(justBelow
                .isError());
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
    @DisplayName("입력 위반의 INVALID_INPUT, 그 밖 예외의 INTERNAL_ERROR 변환")
    void guardsToolCalls() {

        CallToolResult invalid = toolResponses
                .guard(
                        "test",
                        () -> {
                            throw new InvalidToolInputException("bad");
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
                ToolErrorCode.INTERNAL_ERROR,
                ((ToolError) internal
                        .structuredContent())
                        .code()
        );
    }

    @Test
    @DisplayName("maxContextTokens 기본값·범위·정수 검사와 미지 인자 거절")
    void validatesArguments() {

        assertEquals(
                ToolArguments.DEFAULT_BUDGET,
                ToolArguments
                        .maxContextTokens(Map
                                .of())
        );
        assertEquals(
                300,
                ToolArguments
                        .maxContextTokens(Map
                                .of(
                                        ToolArguments.MAX_CONTEXT_TOKENS,
                                        300
                                ))
        );

        for (Object bad : List
                .of(
                        255,
                        16385,
                        300.5,
                        "300"
                )) {
            assertThrowsInvalid(() -> ToolArguments
                    .maxContextTokens(Map
                            .of(
                                    ToolArguments.MAX_CONTEXT_TOKENS,
                                    bad
                            )));
        }

        assertThrowsInvalid(() -> ToolArguments
                .requireOnly(
                        Map
                                .of(
                                        "query",
                                        "x"
                                ),
                        Set
                                .of(ToolArguments.MAX_CONTEXT_TOKENS)
                ));
    }

    // InvalidToolInputException 발생 확인
    private static void assertThrowsInvalid(Runnable call) {

        assertThrows(
                InvalidToolInputException.class,
                call::run
        );
    }
}
