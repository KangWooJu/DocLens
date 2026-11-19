package io.github.kangwooju.doclens.pipeline.parse.store;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.core.pack.type.DocumentType;
import io.github.kangwooju.doclens.core.pack.type.VariantGroupType;
import io.github.kangwooju.doclens.pipeline.parse.dto.ParserProfile;
import io.github.kangwooju.doclens.pipeline.parse.dto.TabSelectors;
import io.github.kangwooju.doclens.pipeline.parse.type.ApprovalState;
import io.github.kangwooju.doclens.pipeline.parse.type.DefaultTabRule;
import io.github.kangwooju.doclens.pipeline.parse.type.VersionMarkerRule;

/** 등록된 파서 프로필 목록과 문서에 맞는 프로필 조회 */
@Component
public class ParserProfileStore {

    /**
     * Spring Boot Antora Reference 프로필, 선택자는 2026-11-20 실제 페이지 조사 결과
     * 사람이 실제 표본과 대조하기 전이라 CANDIDATE
     */
    public static final ParserProfile SPRING_BOOT_REFERENCE = new ParserProfile(
            "spring-antora-reference",
            "1",
            "spring-boot",
            DocumentType.REFERENCE,
            "https://docs\\.spring\\.io/spring-boot/(\\d+\\.\\d+(\\.\\d+)?/)?reference/.+\\.html",
            "article.doc",
            List
                    .of(
                            "div.breadcrumbs-container",
                            "div.admonitionblock.latest",
                            "div.section-summary",
                            "nav.pagination",
                            "p.page-author"
                    ),
            List
                    .of(
                            "h1#page-title",
                            "div.sect1 > h2",
                            "div.sect2 > h3",
                            "div.sect3 > h4",
                            "div.sect4 > h5",
                            "div.sect5 > h6"
                    ),
            new TabSelectors(
                    "div.openblock.tabs",
                    "li.tab"
            ),
            VersionMarkerRule.ANTORA_META_VERSION,
            List
                    .of(),
            ApprovalState.CANDIDATE,
            DefaultTabRule.FIRST_IN_SOURCE_ORDER,
            Map
                    .of(
                            "Java",
                            "java",
                            "Kotlin",
                            "kotlin",
                            "Groovy",
                            "groovy",
                            "Maven",
                            "maven",
                            "Gradle",
                            "gradle",
                            "Properties",
                            "properties",
                            "YAML",
                            "yaml"
                    ),
            Map
                    .of(
                            VariantGroupType.LANGUAGE,
                            Set
                                    .of(
                                            "java",
                                            "kotlin",
                                            "groovy"
                                    ),
                            VariantGroupType.BUILD_TOOL,
                            Set
                                    .of(
                                            "maven",
                                            "gradle"
                                    ),
                            VariantGroupType.CONFIG_STYLE,
                            Set
                                    .of(
                                            "properties",
                                            "yaml"
                                    )
                    )
    );

    private final List<ParserProfile> profiles;

    /** 기본 등록 프로필 */
    public ParserProfileStore() {

        this(List
                .of(SPRING_BOOT_REFERENCE));
    }

    /** 지정한 프로필 목록 */
    public ParserProfileStore(List<ParserProfile> profiles) {

        this.profiles = List
                .copyOf(profiles);
    }

    /** project, 문서 유형, 최종 URL이 모두 맞는 첫 프로필, 승인 상태는 보지 않음 */
    public Optional<ParserProfile> find(
            String project,
            DocumentType documentType,
            String finalUrl
    ) {

        return profiles
                .stream()
                .filter(profile -> profile
                        .project()
                        .equals(project))
                .filter(profile -> profile
                        .documentType() == documentType)
                .filter(profile -> Pattern
                        .compile(profile
                                .urlPattern())
                        .matcher(finalUrl)
                        .matches())
                .findFirst();
    }
}
