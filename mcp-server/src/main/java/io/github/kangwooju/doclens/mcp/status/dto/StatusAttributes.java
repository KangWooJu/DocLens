package io.github.kangwooju.doclens.mcp.status.dto;

import java.util.List;

import io.github.kangwooju.doclens.mcp.global.response.Readiness;
import io.github.kangwooju.doclens.mcp.global.response.UpdateStatus;

/**
 * STATUS 결과 속성(D16)
 *
 * <p>installedBuilds·capabilities는 Pack 찾기(B2)와 검색(B3)이 생기면 채움
 */
public record StatusAttributes(
        String programVersion,
        String protocolVersion,
        Readiness readiness,
        UpdateStatus updateStatus,
        String lastAttemptedAt,
        String lastConfirmedAt,
        List<Object> installedBuilds,
        List<String> capabilities,
        String errorCode
) {

    public StatusAttributes {

        installedBuilds = List
                .copyOf(installedBuilds);
        capabilities = List
                .copyOf(capabilities);
    }
}
