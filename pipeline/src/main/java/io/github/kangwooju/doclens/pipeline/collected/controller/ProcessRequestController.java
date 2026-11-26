package io.github.kangwooju.doclens.pipeline.collected.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.github.kangwooju.doclens.pipeline.collected.dto.ProcessingReceipt;
import io.github.kangwooju.doclens.pipeline.pack.facade.PackBuildFacade;
import lombok.RequiredArgsConstructor;

/** scheduler의 처리 요청과 최신 발행 조회를 받는 내부 HTTP 진입점, 본문 해석과 처리는 Facade */
@RestController
@RequiredArgsConstructor
public class ProcessRequestController {

    private final PackBuildFacade packBuildFacade;

    /** contracts processRequest 본문을 동기로 처리해 processingRunResultReceipt 반환 */
    @PostMapping(
            path = "/internal/v1/processing-jobs",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ProcessingReceipt process(@RequestBody String body) {

        return packBuildFacade
                .process(body);
    }

    /** project와 정확 버전의 최신 publication manifest, 발행이 없으면 404 */
    @GetMapping(
            path = "/internal/v1/publications/latest",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<String> latest(
            @RequestParam String project,
            @RequestParam String exactVersion
    ) {

        return packBuildFacade
                .latestPublication(
                        project,
                        exactVersion
                )
                .map(manifest -> ResponseEntity
                        .ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(manifest))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "no publication for " + project + " " + exactVersion
                ));
    }
}
