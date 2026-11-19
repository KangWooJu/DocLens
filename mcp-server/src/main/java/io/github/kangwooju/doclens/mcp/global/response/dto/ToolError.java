package io.github.kangwooju.doclens.mcp.global.response.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.kangwooju.doclens.mcp.global.response.type.ToolErrorCode;

/** Tool 오류 응답, 예산 필드는 BUDGET_TOO_SMALL일 때만 출력 */
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
                Budget.MIN,
                Budget.MAX,
                minBudget > Budget.MAX
        );
    }
}
