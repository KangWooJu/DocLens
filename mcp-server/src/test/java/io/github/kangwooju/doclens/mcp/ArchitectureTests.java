package io.github.kangwooju.doclens.mcp;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noCodeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

/** 계층 규칙 검사, global 패키지는 제외 */
@DisplayName("mcp-server 계층 규칙")
class ArchitectureTests {

    private static final String BASE = "io.github.kangwooju.doclens.mcp";

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE);

    private static final String[] HELPER_PACKAGES = {
            "..helper..",
            "..handler..",
            "..policy..",
            "..repository..",
            "..store..",
            "..converter.."
    };

    private static final Set<String> CONVERTER_USERS = Set
            .of(
                    "..handler..",
                    "..repository..",
                    "..store.."
            );

    @Test
    @DisplayName("A0 패키지와 이름 접미사 일치")
    void packageMatchesNameSuffix() {

        List<String[]> layers = List
                .of(
                        new String[] {"..tool..", "Tool"},
                        new String[] {"..facade..", "Facade"},
                        new String[] {"..service..", "Service"},
                        new String[] {"..policy..", "Policy"},
                        new String[] {"..repository..", "Repository"},
                        new String[] {"..store..", "Store"},
                        new String[] {"..handler..", "Handler"},
                        new String[] {"..helper..", "Helper"},
                        new String[] {"..converter..", "Converter"}
                );

        for (String[] layer : layers) {
            check(classes()
                    .that()
                    .resideInAPackage(layer[0])
                    .and()
                    .areTopLevelClasses()
                    .should()
                    .haveSimpleNameEndingWith(layer[1]));
        }
    }

    @Test
    @DisplayName("A1 Tool 진입부는 Facade, DTO, 공통, 프레임워크에만 의존")
    void toolEntryPointsDependOnFacadeOnly() {

        check(classes()
                .that()
                .resideInAPackage("..tool..")
                .should()
                .onlyDependOnClassesThat()
                .resideInAnyPackage(
                        "..tool..",
                        "..facade..",
                        "..dto..",
                        BASE + ".global..",
                        "java..",
                        "org.springframework..",
                        "io.modelcontextprotocol..",
                        "lombok.."
                ));
    }

    @Test
    @DisplayName("A2 위에서 아래로만 의존")
    void dependenciesPointDownward() {

        check(noClasses()
                .that()
                .resideInAPackage("..facade..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..tool.."));
        check(noClasses()
                .that()
                .resideInAPackage("..service..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "..tool..",
                        "..facade.."
                ));
        check(noClasses()
                .that()
                .resideInAPackage("..service..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..service.."));
        check(noClasses()
                .that()
                .resideInAnyPackage(HELPER_PACKAGES)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "..tool..",
                        "..facade..",
                        "..service.."
                ));
    }

    @Test
    @DisplayName("A3 Facade 간 의존 금지")
    void facadesDoNotDependOnFacades() {

        check(noClasses()
                .that()
                .resideInAPackage("..facade..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..facade.."));
    }

    @Test
    @DisplayName("A4 Policy의 저장소, 외부 클라이언트 의존 금지")
    void policiesStayPure() {

        check(noClasses()
                .that()
                .resideInAPackage("..policy..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "..repository..",
                        "..store..",
                        "..handler..",
                        "java.sql..",
                        "org.springframework.jdbc.."
                ));
    }

    @Test
    @DisplayName("A5 dto 패키지에는 record만, 최상위 record는 dto 패키지에만 위치")
    void dtosAreRecords() {

        check(classes()
                .that()
                .resideInAPackage("..dto..")
                .should()
                .beRecords());
        check(classes()
                .that()
                .areTopLevelClasses()
                .and()
                .areRecords()
                .should()
                .resideInAPackage("..dto.."));
    }

    @Test
    @DisplayName("A6 @Autowired 금지, Spring bean 주입 필드는 private final")
    void usesConstructorInjection() {

        check(noFields()
                .should()
                .beAnnotatedWith(Autowired.class));
        check(noCodeUnits()
                .should()
                .beAnnotatedWith(Autowired.class));
        check(fields()
                .that()
                .areDeclaredInClassesThat()
                .areMetaAnnotatedWith(Component.class)
                .and()
                .areNotStatic()
                .should()
                .bePrivate()
                .andShould()
                .beFinal());
    }

    @Test
    @DisplayName("A7 TransactionTemplate, PlatformTransactionManager 직접 사용 금지")
    void noDirectTransactionApis() {

        check(noClasses()
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.transaction.support.TransactionTemplate")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.transaction.PlatformTransactionManager"));
    }

    @Test
    @DisplayName("A8 보조 객체 간 의존 금지(Handler, Repository, Store의 Converter 사용만 허용)")
    void helpersDoNotDependOnEachOther() {

        for (String helper : HELPER_PACKAGES) {
            boolean mayUseConverter = CONVERTER_USERS
                    .contains(helper);
            String[] forbidden = Stream
                    .of(HELPER_PACKAGES)
                    .filter(other -> !other
                            .equals(helper))
                    .filter(other -> !(mayUseConverter && other
                            .equals("..converter..")))
                    .toArray(String[]::new);

            check(noClasses()
                    .that()
                    .resideInAPackage(helper)
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(forbidden));
        }
    }

    @Test
    @DisplayName("A9 enum은 type 패키지에만 위치")
    void enumsLiveInTypePackage() {

        check(classes()
                .that()
                .areEnums()
                .should()
                .resideInAPackage("..type.."));
        check(classes()
                .that()
                .resideInAPackage("..type..")
                .and()
                .areTopLevelClasses()
                .should()
                .beEnums());
    }

    @Test
    @DisplayName("A10 예외는 exception 패키지에만 위치")
    void exceptionsLiveInExceptionPackage() {

        check(classes()
                .that()
                .areAssignableTo(Throwable.class)
                .should()
                .resideInAPackage("..exception.."));
        check(classes()
                .that()
                .resideInAPackage("..exception..")
                .and()
                .areTopLevelClasses()
                .should()
                .beAssignableTo(Throwable.class));
    }

    // 대상 클래스가 아직 없는 계층도 통과시키는 규칙 검사
    private static void check(ArchRule rule) {

        rule
                .allowEmptyShould(true)
                .check(CLASSES);
    }
}
