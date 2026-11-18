package io.github.kangwooju.doclens.mcp.global.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CacheDirectory")
class CacheDirectoryTests {

    @Test
    @DisplayName("DOCLENS_CACHE 절대 경로 우선")
    void usesAbsoluteEnvironmentVariable() {

        CacheDirectory cache = CacheDirectory
                .resolve(
                        Map
                                .of(
                                        "DOCLENS_CACHE",
                                        "/data/doclens-cache"
                                ),
                        "Mac OS X",
                        "/Users/someone"
                );

        assertEquals(
                Path
                        .of("/data/doclens-cache"),
                cache
                        .root()
        );
        assertEquals(
                Path
                        .of("/data/doclens-cache/logs"),
                cache
                        .logs()
        );
    }

    @Test
    @DisplayName("상대 경로 DOCLENS_CACHE 거절")
    void rejectsRelativeEnvironmentVariable() {

        assertThrows(
                IllegalStateException.class,
                () -> CacheDirectory
                        .resolve(
                                Map
                                        .of(
                                                "DOCLENS_CACHE",
                                                "cache"
                                        ),
                                "Linux",
                                "/home/someone"
                        )
        );
    }

    @Test
    @DisplayName("macOS 기본 경로")
    void usesMacDefault() {

        assertEquals(
                Path
                        .of("/Users/someone/Library/Caches/doclens"),
                CacheDirectory
                        .resolve(
                                Map
                                        .of(),
                                "Mac OS X",
                                "/Users/someone"
                        )
                        .root()
        );
    }

    @Test
    @DisplayName("Linux 기본 경로와 절대 경로 XDG_CACHE_HOME")
    void usesLinuxDefaultAndXdg() {

        assertEquals(
                Path
                        .of("/home/someone/.cache/doclens"),
                CacheDirectory
                        .resolve(
                                Map
                                        .of(),
                                "Linux",
                                "/home/someone"
                        )
                        .root()
        );
        assertEquals(
                Path
                        .of("/var/cache/user/doclens"),
                CacheDirectory
                        .resolve(
                                Map
                                        .of(
                                                "XDG_CACHE_HOME",
                                                "/var/cache/user"
                                        ),
                                "Linux",
                                "/home/someone"
                        )
                        .root()
        );
        assertEquals(
                Path
                        .of("/home/someone/.cache/doclens"),
                CacheDirectory
                        .resolve(
                                Map
                                        .of(
                                                "XDG_CACHE_HOME",
                                                "relative"
                                        ),
                                "Linux",
                                "/home/someone"
                        )
                        .root()
        );
    }

    @Test
    @DisplayName("user.home 없음 거절")
    void rejectsMissingHome() {

        assertThrows(
                IllegalStateException.class,
                () -> CacheDirectory
                        .resolve(
                                Map
                                        .of(),
                                "Linux",
                                ""
                        )
        );
    }
}
