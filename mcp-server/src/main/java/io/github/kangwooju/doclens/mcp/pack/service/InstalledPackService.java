package io.github.kangwooju.doclens.mcp.pack.service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import io.github.kangwooju.doclens.core.pack.type.PackCapability;
import io.github.kangwooju.doclens.mcp.global.response.type.Readiness;
import io.github.kangwooju.doclens.mcp.global.response.type.UpdateStatus;
import io.github.kangwooju.doclens.mcp.pack.dto.InstalledPack;
import io.github.kangwooju.doclens.mcp.pack.policy.VersionOrderPolicy;
import io.github.kangwooju.doclens.mcp.pack.store.InstalledPackStore;
import lombok.RequiredArgsConstructor;

/**
 * 설치 Pack 조회와 준비 상태 판정
 * 서버 최신 확인 전이라 Pack이 있으면 READY_STALE
 */
@Service
@RequiredArgsConstructor
public class InstalledPackService {

    /** 제공하는 검색 방식 */
    public static final List<String> SERVED_CAPABILITIES = List
            .of(PackCapability.BM25
                    .name());

    private final InstalledPackStore installedPackStore;

    private final VersionOrderPolicy versionOrderPolicy;

    /** 설치 Pack 전체(프로젝트, 버전, buildId 순) */
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

    /** 프로젝트와 버전으로 고른 Pack, build가 여럿이면 가장 최근 설치 */
    public Optional<InstalledPack> resolve(
            String project,
            String exactVersion,
            String buildId
    ) {

        return installedPackStore
                .installed()
                .stream()
                .filter(pack -> pack
                        .project()
                        .equals(project))
                .filter(pack -> pack
                        .exactVersion()
                        .equals(exactVersion))
                .filter(pack -> buildId == null || pack
                        .buildId()
                        .equals(buildId))
                .max(Comparator
                        .comparing(InstalledPack::installedAt)
                        .thenComparing(InstalledPack::buildId));
    }

    /** 이 서버가 설치 Pack으로 제공하는 검색 능력 */
    public List<String> capabilities() {

        return SERVED_CAPABILITIES
                .stream()
                .filter(capability -> installedPackStore
                        .installed()
                        .stream()
                        .anyMatch(pack -> pack
                                .capabilities()
                                .contains(capability)))
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
