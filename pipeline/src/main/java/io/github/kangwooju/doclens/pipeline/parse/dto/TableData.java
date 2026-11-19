package io.github.kangwooju.doclens.pipeline.parse.dto;

import java.util.List;

/** 표 caption과 원래 순서의 행 */
public record TableData(
        String caption,
        List<TableRow> rows
) {

    /** 행 목록 불변 사본 */
    public TableData {
        rows = List
                .copyOf(rows);
    }
}
