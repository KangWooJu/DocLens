package io.github.kangwooju.doclens.mcp.status.dto;

import java.util.List;

import io.github.kangwooju.doclens.mcp.global.mcp.ServerIdentity;
import io.github.kangwooju.doclens.mcp.global.response.Readiness;
import io.github.kangwooju.doclens.mcp.global.response.UpdateStatus;
import io.github.kangwooju.doclens.mcp.pack.dto.ResolvedPack;

/**
 * get_status의 STATUS 결과 하나(D16), text·citation은 항상 null
 */
public record StatusResult(
        String kind,
        String id,
        String text,
        Object citation,
        StatusAttributes attributes
) {

    /** 서버 정보와 설치 Pack 기준 상태 */
    public static StatusResult of(
            ServerIdentity identity,
            Readiness readiness,
            UpdateStatus updateStatus,
            List<ResolvedPack> installedBuilds,
            List<String> capabilities
    ) {

        return new StatusResult(
                "STATUS",
                identity
                        .name(),
                null,
                null,
                new StatusAttributes(
                        identity
                                .programVersion(),
                        identity
                                .protocolVersion(),
                        readiness,
                        updateStatus,
                        null,
                        null,
                        installedBuilds,
                        capabilities,
                        null
                )
        );
    }

    /** 응답 envelope에 쓰는 준비 상태 */
    public Readiness readiness() {

        return attributes
                .readiness();
    }
}
