package io.github.kangwooju.doclens.pipeline.parse.dto;

import java.util.List;

/** 표 행과 header 여부 */
public record TableRow(
        boolean header,
        List<TableCell> cells,
        String domPath
) {

    /** 셀 목록 불변 사본 */
    public TableRow {
        cells = List
                .copyOf(cells);
    }
}
