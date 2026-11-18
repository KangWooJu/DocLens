package io.github.kangwooju.doclens.mcp.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Tool 오류 응답 본문(D16 error), 질의·예외 내용은 담지 않음
 *
 * <p>예산 관련 필드는 BUDGET_TOO_SMALL에서만 출력
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolError(
        int schemaVersion,
        String status,
        ToolErrorCode code,
        Long minBudget,
        Integer inputMinBudget,
        Integer hardMaxBudget,
        Boolean unservableAtHardCap
) {

    /** 예산 필드 없는 오류 */
    public static ToolError of(ToolErrorCode code) {

        return new ToolError(
                1,
                "ERROR",
                code,
                null,
                null,
                null,
                null
        );
    }

    /** 요청 예산 부족 오류 */
    public static ToolError budgetTooSmall(long minBudget) {

        return new ToolError(
                1,
                "ERROR",
                ToolErrorCode.BUDGET_TOO_SMALL,
                minBudget,
                ToolArguments.MIN_BUDGET,
                ToolArguments.MAX_BUDGET,
                minBudget > ToolArguments.MAX_BUDGET
        );
    }
}
