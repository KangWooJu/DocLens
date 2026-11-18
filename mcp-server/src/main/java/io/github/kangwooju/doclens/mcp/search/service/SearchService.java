package io.github.kangwooju.doclens.mcp.search.service;

import java.sql.SQLException;
import java.util.List;

import org.springframework.stereotype.Service;

import io.github.kangwooju.doclens.mcp.pack.dto.InstalledPack;
import io.github.kangwooju.doclens.mcp.pack.store.InstalledPackStore;
import io.github.kangwooju.doclens.mcp.search.dto.ChunkHit;
import io.github.kangwooju.doclens.mcp.search.dto.SearchTerms;
import io.github.kangwooju.doclens.mcp.search.repository.ChunkSearchRepository;
import lombok.RequiredArgsConstructor;

/**
 * 설치 Pack 하나의 청크 후보 검색
 */
@Service
@RequiredArgsConstructor
public class SearchService {

    /** 정렬 후보 상한(F12 기본 50) */
    public static final int CANDIDATE_CAP = 50;

    private final InstalledPackStore installedPackStore;

    private final ChunkSearchRepository chunkSearchRepository;

    /** 정렬된 청크 후보, 최대 CANDIDATE_CAP개 */
    public List<ChunkHit> candidates(
            InstalledPack pack,
            SearchTerms terms,
            List<String> documentTypes
    ) {

        try {
            return installedPackStore
                    .read(
                            pack,
                            connection -> chunkSearchRepository
                                    .find(
                                            connection,
                                            terms,
                                            documentTypes,
                                            CANDIDATE_CAP
                                    )
                    );
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "search failed in pack " + pack
                            .packId(),
                    exception
            );
        }
    }
}
