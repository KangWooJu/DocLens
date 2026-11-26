package io.github.kangwooju.doclens.scheduler.handoff.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.kangwooju.doclens.scheduler.handoff.dto.PackScope;
import io.github.kangwooju.doclens.testsupport.FakePeers;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("readyRun에서 processRequest 변환")
class ProcessRequestConverterTests {

    private static final JsonMapper JSON_MAPPER = JsonMapper
            .builder()
            .build();

    private final ProcessRequestConverter converter = new ProcessRequestConverter();

    @Test
    @DisplayName("계약 예시 readyRun 변환 결과가 같은 폴더 processRequest 예시와 같음")
    void matchesContractExample() throws IOException {

        String readyRun = Files
                .readString(FakePeers
                        .contractsExample("root100/ready-run.json"));
        String expected = Files
                .readString(FakePeers
                        .contractsExample("root100/process-request.json"));
        UUID exampleId = UUID
                .fromString("11111111-1111-4111-8111-111111111111");

        String actual = converter
                .toProcessRequest(
                        readyRun,
                        exampleId,
                        exampleId,
                        "sha256:" + "a"
                                .repeat(64),
                        0,
                        Instant
                                .parse("2026-10-24T00:00:00Z")
                );

        assertEquals(
                JSON_MAPPER
                        .readTree(expected),
                JSON_MAPPER
                        .readTree(actual)
        );
    }

    @Test
    @DisplayName("발행 번호 조회 범위는 첫 projection의 project와 exactVersion")
    void readsPackScope() throws IOException {

        String readyRun = Files
                .readString(FakePeers
                        .contractsExample("root100/ready-run.json"));

        assertEquals(
                new PackScope(
                        "spring-boot",
                        "4.0.0"
                ),
                converter
                        .packScope(readyRun)
        );
    }
}
