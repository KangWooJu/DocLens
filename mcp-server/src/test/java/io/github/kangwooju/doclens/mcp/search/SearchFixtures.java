package io.github.kangwooju.doclens.mcp.search;

import java.nio.file.Path;

import io.github.kangwooju.doclens.mcp.global.cache.CacheDirectory;
import io.github.kangwooju.doclens.mcp.pack.policy.VersionOrderPolicy;
import io.github.kangwooju.doclens.mcp.pack.service.InstalledPackService;
import io.github.kangwooju.doclens.mcp.pack.store.InstalledPackStore;
import io.github.kangwooju.doclens.mcp.search.converter.HeadingPathConverter;
import io.github.kangwooju.doclens.mcp.search.converter.SearchTermsConverter;
import io.github.kangwooju.doclens.mcp.search.converter.SourceMapConverter;
import io.github.kangwooju.doclens.mcp.search.facade.SearchFacade;
import io.github.kangwooju.doclens.mcp.search.repository.ChunkSearchRepository;
import io.github.kangwooju.doclens.mcp.search.service.SearchService;

/**
 * 시험 캐시 루트 위에 실제 객체로 조립한 검색 계층
 */
public record SearchFixtures(
        InstalledPackStore store,
        InstalledPackService installedPackService,
        SearchService searchService,
        SearchFacade searchFacade
) implements AutoCloseable {

    /** cacheRoot에 설치된 Pack을 읽는 검색 계층 */
    public static SearchFixtures over(Path cacheRoot) {

        InstalledPackStore store = new InstalledPackStore(new CacheDirectory(cacheRoot));
        InstalledPackService installedPackService = new InstalledPackService(
                store,
                new VersionOrderPolicy()
        );
        SearchService searchService = new SearchService(
                store,
                new ChunkSearchRepository(
                        new HeadingPathConverter(),
                        new SearchTermsConverter(),
                        new SourceMapConverter()
                )
        );

        return new SearchFixtures(
                store,
                installedPackService,
                searchService,
                new SearchFacade(
                        installedPackService,
                        searchService
                )
        );
    }

    /** Store가 연 Reader 닫기 */
    @Override
    public void close() {

        store
                .close();
    }
}
