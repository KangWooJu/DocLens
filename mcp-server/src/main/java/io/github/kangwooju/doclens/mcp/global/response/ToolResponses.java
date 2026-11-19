package io.github.kangwooju.doclens.mcp.global.response;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.mcp.global.input.ToolArguments;
import io.github.kangwooju.doclens.mcp.global.response.dto.Budget;
import io.github.kangwooju.doclens.mcp.global.response.dto.ToolError;
import io.github.kangwooju.doclens.mcp.global.response.dto.ToolSuccess;
import io.github.kangwooju.doclens.mcp.global.response.exception.ToolFailureException;
import io.github.kangwooju.doclens.mcp.global.response.type.Readiness;
import io.github.kangwooju.doclens.mcp.global.response.type.ToolErrorCode;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Tool 응답 조립과 예산 계산, 예산은 JSON-RPC 응답 한 줄 전체 크기 기준 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolResponses {

    /** content 고정 문구, 본문은 structuredContent 전용 */
    public static final String CONTENT_TEXT = "External source data.";

    /** 예산 계산용 임시 id, 가장 긴 정수(20바이트) */
    public static final Object RESERVED_ID = Long.MIN_VALUE;

    private static final int MAX_SETTLE_ROUNDS = 8;

    private final McpJsonMapper jsonMapper;

    /** 예산 안에 들어가는 결과만 담은 성공 응답, 첫 결과도 넘치면 BUDGET_TOO_SMALL */
    public CallToolResult success(
            Readiness readiness,
            Object resolved,
            List<?> results,
            int requestedBudget,
            boolean truncated
    ) {

        int total = results
                .size();

        // 뒤에서 하나씩 빼며 확인, 결과가 최대 50개라 선형 탐색으로 충분
        for (int count = total; count > 0; count--) {
            ToolSuccess settled = settle(
                    ToolSuccess
                            .of(
                                    readiness,
                                    resolved,
                                    results
                                            .subList(
                                                    0,
                                                    count
                                            ),
                                    requestedBudget,
                                    truncated || count < total
                            ),
                    RESERVED_ID
            );

            if (settled
                    .budget()
                    .fitsRequested()) {
                return toolResult(
                        settled,
                        false
                );
            }
        }

        return fitOrReject(
                ToolSuccess
                        .of(
                                readiness,
                                resolved,
                                results
                                        .subList(
                                                0,
                                                Math
                                                        .min(
                                                                1,
                                                                total
                                                        )
                                        ),
                                requestedBudget,
                                truncated
                        ),
                RESERVED_ID
        );
    }

    /** 실제 JSON-RPC id 기준으로 예산을 다시 계산한 전송용 결과, 성공 응답이 아니면 그대로 */
    public CallToolResult finalizeFor(
            CallToolResult result,
            Object requestId
    ) {

        if (!(result
                .structuredContent() instanceof ToolSuccess success)) {
            return result;
        }

        return fitOrReject(
                success,
                requestId
        );
    }

    /** 오류 응답 */
    public CallToolResult error(ToolError error) {

        return toolResult(
                error,
                true
        );
    }

    /** Tool 호출 실행, 처리 실패는 그 오류 코드, 그 밖 예외는 INTERNAL_ERROR */
    public CallToolResult guard(
            String toolName,
            Supplier<CallToolResult> call
    ) {

        try {
            return call
                    .get();
        } catch (ToolFailureException exception) {
            return error(ToolError
                    .of(exception
                            .code()));
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

    /** 주어진 id의 JSON-RPC 응답 한 줄(LF 포함) UTF-8 바이트 */
    public long wireBytes(
            CallToolResult result,
            Object requestId
    ) {

        try {
            String line = jsonMapper
                    .writeValueAsString(JSONRPCResponse
                            .result(
                                    requestId,
                                    result
                            ));

            return line
                    .getBytes(StandardCharsets.UTF_8).length + 1L;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    // 예산 안이면 확정 응답, 넘치면 최소 예산을 담은 BUDGET_TOO_SMALL
    private CallToolResult fitOrReject(
            ToolSuccess draft,
            Object requestId
    ) {

        ToolSuccess settled = settle(
                draft,
                requestId
        );

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
                        draft,
                        requestId
                )));
    }

    // 예산 숫자 자릿수가 안정될 때까지 재직렬화한 응답
    private ToolSuccess settle(
            ToolSuccess draft,
            Object requestId
    ) {

        ToolSuccess current = draft
                .withBudget(Budget
                        .initial(draft
                                .budget()
                                .requested()));

        for (int round = 0; round < MAX_SETTLE_ROUNDS; round++) {
            Budget measured = current
                    .budget()
                    .measured(wireBytes(
                            toolResult(
                                    current,
                                    false
                            ),
                            requestId
                    ));

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

    // 같은 응답이 들어가는 가장 작은 요청 예산
    private long minimumBudget(
            ToolSuccess draft,
            Object requestId
    ) {

        long candidate = Budget.MIN;

        while (candidate <= Budget.MAX) {
            ToolSuccess settled = settle(
                    draft
                            .withBudget(Budget
                                    .initial((int) candidate)),
                    requestId
            );
            Budget budget = settled
                    .budget();

            if (budget
                    .fitsRequested()) {
                return candidate;
            }

            candidate = Math
                    .max(
                            candidate + 1,
                            Math
                                    .max(
                                            budget
                                                    .estimatedTokens(),
                                            (budget
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
