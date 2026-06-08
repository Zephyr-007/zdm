package lx.model;

import org.apache.commons.lang3.StringUtils;

public enum SearchSortMode {
    SCORE("score"),
    TIME("time");

    private final String value;

    SearchSortMode(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static SearchSortMode from(String value) {
        if (StringUtils.isBlank(value))
            return SCORE;
        switch (value.trim().toLowerCase()) {
            case "score":
                return SCORE;
            case "time":
                return TIME;
            default:
                throw new IllegalArgumentException("未知的searchSortMode配置:" + value + ", 可选值: score/time");
        }
    }
}
