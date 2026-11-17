package io.github.kangwooju.doclens.core.pack;

/** 코드 탭 그룹의 종류. */
public enum VariantGroupType {

    LANGUAGE("language"),
    BUILD_TOOL("build-tool"),
    GRADLE_DSL("gradle-dsl"),
    CONFIG_STYLE("config-style"),
    OTHER("other");

    private final String wireValue;

    VariantGroupType(String wireValue) {

        this.wireValue = wireValue;
    }

    /** Pack에 저장하는 값. */
    public String wireValue() {

        return wireValue;
    }
}
