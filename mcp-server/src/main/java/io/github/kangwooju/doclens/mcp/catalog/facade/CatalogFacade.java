package io.github.kangwooju.doclens.mcp.catalog.facade;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.mcp.catalog.dto.CatalogPage;
import io.github.kangwooju.doclens.mcp.catalog.dto.ProjectResult;
import io.github.kangwooju.doclens.mcp.catalog.dto.VersionResult;
import io.github.kangwooju.doclens.mcp.global.response.exception.ToolFailureException;
import io.github.kangwooju.doclens.mcp.global.response.type.ToolErrorCode;
import io.github.kangwooju.doclens.mcp.pack.dto.InstalledPack;
import io.github.kangwooju.doclens.mcp.pack.service.InstalledPackService;
import lombok.RequiredArgsConstructor;

/** 설치된 프로젝트와 버전 목록 Facade */
@Component
@RequiredArgsConstructor
public class CatalogFacade {

    private final InstalledPackService installedPackService;

    /** 설치 프로젝트 목록(projectId 순) */
    public CatalogPage projects(int limit) {

        List<ProjectResult> projects = installedPackService
                .installed()
                .stream()
                .collect(Collectors
                        .groupingBy(
                                InstalledPack::project,
                                LinkedHashMap::new,
                                Collectors
                                        .toList()
                        ))
                .entrySet()
                .stream()
                .map(entry -> ProjectResult
                        .from(
                                entry
                                        .getKey(),
                                entry
                                        .getValue()
                        ))
                .toList();

        return CatalogPage
                .of(
                        installedPackService
                                .readiness(),
                        projects,
                        limit
                );
    }

    /** 한 프로젝트의 설치 버전 목록, 설치되지 않은 프로젝트는 NOT_FOUND */
    public CatalogPage versions(
            String project,
            int limit
    ) {

        List<VersionResult> versions = installedPackService
                .versions(project)
                .stream()
                .map(VersionResult::from)
                .toList();

        if (versions
                .isEmpty()) {
            throw new ToolFailureException(ToolErrorCode.NOT_FOUND);
        }

        return CatalogPage
                .of(
                        installedPackService
                                .readiness(),
                        versions,
                        limit
                );
    }
}
