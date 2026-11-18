package io.github.kangwooju.doclens.mcp.pack.service;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import io.github.kangwooju.doclens.mcp.global.response.Readiness;
import io.github.kangwooju.doclens.mcp.global.response.UpdateStatus;
import io.github.kangwooju.doclens.mcp.pack.dto.InstalledPack;
import io.github.kangwooju.doclens.mcp.pack.policy.VersionOrderPolicy;
import io.github.kangwooju.doclens.mcp.pack.store.InstalledPackStore;
import lombok.RequiredArgsConstructor;

/**
 * 설치 Pack 조회와 준비 상태 판정
 *
 * <p>순서 B는 서버와 최신 여부를 확인하지 않으므로 Pack이 있으면 READY_STALE(MC05·MC06)
 */
@Service
@RequiredArgsConstructor
public class InstalledPackService {

    private final InstalledPackStore installedPackStore;

    private final VersionOrderPolicy versionOrderPolicy;

    /** 설치 Pack 전체(project, 버전 순서, buildId 순) */
    public List<InstalledPack> installed() {

        return installedPackStore
                .installed()
                .stream()
                .sorted(Comparator
                        .comparing(InstalledPack::project)
                        .thenComparing(
                                InstalledPack::exactVersion,
                                versionOrderPolicy
                        )
                        .thenComparing(InstalledPack::buildId))
                .toList();
    }

    /** 한 프로젝트의 설치 Pack(버전 순서, buildId 순) */
    public List<InstalledPack> versions(String project) {

        return installed()
                .stream()
                .filter(pack -> pack
                        .project()
                        .equals(project))
                .toList();
    }

    /** 검색 준비 상태 */
    public Readiness readiness() {

        return installedPackStore
                .installed()
                .isEmpty()
                ? Readiness.NOT_READY
                : Readiness.READY_STALE;
    }

    /** 갱신 상태 */
    public UpdateStatus updateStatus() {

        return installedPackStore
                .installed()
                .isEmpty()
                ? UpdateStatus.FIRST_INSTALL_PENDING
                : UpdateStatus.STALE_SOURCE;
    }
}
