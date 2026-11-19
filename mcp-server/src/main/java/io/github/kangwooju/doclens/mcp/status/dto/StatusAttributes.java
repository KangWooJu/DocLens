package io.github.kangwooju.doclens.mcp.status.dto;

import java.util.List;

import io.github.kangwooju.doclens.mcp.global.response.type.Readiness;
import io.github.kangwooju.doclens.mcp.global.response.type.UpdateStatus;
import io.github.kangwooju.doclens.mcp.pack.dto.ResolvedPack;

/** STATUS 결과 속성, lastAttemptedAt과 lastConfirmedAt은 아직 null */
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

    /** 목록 불변 사본 */
    public StatusAttributes {

        installedBuilds = List
                .copyOf(installedBuilds);
        capabilities = List
                .copyOf(capabilities);
    }
}
