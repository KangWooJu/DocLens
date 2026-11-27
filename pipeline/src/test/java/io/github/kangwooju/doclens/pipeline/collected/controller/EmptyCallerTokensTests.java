package io.github.kangwooju.doclens.pipeline.collected.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.kangwooju.doclens.pipeline.global.security.InternalSecurityConfig;
import io.github.kangwooju.doclens.pipeline.pack.facade.PackBuildFacade;
import io.github.kangwooju.doclens.testsupport.CallerTokens;

@WebMvcTest(ProcessRequestController.class)
@Import(InternalSecurityConfig.class)
@DisplayName("호출자 토큰 설정이 비면 문맥은 뜨고 모든 내부 경로 401")
class EmptyCallerTokensTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PackBuildFacade facade;

    @Test
    @DisplayName("빈 토큰 목록에서 어떤 bearer 토큰도 401")
    void rejectsEveryToken() throws Exception {

        assertEquals(
                401,
                mockMvc
                        .perform(get("/internal/v1/publications/latest")
                                .param(
                                        "project",
                                        "spring-boot"
                                )
                                .param(
                                        "exactVersion",
                                        "4.1.2"
                                )
                                .header(
                                        "Authorization",
                                        "Bearer " + CallerTokens
                                                .newToken()
                                ))
                        .andReturn()
                        .getResponse()
                        .getStatus()
        );
    }
}
