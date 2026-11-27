package io.github.kangwooju.doclens.pipeline.global.id;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

/** 줄바꿈 LF 통일, NFC 정규화, 엄격한 UTF-8 변환과 바이트 수 계산 */
public final class TextNormalizer {

    // 인스턴스 생성 금지
    private TextNormalizer() {
    }

    /** CRLF와 CR을 LF로 바꾼 뒤 NFC 적용, 짝 없는 surrogate는 거절 */
    public static String normalize(String text) {

        requireWellFormed(text);

        String lineFeeds = text
                .replace(
                        "\r\n",
                        "\n"
                )
                .replace(
                        '\r',
                        '\n'
                );

        if (Normalizer
                .isNormalized(
                        lineFeeds,
                        Normalizer.Form.NFC
                )) {
            return lineFeeds;
        }

        return Normalizer
                .normalize(
                        lineFeeds,
                        Normalizer.Form.NFC
                );
    }

    /** 잘못된 바이트를 대체 문자로 바꾸지 않는 UTF-8 해석 */
    public static String decodeUtf8(byte[] bytes) throws CharacterCodingException {

        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer
                        .wrap(bytes))
                .toString();
    }

    /** 짝 없는 surrogate를 '?'로 바꾸지 않는 UTF-8 인코딩 */
    public static byte[] encodeUtf8(String text) {

        requireWellFormed(text);

        try {
            ByteBuffer buffer = StandardCharsets.UTF_8
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer
                            .wrap(text));
            byte[] bytes = new byte[buffer
                    .remaining()];
            buffer
                    .get(bytes);

            return bytes;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(
                    "text is not valid UTF-16",
                    exception
            );
        }
    }

    /** 배열을 만들지 않고 세는 UTF-8 바이트 수 */
    public static int utf8Length(CharSequence text) {

        int length = text
                .length();
        int bytes = 0;

        for (int index = 0; index < length; index++) {
            char current = text
                    .charAt(index);

            if (current < 0x80) {
                bytes += 1;
            } else if (current < 0x800) {
                bytes += 2;
            } else if (Character
                    .isHighSurrogate(current)) {
                bytes += 4;
                index++;
            } else {
                bytes += 3;
            }
        }

        return bytes;
    }

    /** 짝 없는 surrogate가 있으면 IllegalArgumentException */
    public static void requireWellFormed(String text) {

        int length = text
                .length();

        for (int index = 0; index < length; index++) {
            char current = text
                    .charAt(index);

            if (Character
                    .isHighSurrogate(current)) {
                boolean paired = index + 1 < length && Character
                        .isLowSurrogate(text
                                .charAt(index + 1));

                if (!paired) {
                    throw new IllegalArgumentException("unpaired surrogate at index " + index);
                }

                index++;
            } else if (Character
                    .isLowSurrogate(current)) {
                throw new IllegalArgumentException("unpaired surrogate at index " + index);
            }
        }
    }
}
