package lx.model;

import org.apache.commons.lang3.StringUtils;

public enum CrawlMode {
    RANKING,
    SEARCH,
    BOTH;

    public static CrawlMode from(String value) {
        if (StringUtils.isBlank(value))
            return RANKING;
        switch (value.trim().toLowerCase()) {
            case "ranking":
                return RANKING;
            case "search":
                return SEARCH;
            case "both":
                return BOTH;
            default:
                throw new IllegalArgumentException("未知的crawlMode配置:" + value + ", 可选值: ranking/search/both");
        }
    }

    public boolean enableRanking() {
        return this == RANKING || this == BOTH;
    }

    public boolean enableSearch() {
        return this == SEARCH || this == BOTH;
    }
}
