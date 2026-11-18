package io.github.kangwooju.doclens.mcp.pack.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.core.pack.PackFiles;
import io.github.kangwooju.doclens.core.pack.PackInfo;
import io.github.kangwooju.doclens.core.pack.PackReader;
import io.github.kangwooju.doclens.core.pack.PackValidationException;
import io.github.kangwooju.doclens.mcp.global.cache.CacheDirectory;
import io.github.kangwooju.doclens.mcp.pack.dto.InstalledPack;
import lombok.extern.slf4j.Slf4j;

/**
 * 캐시의 설치 Pack 찾기와 프로세스 수명 동안의 읽기 전용 Reader 보관
 *
 * <p>시작 시 한 번 `packs/<packId>/<buildId>/<sha256>.pack`만 훑음(MC07 실행 중 조합 고정)
 * <p>심볼릭 링크·캐시 밖 실제 경로·이름과 다른 SHA-256·검증 실패·디렉터리와 다른 packId/buildId,
 * 이미 찾은 buildId의 두 번째 파일은 건너뜀
 * <p>다운로드·설치 세대·잠금은 배포 단계 몫
 */
@Slf4j
@Component
public class InstalledPackStore implements AutoCloseable {

    private static final Pattern PACK_FILE_NAME = Pattern
            .compile("([0-9a-f]{64})\\.pack");

    private final Map<InstalledPack, PackReader> readers;

    public InstalledPackStore(CacheDirectory cacheDirectory) {

        // ponytail: 시작 시 동기 검증, 큰 Pack·다운로드가 생기면 STARTING·VERIFYING 단계의 비동기 설치로 전환
        readers = Collections
                .unmodifiableMap(scan(cacheDirectory
                        .packs()));
    }

    /** 검증을 통과한 설치 Pack 목록 */
    public List<InstalledPack> installed() {

        return List
                .copyOf(readers
                        .keySet());
    }

    /** 설치 Pack 하나의 Reader 연결로 조회 실행 */
    public <T> T read(
            InstalledPack pack,
            PackReader.Query<T> query
    ) throws SQLException {

        PackReader reader = readers
                .get(pack);

        if (reader == null) {
            throw new IllegalArgumentException("pack is not installed: " + pack
                    .packId());
        }

        return reader
                .query(query);
    }

    @Override
    public void close() {

        readers
                .values()
                .forEach(PackReader::close);
    }

    // packs 아래 세 단계 경로 훑기와 통과 Pack 열기
    private static Map<InstalledPack, PackReader> scan(Path packsRoot) {

        Map<InstalledPack, PackReader> found = new LinkedHashMap<>();

        if (!Files
                .isDirectory(
                        packsRoot,
                        LinkOption.NOFOLLOW_LINKS
                )) {
            return found;
        }

        Path realRoot = realPath(packsRoot);

        for (Path packDirectory : directories(packsRoot)) {
            for (Path buildDirectory : directories(packDirectory)) {
                for (Path file : entries(buildDirectory)) {
                    open(
                            realRoot,
                            file,
                            found
                    );
                }
            }
        }

        log
                .info(
                        "installed packs: {}",
                        found
                                .size()
                );

        return found;
    }

    // Pack 파일 하나의 경로·체크섬·내용·위치 확인 뒤 found에 추가, 거절 시 이유 기록
    private static void open(
            Path realRoot,
            Path file,
            Map<InstalledPack, PackReader> found
    ) {

        Matcher name = PACK_FILE_NAME
                .matcher(file
                        .getFileName()
                        .toString());

        if (!name
                .matches()) {
            return;
        }

        if (!Files
                .isRegularFile(
                        file,
                        LinkOption.NOFOLLOW_LINKS
                )
                || !realPath(file)
                        .startsWith(realRoot)) {
            skip(
                    file,
                    "NOT_A_REGULAR_FILE_IN_CACHE"
            );
            return;
        }

        String checksum = PackFiles
                .sha256(file);

        if (!checksum
                .equals("sha256:" + name
                        .group(1))) {
            skip(
                    file,
                    "CHECKSUM_MISMATCH"
            );
            return;
        }

        PackReader reader;

        try {
            reader = PackReader
                    .open(file);
        } catch (PackValidationException exception) {
            skip(
                    file,
                    exception
                            .reason()
                            .name()
            );
            return;
        }

        PackInfo info = reader
                .info();
        Path buildDirectory = file
                .getParent();

        if (!info
                .buildId()
                .equals(buildDirectory
                        .getFileName()
                        .toString())
                || !info
                        .packId()
                        .equals(buildDirectory
                                .getParent()
                                .getFileName()
                                .toString())) {
            reader
                    .close();

            skip(
                    file,
                    "LOCATION_MISMATCH"
            );
            return;
        }

        boolean duplicate = found
                .keySet()
                .stream()
                .anyMatch(installed -> installed
                        .buildId()
                        .equals(info
                                .buildId()));

        if (duplicate) {
            reader
                    .close();
            skip(
                    file,
                    "DUPLICATE_BUILD"
            );
            return;
        }

        found
                .put(
                        new InstalledPack(
                                info
                                        .packId(),
                                info
                                        .buildId(),
                                info
                                        .project(),
                                info
                                        .docVersion(),
                                checksum,
                                modifiedAt(file),
                                info
                                        .capabilities()
                        ),
                        reader
                );
    }

    // 건너뛴 경로와 이유 기록
    private static void skip(
            Path file,
            String reason
    ) {

        log
                .warn(
                        "pack skipped: {} ({})",
                        file,
                        reason
                );
    }

    // 심볼릭 링크가 아닌 하위 디렉터리(이름 순)
    private static List<Path> directories(Path parent) {

        List<Path> directories = new ArrayList<>();

        for (Path entry : entries(parent)) {
            if (Files
                    .isDirectory(
                            entry,
                            LinkOption.NOFOLLOW_LINKS
                    )) {
                directories
                        .add(entry);
            } else if (Files
                    .isSymbolicLink(entry)) {
                skip(
                        entry,
                        "SYMBOLIC_LINK"
                );
            }
        }

        return directories;
    }

    // 디렉터리 항목(이름 순), 읽기 실패 시 빈 목록
    private static List<Path> entries(Path directory) {

        try (Stream<Path> children = Files
                .list(directory)) {
            return children
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            log
                    .warn(
                            "cannot list {}: {}",
                            directory,
                            exception
                                    .getClass()
                                    .getName()
                    );

            return List
                    .of();
        }
    }

    // 링크를 풀어낸 실제 경로
    private static Path realPath(Path path) {

        try {
            return path
                    .toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "cannot resolve " + path,
                    exception
            );
        }
    }

    // 파일 수정 시각(초 단위)
    private static Instant modifiedAt(Path file) {

        try {
            return Files
                    .getLastModifiedTime(file)
                    .toInstant()
                    .truncatedTo(ChronoUnit.SECONDS);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "cannot read time of " + file,
                    exception
            );
        }
    }
}
