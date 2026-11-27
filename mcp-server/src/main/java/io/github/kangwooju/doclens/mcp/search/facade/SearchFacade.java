package io.github.kangwooju.doclens.mcp.search.facade;

import java.util.List;
import java.util.stream.IntStream;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.mcp.global.response.exception.ToolFailureException;
import io.github.kangwooju.doclens.mcp.global.response.type.ToolErrorCode;
import io.github.kangwooju.doclens.mcp.pack.dto.InstalledPack;
import io.github.kangwooju.doclens.mcp.pack.dto.ResolvedPack;
import io.github.kangwooju.doclens.mcp.pack.service.InstalledPackService;
import io.github.kangwooju.doclens.mcp.search.dto.ChunkHit;
import io.github.kangwooju.doclens.mcp.search.dto.ChunkResult;
import io.github.kangwooju.doclens.mcp.search.dto.SearchPage;
import io.github.kangwooju.doclens.mcp.search.dto.SearchRequest;
import io.github.kangwooju.doclens.mcp.search.service.SearchService;
import lombok.RequiredArgsConstructor;

/** 문서 검색 Facade */
@Component
@RequiredArgsConstructor
public class SearchFacade {

    private final InstalledPackService installedPackService;

    private final SearchService searchService;

    /** 요청한 프로젝트와 버전의 Pack 검색, 설치 Pack이 없으면 NOT_READY, 맞는 Pack이 없으면 NOT_FOUND */
    public SearchPage search(SearchRequest request) {

        if (installedPackService
                .installed()
                .isEmpty()) {
            throw new ToolFailureException(ToolErrorCode.NOT_READY);
        }

        InstalledPack pack = installedPackService
                .resolve(
                        request
                                .project(),
                        request
                                .exactVersion(),
                        request
                                .buildId()
                )
                .orElseThrow(() -> new ToolFailureException(ToolErrorCode.NOT_FOUND));
        List<ChunkHit> hits = searchService
                .candidates(
                        pack,
                        request
                                .query(),
                        request
                                .documentTypes()
                );
        int count = Math
                .min(
                        request
                                .topK(),
                        hits
                                .size()
                );
        List<ChunkResult> results = IntStream
                .range(
                        0,
                        count
                )
                .mapToObj(index -> ChunkResult
                        .from(
                                hits
                                        .get(index),
                                index + 1,
                                pack
                        ))
                .toList();

        return new SearchPage(
                installedPackService
                        .readiness(),
                ResolvedPack
                        .from(pack),
                results
        );
    }
}
