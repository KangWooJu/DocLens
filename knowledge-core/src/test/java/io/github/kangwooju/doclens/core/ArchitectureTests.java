package io.github.kangwooju.doclens.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/** 패키지별 클래스 종류 검사 */
@DisplayName("knowledge-core 패키지 규칙")
class ArchitectureTests {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.github.kangwooju.doclens.core");

    @Test
    @DisplayName("dto 패키지에는 record만, 최상위 record는 dto 패키지에만 위치")
    void dtosAreRecords() {

        classes()
                .that()
                .resideInAPackage("..dto..")
                .should()
                .beRecords()
                .check(CLASSES);
        classes()
                .that()
                .areTopLevelClasses()
                .and()
                .areRecords()
                .should()
                .resideInAPackage("..dto..")
                .check(CLASSES);
    }

    @Test
    @DisplayName("enum은 type 패키지에만 위치")
    void enumsLiveInTypePackage() {

        classes()
                .that()
                .areEnums()
                .should()
                .resideInAPackage("..type..")
                .check(CLASSES);
        classes()
                .that()
                .resideInAPackage("..type..")
                .and()
                .areTopLevelClasses()
                .should()
                .beEnums()
                .check(CLASSES);
    }

    @Test
    @DisplayName("예외는 exception 패키지에만 위치")
    void exceptionsLiveInExceptionPackage() {

        classes()
                .that()
                .areAssignableTo(Throwable.class)
                .should()
                .resideInAPackage("..exception..")
                .check(CLASSES);
        classes()
                .that()
                .resideInAPackage("..exception..")
                .and()
                .areTopLevelClasses()
                .should()
                .beAssignableTo(Throwable.class)
                .check(CLASSES);
    }
}
