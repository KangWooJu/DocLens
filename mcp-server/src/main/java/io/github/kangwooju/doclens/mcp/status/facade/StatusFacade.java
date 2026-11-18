package io.github.kangwooju.doclens.mcp.status.facade;

import java.util.List;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.mcp.global.mcp.ServerIdentity;
import io.github.kangwooju.doclens.mcp.pack.dto.ResolvedPack;
import io.github.kangwooju.doclens.mcp.pack.service.InstalledPackService;
import io.github.kangwooju.doclens.mcp.status.dto.StatusResult;
import lombok.RequiredArgsConstructor;

/**
 * 서버 상태 조회 Facade
 */
@Component
@RequiredArgsConstructor
public class StatusFacade {

    private final ServerIdentity serverIdentity;

    private final InstalledPackService installedPackService;

    /** 현재 서버 상태와 설치 build 목록 */
    public StatusResult status() {

        return StatusResult
                .of(
                        serverIdentity,
                        installedPackService
                                .readiness(),
                        installedPackService
                                .updateStatus(),
                        installedPackService
                                .installed()
                                .stream()
                                .map(ResolvedPack::from)
                                .toList(),
                        List
                                .of()
                );
    }
}
