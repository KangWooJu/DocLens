package io.github.kangwooju.doclens.pipeline.parse.type;

/** 순회 중 구조로 표현하지 못한 요소의 종류 */
public enum StructureIssueType {
    CONTENT_BEFORE_HEADING,
    NESTED_TAB_GROUP,
    TAB_GROUP_UNKNOWN_CHILD,
    TAB_GROUP_WITHOUT_TABS,
    TAB_PANEL_MISMATCH,
    BLOCK_IN_LIST,
    BLOCK_IN_TABLE_CELL
}
