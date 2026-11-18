package io.github.kangwooju.doclens.mcp.status.facade;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.mcp.global.mcp.ServerIdentity;
import io.github.kangwooju.doclens.mcp.global.response.Readiness;
import io.github.kangwooju.doclens.mcp.status.dto.StatusResult;
import lombok.RequiredArgsConstructor;

/**
 * 서버 상태 조회 Facade
 *
 * <p>Pack 찾기(B2) 전까지 준비 상태는 NOT_READY
 */
@Component
@RequiredArgsConstructor
public class StatusFacade {

    private final ServerIdentity serverIdentity;

    /** 현재 서버 상태 */
    public StatusResult status() {

        return StatusResult
                .withoutPacks(
                        serverIdentity,
                        Readiness.NOT_READY
                );
    }
}
