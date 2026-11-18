package io.github.kangwooju.doclens.mcp.global.response;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Tool 응답 조립과 응답 줄 전체 기준 예산 계산(D16)
 *
 * <p>wire 크기는 SDK와 같은 JSON mapper로 JSON-RPC 응답 전체를 직렬화해 측정
 * <p>JSON-RPC id는 허용 최대 크기(ASCII 64자, 전부 escape)로 가정한 상한
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolResponses {

    /** content에 넣는 고정 문구, 본문은 structuredContent에만 둠 */
    public static final String CONTENT_TEXT = "External source data.";

    private static final String WORST_CASE_ID = "\u0001"
            .repeat(64);

    private static final int MAX_SETTLE_ROUNDS = 8;

    private final McpJsonMapper jsonMapper;

    /** 예산 확인을 거친 성공 응답, 넘치면 BUDGET_TOO_SMALL */
    public CallToolResult success(
            Readiness readiness,
            Object resolved,
            List<?> results,
            int requestedBudget
    ) {

        ToolSuccess settled = settle(ToolSuccess
                .of(
                        readiness,
                        resolved,
                        results,
                        requestedBudget
                ));

        if (settled
                .budget()
                .fitsRequested()) {
            return toolResult(
                    settled,
                    false
            );
        }

        return error(ToolError
                .budgetTooSmall(minimumBudget(
                        readiness,
                        resolved,
                        results
                )));
    }

    /** 오류 응답 */
    public CallToolResult error(ToolError error) {

        return toolResult(
                error,
                true
        );
    }

    /** Tool 호출 실행, 입력 위반은 INVALID_INPUT, 그 밖 예외는 INTERNAL_ERROR */
    public CallToolResult guard(
            String toolName,
            Supplier<CallToolResult> call
    ) {

        try {
            return call
                    .get();
        } catch (InvalidToolInputException exception) {
            return error(ToolError
                    .of(ToolErrorCode.INVALID_INPUT));
        } catch (RuntimeException exception) {
            log
                    .error(
                            "tool {} failed: {}",
                            toolName,
                            exception
                                    .getClass()
                                    .getName()
                    );

            return error(ToolError
                    .of(ToolErrorCode.INTERNAL_ERROR));
        }
    }

    /** JSON-RPC 응답 한 줄(LF 포함)의 UTF-8 바이트 상한 */
    public long wireBytes(CallToolResult result) {

        try {
            String line = jsonMapper
                    .writeValueAsString(new JSONRPCResponse(
                            "2.0",
                            WORST_CASE_ID,
                            result,
                            null
                    ));

            return line
                    .getBytes(StandardCharsets.UTF_8).length + 1L;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    // 예산 숫자 자릿수가 안정될 때까지 재직렬화한 응답
    private ToolSuccess settle(ToolSuccess draft) {

        ToolSuccess current = draft;

        for (int round = 0; round < MAX_SETTLE_ROUNDS; round++) {
            Budget measured = current
                    .budget()
                    .measured(wireBytes(toolResult(
                            current,
                            false
                    )));

            if (measured
                    .equals(current
                            .budget())) {
                return current;
            }

            current = current
                    .withBudget(measured);
        }

        throw new IllegalStateException("budget did not settle");
    }

    // 같은 결과가 들어가는 가장 작은 요청 예산
    private long minimumBudget(
            Readiness readiness,
            Object resolved,
            List<?> results
    ) {

        long candidate = ToolArguments.MIN_BUDGET;

        while (candidate <= ToolArguments.MAX_BUDGET) {
            ToolSuccess settled = settle(ToolSuccess
                    .of(
                            readiness,
                            resolved,
                            results,
                            (int) candidate
                    ));

            if (settled
                    .budget()
                    .fitsRequested()) {
                return candidate;
            }

            candidate = Math
                    .max(
                            candidate + 1,
                            Math
                                    .max(
                                            settled
                                                    .budget()
                                                    .estimatedTokens(),
                                            (settled
                                                    .budget()
                                                    .wireBytes() + 3) / 4
                                    )
                    );
        }

        return candidate;
    }

    // 고정 content 문구와 structuredContent로 구성한 Tool 결과
    private CallToolResult toolResult(
            Object payload,
            boolean isError
    ) {

        return CallToolResult
                .builder()
                .content(List
                        .of(TextContent
                                .builder(CONTENT_TEXT)
                                .build()))
                .structuredContent(payload)
                .isError(isError)
                .build();
    }
}
