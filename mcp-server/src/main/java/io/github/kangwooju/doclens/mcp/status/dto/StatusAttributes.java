package io.github.kangwooju.doclens.mcp.status.dto;

import java.util.List;

import io.github.kangwooju.doclens.mcp.global.response.Readiness;
import io.github.kangwooju.doclens.mcp.global.response.UpdateStatus;
import io.github.kangwooju.doclens.mcp.pack.dto.ResolvedPack;

/**
 * STATUS 결과 속성(D16)
 *
 * <p>순서 B는 서버 최신 확인이 없어 lastAttemptedAt·lastConfirmedAt은 null
 */
public record StatusAttributes(
        String programVersion,
        String protocolVersion,
        Readiness readiness,
        UpdateStatus updateStatus,
        String lastAttemptedAt,
        String lastConfirmedAt,
        List<ResolvedPack> installedBuilds,
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
