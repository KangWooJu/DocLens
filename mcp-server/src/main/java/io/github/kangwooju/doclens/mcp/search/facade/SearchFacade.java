package io.github.kangwooju.doclens.mcp.search.facade;

import java.util.List;
import java.util.stream.IntStream;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.mcp.global.response.ToolErrorCode;
import io.github.kangwooju.doclens.mcp.global.response.ToolFailureException;
import io.github.kangwooju.doclens.mcp.pack.dto.InstalledPack;
import io.github.kangwooju.doclens.mcp.pack.dto.ResolvedPack;
import io.github.kangwooju.doclens.mcp.pack.service.InstalledPackService;
import io.github.kangwooju.doclens.mcp.search.dto.ChunkHit;
import io.github.kangwooju.doclens.mcp.search.dto.ChunkResult;
import io.github.kangwooju.doclens.mcp.search.dto.SearchPage;
import io.github.kangwooju.doclens.mcp.search.dto.SearchRequest;
import io.github.kangwooju.doclens.mcp.search.dto.SearchTerms;
import io.github.kangwooju.doclens.mcp.search.service.SearchService;
import lombok.RequiredArgsConstructor;

/**
 * 문서 검색 Facade: 설치 Pack 하나 고르기, 후보 검색, topK 결과 조립
 */
@Component
@RequiredArgsConstructor
public class SearchFacade {

    private final InstalledPackService installedPackService;

    private final SearchService searchService;

    /** 요청한 프로젝트·정확 버전 Pack의 검색 결과, Pack이 없으면 NOT_READY, 맞는 Pack이 없으면 NOT_FOUND */
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
                        SearchTerms
                                .from(request
                                        .query()),
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
