package io.github.kangwooju.doclens.pipeline.global.store;

import java.nio.file.Path;
import java.util.Optional;

/** object key의 bytes 읽기와 한 번만 쓰는 게시, 로컬 디렉터리와 S3 두 구현 */
public interface ObjectStore {

    /** object key의 bytes, 없으면 빈 값, 권한 거부와 timeout은 예외 */
    Optional<byte[]> read(String objectKey);

    /** 파일을 object key에 한 번만 게시, 같은 checksum이면 채택, 다르면 IllegalStateException */
    void publish(
            String objectKey,
            Path file,
            String checksum
    );

    /** object key가 있고 checksum이 같으면 true */
    boolean contains(
            String objectKey,
            String checksum
    );
}
