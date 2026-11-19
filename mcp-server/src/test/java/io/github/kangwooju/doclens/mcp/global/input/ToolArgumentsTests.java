package io.github.kangwooju.doclens.mcp.global.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.kangwooju.doclens.mcp.global.response.dto.Budget;
import io.github.kangwooju.doclens.mcp.global.response.exception.InvalidToolInputException;

@DisplayName("Tool 입력 인자 검사")
class ToolArgumentsTests {

    @Test
    @DisplayName("maxContextTokens 기본값, 범위, 정수 검사와 미지 인자 거절")
    void validatesArguments() {

        assertEquals(
                Budget.DEFAULT,
                ToolArguments
                        .maxContextTokens(Map
                                .of())
        );
        assertEquals(
                300,
                ToolArguments
                        .maxContextTokens(Map
                                .of(
                                        ToolArguments.MAX_CONTEXT_TOKENS,
                                        300
                                ))
        );

        for (Object bad : List
                .of(
                        255,
                        16385,
                        300.5,
                        "300"
                )) {
            assertThrowsInvalid(() -> ToolArguments
                    .maxContextTokens(Map
                            .of(
                                    ToolArguments.MAX_CONTEXT_TOKENS,
                                    bad
                            )));
        }

        assertThrowsInvalid(() -> ToolArguments
                .requireOnly(
                        Map
                                .of(
                                        "query",
                                        "x"
                                ),
                        Set
                                .of(ToolArguments.MAX_CONTEXT_TOKENS)
                ));
    }

    @Test
    @DisplayName("limit 기본값, 범위와 문자열 인자의 필수, 형식, 길이 검사")
    void validatesLimitAndText() {

        assertEquals(
                ToolArguments.DEFAULT_LIMIT,
                ToolArguments
                        .limit(Map
                                .of())
        );
        assertThrowsInvalid(() -> ToolArguments
                .limit(Map
                        .of(
                                ToolArguments.LIMIT,
                                51
                        )));
        assertEquals(
                "spring-boot",
                ToolArguments
                        .text(
                                Map
                                        .of(
                                                "project",
                                                "spring-boot"
                                        ),
                                "project",
                                128
                        )
        );
        assertEquals(
                null,
                ToolArguments
                        .optionalText(
                                Map
                                        .of(),
                                "buildId",
                                64
                        )
        );
        assertEquals(
                Map
                        .of(),
                ToolArguments
                        .requireOnly(
                                null,
                                Set
                                        .of()
                        )
        );

        for (Map<String, Object> bad : List
                .<Map<String, Object>>of(
                        Map
                                .of(),
                        Map
                                .of(
                                        "project",
                                        ""
                                ),
                        Map
                                .of(
                                        "project",
                                        7
                                ),
                        Map
                                .of(
                                        "project",
                                        "가"
                                                .repeat(129)
                                )
                )) {
            assertThrowsInvalid(() -> ToolArguments
                    .text(
                            bad,
                            "project",
                            128
                    ));
        }
    }

    // InvalidToolInputException 발생 확인
    private static void assertThrowsInvalid(Runnable call) {

        assertThrows(
                InvalidToolInputException.class,
                call::run
        );
    }
}
