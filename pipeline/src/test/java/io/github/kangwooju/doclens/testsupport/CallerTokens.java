package io.github.kangwooju.doclens.testsupport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** 시험 실행 중에만 만드는 bearer 토큰과 DOCLENS_CALLER_TOKENS 항목, 저장소에 값을 남기지 않음 */
public final class CallerTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    // 인스턴스 생성 금지
    private CallerTokens() {
    }

    /** 32 bytes 난수의 URL 안전 base64 토큰 */
    public static String newToken() {

        byte[] bytes = new byte[32];
        RANDOM
                .nextBytes(bytes);

        return Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);
    }

    /** 호출자와 토큰 SHA-256 hex의 설정 항목 */
    public static String entry(
            String caller,
            String token
    ) {

        try {
            return caller + ":" + HexFormat
                    .of()
                    .formatHex(MessageDigest
                            .getInstance("SHA-256")
                            .digest(token
                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
