package io.github.kangwooju.doclens.pipeline.pack.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** object key를 로컬 디렉터리 아래 파일로 다루는 저장소, E에서 S3 구현과 짝을 이룸 */
@Component
public class LocalObjectStore {

    private final Path root;

    /** object key의 기준 디렉터리 */
    public LocalObjectStore(@Value("${doclens.pipeline.object-root}") Path root) {

        this.root = root
                .toAbsolutePath()
                .normalize();
    }

    /** object key의 bytes, 파일이 없으면 빈 값 */
    public Optional<byte[]> read(String objectKey) {

        try {
            return Optional
                    .of(Files
                            .readAllBytes(resolve(objectKey)));
        } catch (NoSuchFileException exception) {
            return Optional
                    .empty();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** object key의 파일 경로, 기준 디렉터리 밖이면 IllegalArgumentException */
    public Path resolve(String objectKey) {

        Path path = root
                .resolve(objectKey)
                .normalize();

        if (!path
                .startsWith(root)) {
            throw new IllegalArgumentException("object key escapes the object root: " + objectKey);
        }

        return path;
    }
}
