package io.github.kangwooju.doclens.core.pack;

/** Pack·Tool의 소문자 문서 유형 10종(수집의 대문자 유형과 전수 대응). */
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

    DocumentType(String wireValue) {

        this.wireValue = wireValue;
    }

    /** Pack에 저장하는 값. */
    public String wireValue() {

        return wireValue;
    }
}
