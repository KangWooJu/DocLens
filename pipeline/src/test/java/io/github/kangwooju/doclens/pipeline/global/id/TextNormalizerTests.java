package io.github.kangwooju.doclens.pipeline.global.id;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("텍스트 정규화와 UTF-8 처리")
class TextNormalizerTests {

    @Test
    @DisplayName("CRLF와 CR은 LF, 결합 문자는 NFC")
    void normalizesLineEndingsAndComposition() {

        assertEquals(
                "a\nb\nc\né",
                TextNormalizer
                        .normalize("a\r\nb\rc\né")
        );
    }

    @Test
    @DisplayName("코드 들여쓰기, 탭, 연속 공백, 마지막 줄바꿈 보존")
    void keepsWhitespace() {

        String code = "  if (x) {\n\treturn  y;\n}\n";

        assertEquals(
                code,
                TextNormalizer
                        .normalize(code)
        );
    }

    @Test
    @DisplayName("짝 없는 surrogate 거절")
    void rejectsUnpairedSurrogates() {

        assertThrows(
                IllegalArgumentException.class,
                () -> TextNormalizer
                        .normalize("a\udc00")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> TextNormalizer
                        .encodeUtf8("\ud83d")
        );
    }

    @Test
    @DisplayName("UTF-8 바이트 수는 실제 인코딩 길이와 같음")
    void countsUtf8Bytes() {

        List<String> samples = List
                .of(
                        "",
                        "spring.jpa.open-in-view",
                        "é",
                        "한글 문단",
                        "😀 emoji",
                        "€"
                );

        for (String sample : samples) {
            assertEquals(
                    sample
                            .getBytes(StandardCharsets.UTF_8).length,
                    TextNormalizer
                            .utf8Length(sample),
                    sample
            );
            assertArrayEquals(
                    sample
                            .getBytes(StandardCharsets.UTF_8),
                    TextNormalizer
                            .encodeUtf8(sample)
            );
        }
    }

    @Test
    @DisplayName("잘못된 UTF-8 바이트는 대체 문자 없이 거절")
    void rejectsMalformedUtf8() throws CharacterCodingException {

        assertEquals(
                "가",
                TextNormalizer
                        .decodeUtf8(new byte[] {(byte) 0xea, (byte) 0xb0, (byte) 0x80})
        );
        assertThrows(
                CharacterCodingException.class,
                () -> TextNormalizer
                        .decodeUtf8(new byte[] {(byte) 0xea, (byte) 0xb0})
        );
    }
}
