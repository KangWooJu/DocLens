package io.github.kangwooju.doclens.mcp.global.cache;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * DocLens 로컬 캐시 루트
 *
 * <p>`DOCLENS_CACHE`(절대 경로)가 있으면 그 경로, 없으면 OS 기본 경로
 * <p>macOS `~/Library/Caches/doclens`, Windows `%LOCALAPPDATA%/doclens/cache`,
 * 그 밖 `${XDG_CACHE_HOME:-~/.cache}/doclens`
 */
public record CacheDirectory(Path root) {

    /** 캐시 루트 지정 환경 변수 이름 */
    public static final String ENVIRONMENT_VARIABLE = "DOCLENS_CACHE";

    public CacheDirectory {

        if (root == null || !root
                .isAbsolute()) {
            throw new IllegalStateException("cache root must be an absolute path");
        }

        root = root
                .normalize();
    }

    /** 환경 변수와 OS 정보 기준의 캐시 루트 결정 */
    public static CacheDirectory resolve(
            Map<String, String> environment,
            String osName,
            String userHome
    ) {

        Path configured = absolutePath(
                environment,
                ENVIRONMENT_VARIABLE
        );

        if (configured != null) {
            return new CacheDirectory(configured);
        }

        if (hasText(environment
                .get(ENVIRONMENT_VARIABLE))) {
            throw new IllegalStateException(ENVIRONMENT_VARIABLE + " must be an absolute path");
        }

        if (!hasText(userHome)) {
            throw new IllegalStateException("user.home is not set; set " + ENVIRONMENT_VARIABLE);
        }

        Path home = Path
                .of(userHome);
        String os = hasText(osName)
                ? osName
                        .toLowerCase(Locale.ROOT)
                : "";

        if (os
                .startsWith("mac")) {
            return new CacheDirectory(home
                    .resolve("Library/Caches/doclens"));
        }

        if (os
                .startsWith("windows")) {
            Path localAppData = absolutePath(
                    environment,
                    "LOCALAPPDATA"
            );
            Path base = localAppData != null
                    ? localAppData
                    : home
                            .resolve("AppData/Local");

            return new CacheDirectory(base
                    .resolve("doclens/cache"));
        }

        Path xdgCacheHome = absolutePath(
                environment,
                "XDG_CACHE_HOME"
        );
        Path base = xdgCacheHome != null
                ? xdgCacheHome
                : home
                        .resolve(".cache");

        return new CacheDirectory(base
                .resolve("doclens"));
    }

    /** 로그 디렉터리 */
    public Path logs() {

        return root
                .resolve("logs");
    }

    // 환경 변수 값이 절대 경로일 때만 Path, 그 밖에는 null
    private static Path absolutePath(
            Map<String, String> environment,
            String name
    ) {

        String value = environment
                .get(name);

        if (!hasText(value)) {
            return null;
        }

        Path path = Path
                .of(value);

        return path
                .isAbsolute()
                ? path
                : null;
    }

    // null이 아니고 공백만이 아닌 문자열 여부
    private static boolean hasText(String value) {

        return value != null && !value
                .isBlank();
    }
}
