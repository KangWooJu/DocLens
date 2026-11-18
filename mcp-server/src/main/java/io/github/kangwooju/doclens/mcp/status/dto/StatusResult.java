package io.github.kangwooju.doclens.mcp.status.dto;

import java.util.List;

import io.github.kangwooju.doclens.mcp.global.mcp.ServerIdentity;
import io.github.kangwooju.doclens.mcp.global.response.Readiness;
import io.github.kangwooju.doclens.mcp.global.response.UpdateStatus;

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

    /** Pack 없이 시작한 서버의 상태 */
    public static StatusResult withoutPacks(
            ServerIdentity identity,
            Readiness readiness
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
                        UpdateStatus.FIRST_INSTALL_PENDING,
                        null,
                        null,
                        List
                                .of(),
                        List
                                .of(),
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
