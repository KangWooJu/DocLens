package io.github.kangwooju.doclens.core.pack.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Pack 파일 공통 처리 */
public final class PackFiles {

    // 정적 메서드 전용
    private PackFiles() {
    }

    /** 파일 전체 SHA-256(`sha256:` 접두어) */
    public static String sha256(Path path) {

        try {
            MessageDigest digest = MessageDigest
                    .getInstance("SHA-256");

            try (InputStream in = new DigestInputStream(
                    Files
                            .newInputStream(path),
                    digest
            )) {
                in
                        .transferTo(OutputStream
                                .nullOutputStream());
            }

            return "sha256:" + HexFormat
                    .of()
                    .formatHex(digest
                            .digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "cannot read " + path,
                    exception
            );
        }
    }
}
