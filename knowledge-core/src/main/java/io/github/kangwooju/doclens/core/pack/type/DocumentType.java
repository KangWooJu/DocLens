package io.github.kangwooju.doclens.core.pack.type;

import java.util.List;
import java.util.stream.Stream;

/** Pack과 Tool에서 쓰는 문서 유형 */
public enum DocumentType {

    REFERENCE("reference"),
    JAVADOC("javadoc"),
    PROPERTIES("properties"),
    DEPENDENCIES("dependencies"),
    GUIDE("guide"),
    MIGRATION("migration"),
    RELEASE_NOTE("release-note"),
    PROJECT_OVERVIEW("project-overview"),
    EXAMPLE("example"),
    CONFIG_METADATA("config-metadata");

    private final String wireValue;

    // Pack 저장 값 지정
    DocumentType(String wireValue) {

        this.wireValue = wireValue;
    }

    /** Pack에 저장하는 값 */
    public String wireValue() {

        return wireValue;
    }

    /** 모든 유형의 Pack 저장 값 */
    public static List<String> wireValues() {

        return Stream
                .of(values())
                .map(DocumentType::wireValue)
                .toList();
    }
}
