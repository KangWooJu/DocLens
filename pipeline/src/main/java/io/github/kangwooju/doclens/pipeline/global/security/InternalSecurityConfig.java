package io.github.kangwooju.doclens.pipeline.global.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AuthenticationEntryPointFailureHandler;
import org.springframework.security.web.authentication.AuthenticationFilter;

import io.github.kangwooju.doclens.pipeline.global.response.dto.ErrorBody;
import io.github.kangwooju.doclens.pipeline.global.response.type.ErrorCode;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * 내부 HTTP API의 호출자별 정적 bearer 인증과 경로 허용, 토큰 원본은 받지 않고 SHA-256 hex만 설정에 둠
 * servlet 웹 문맥에서만 구성해 웹 없는 실행은 Security 구성 없이 뜸, 토큰 설정이 비면 모든 내부 경로 401
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class InternalSecurityConfig {

    /** 처리 요청과 최신 발행 조회를 모두 부르는 호출자 */
    public static final String SCHEDULER = "scheduler";

    /** 최신 발행 조회만 부르는 사람 호출자 */
    public static final String OPERATOR = "operator";

    // 호출자 권한 이름 접두어
    private static final String CALLER = "CALLER_";

    // Authorization header의 bearer 접두어
    private static final String BEARER = "Bearer ";

    // 설정 항목 하나, 호출자:소문자 SHA-256 hex
    private static final Pattern CALLER_TOKEN = Pattern
            .compile("([a-z][a-z0-9-]*):([0-9a-f]{64})");

    private final JsonMapper jsonMapper;

    // STATELESS, CSRF 끔, bearer 인증 뒤 호출자별 허용 경로, health와 오류 dispatch만 무인증, 나머지 거부
    @Bean
    SecurityFilterChain internalApi(
            HttpSecurity http,
            @Value("${doclens.security.caller-tokens:}") List<String> callerTokens
    ) throws Exception {

        AuthenticationFilter bearer = new AuthenticationFilter(
                callerTokenManager(callerHashes(callerTokens)),
                InternalSecurityConfig::bearerToken
        );
        bearer
                .setSuccessHandler((request, response, authentication) -> {
                });
        bearer
                .setFailureHandler(new AuthenticationEntryPointFailureHandler(this::unauthorized));

        return http
                .csrf(csrf -> csrf
                        .disable())
                .httpBasic(basic -> basic
                        .disable())
                .formLogin(form -> form
                        .disable())
                .logout(logout -> logout
                        .disable())
                .requestCache(cache -> cache
                        .disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(this::unauthorized)
                        .accessDeniedHandler(this::forbidden))
                .addFilterBefore(
                        bearer,
                        AuthorizationFilter.class
                )
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers("/actuator/health/**")
                        .permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/internal/v1/processing-jobs"
                        )
                        .hasAuthority(CALLER + SCHEDULER)
                        .requestMatchers(
                                HttpMethod.GET,
                                "/internal/v1/publications/latest"
                        )
                        .hasAnyAuthority(
                                CALLER + SCHEDULER,
                                CALLER + OPERATOR
                        )
                        .anyRequest()
                        .denyAll())
                .build();
    }

    // 토큰이 없거나 틀린 요청의 401, WWW-Authenticate와 contracts error
    private void unauthorized(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException {

        response
                .setHeader(
                        HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer"
                );
        write(
                response,
                HttpStatus.UNAUTHORIZED,
                ErrorBody
                        .of(
                                ErrorCode.AUTH_REQUIRED,
                                "a registered bearer token is required",
                                false
                        )
        );
    }

    // 인증한 호출자에게 허용되지 않은 경로의 403 contracts error
    private void forbidden(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception
    ) throws IOException {

        write(
                response,
                HttpStatus.FORBIDDEN,
                ErrorBody
                        .of(
                                ErrorCode.FORBIDDEN,
                                "the caller may not use " + request
                                        .getMethod() + " " + request
                                                .getRequestURI(),
                                false
                        )
        );
    }

    // contracts error JSON 응답
    private void write(
            HttpServletResponse response,
            HttpStatus status,
            ErrorBody body
    ) throws IOException {

        response
                .setStatus(status
                        .value());
        response
                .setContentType(MediaType.APPLICATION_JSON_VALUE);
        jsonMapper
                .writeValue(
                        response
                                .getOutputStream(),
                        body
                );
    }

    // Authorization header의 bearer 토큰, 없거나 다른 방식이면 인증 시도 없이 다음 filter로
    private static Authentication bearerToken(HttpServletRequest request) {

        String header = request
                .getHeader(HttpHeaders.AUTHORIZATION);

        if (header == null || !header
                .regionMatches(
                        true,
                        0,
                        BEARER,
                        0,
                        BEARER
                                .length()
                )) {
            return null;
        }

        return UsernamePasswordAuthenticationToken
                .unauthenticated(
                        "bearer",
                        header
                                .substring(BEARER
                                        .length())
                                .strip()
                );
    }

    // 제시한 토큰의 SHA-256을 모든 항목과 조기 종료 없이 상수 시간 비교, 맞는 호출자의 권한 하나
    private static AuthenticationManager callerTokenManager(List<Map.Entry<String, byte[]>> hashes) {

        return authentication -> {
            byte[] presented = sha256(String
                    .valueOf(authentication
                            .getCredentials()));
            String caller = null;

            for (Map.Entry<String, byte[]> hash : hashes) {
                if (MessageDigest
                        .isEqual(
                                presented,
                                hash
                                        .getValue()
                        )) {
                    caller = hash
                            .getKey();
                }
            }

            if (caller == null) {
                throw new BadCredentialsException("bearer token is not registered");
            }

            return UsernamePasswordAuthenticationToken
                    .authenticated(
                            caller,
                            null,
                            AuthorityUtils
                                    .createAuthorityList(CALLER + caller)
                    );
        };
    }

    // 설정의 호출자와 SHA-256 bytes 목록, 같은 호출자 두 항목은 교체 기간, 형식이 틀리면 기동 실패
    private static List<Map.Entry<String, byte[]>> callerHashes(List<String> callerTokens) {

        List<Map.Entry<String, byte[]>> hashes = new ArrayList<>();

        for (String entry : callerTokens) {
            if (entry
                    .isBlank()) {
                continue;
            }

            Matcher matcher = CALLER_TOKEN
                    .matcher(entry
                            .strip());

            if (!matcher
                    .matches()) {
                throw new IllegalArgumentException("doclens.security.caller-tokens entries must be <caller>:<lowercase sha256 hex>");
            }

            hashes
                    .add(Map
                            .entry(
                                    matcher
                                            .group(1),
                                    HexFormat
                                            .of()
                                            .parseHex(matcher
                                                    .group(2))
                            ));
        }

        return hashes;
    }

    // 문자열 UTF-8 bytes의 SHA-256
    private static byte[] sha256(String value) {

        try {
            return MessageDigest
                    .getInstance("SHA-256")
                    .digest(value
                            .getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is required by the Java platform",
                    exception
            );
        }
    }
}
