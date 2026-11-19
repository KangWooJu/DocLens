package io.github.kangwooju.doclens.mcp.global.response.dto;

import java.util.List;

import io.github.kangwooju.doclens.mcp.global.response.type.Readiness;

/** Tool 성공 응답, nextCursor는 아직 항상 null */
public record ToolSuccess(
        int schemaVersion,
        String status,
        Readiness readiness,
        Object resolved,
        List<?> results,
        boolean truncated,
        String nextCursor,
        List<?> omissions,
        Budget budget,
        boolean externalData
) {

    /** 목록 불변 사본 */
    public ToolSuccess {

        results = List
                .copyOf(results);
        omissions = List
                .copyOf(omissions);
    }

    /** 누락 없는 성공 응답 초안 */
    public static ToolSuccess of(
            Readiness readiness,
            Object resolved,
            List<?> results,
            int requestedBudget,
            boolean truncated
    ) {

        return new ToolSuccess(
                1,
                "OK",
                readiness,
                resolved,
                results,
                truncated,
                null,
                List
                        .of(),
                Budget
                        .initial(requestedBudget),
                true
        );
    }

    /** 예산 값만 바꾼 사본 */
    public ToolSuccess withBudget(Budget newBudget) {

        return new ToolSuccess(
                schemaVersion,
                status,
                readiness,
                resolved,
                results,
                truncated,
                nextCursor,
                omissions,
                newBudget,
                externalData
        );
    }
}
