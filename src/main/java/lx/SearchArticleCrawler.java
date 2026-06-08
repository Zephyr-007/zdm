package lx;

import java.net.HttpCookie;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import cn.hutool.core.io.IORuntimeException;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.http.Header;
import cn.hutool.http.HttpException;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpUtil;
import lx.model.SearchSortMode;
import lx.model.Zdm;
import lx.utils.Utils;
import lx.utils.WatchWordConfig;

import static lx.utils.Const.MAX_RETRY;

public class SearchArticleCrawler {

    private static final String SEARCH_URL_TEMPLATE = "https://search.smzdm.com/?c=faxian&s=%s&order=%s&v=b&mx_v=b&p=%d";
    private static final Pattern ARTICLE_ID_PATTERN = Pattern.compile("/p/(\\d+)/");
    private static final Pattern MALL_PATTERN = Pattern.compile("dimension12':'([^']+)'");
    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}|\\d{2}:\\d{2})");

    private final CookieHeaderProvider cookieHeaderProvider;
    private final Runnable cookieCleaner;

    public SearchArticleCrawler(CookieHeaderProvider cookieHeaderProvider, Runnable cookieCleaner) {
        this.cookieHeaderProvider = cookieHeaderProvider;
        this.cookieCleaner = cookieCleaner;
    }

    public List<Zdm> obtainSearchArticles(int maxPageSize, SearchSortMode sortMode, int withinDays, boolean detail) {
        List<String> watchWords = WatchWordConfig.readWatchWords();
        if (watchWords.isEmpty()) {
            System.out.println("watch_words.txt为空,跳过搜索关注抓取");
            return new ArrayList<>();
        }

        Map<String, Zdm> result = new LinkedHashMap<>();
        for (String keyword : watchWords) {
            for (int page = 1; page <= maxPageSize; page++) {
                List<Zdm> articles = filterRecentArticles(crawlSearchPage(keyword, sortMode, page, MAX_RETRY), withinDays);
                articles.forEach(article -> result.putIfAbsent(article.getArticleId(), article));
                System.out.println("关键词[" + keyword + "]第" + page + "页搜索数据获取成功, 当前页数据条数" + articles.size());
                ThreadUtil.sleep(ThreadLocalRandom.current().nextInt(1000, 2000));
            }
        }
        if (detail) {
            System.out.println("搜索关注抓取到的优惠信息:");
            result.values().forEach(z -> System.out.println(z.getArticleId() + " | " + z.getTitle()));
        }
        return new ArrayList<>(result.values());
    }

    private List<Zdm> crawlSearchPage(String keyword, SearchSortMode sortMode, int page, int retry) {
        String url = buildSearchUrl(keyword, sortMode, page);
        try {
            HttpRequest request = HttpUtil.createGet(url)
                    .header(Header.COOKIE, cookieHeaderProvider.buildCookieHeader())
                    .header(Header.USER_AGENT, Utils.ramdomUserAgent())
                    .header(Header.REFERER, "https://search.smzdm.com/")
                    .header(Header.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                    .header(Header.ACCEPT_LANGUAGE, "zh-CN,zh;q=0.9,en;q=0.8")
                    .header(Header.CONNECTION, "keep-alive")
                    .timeout(15000);

            String html = request.execute().body();
            if (html.contains("probe.js") && !html.contains("feed-row-wide"))
                throw new HttpException("搜索页触发WAF探针,需要重新获取cookie");
            return parseSearchHtml(html);
        } catch (IORuntimeException | HttpException | TimeoutException |
                 org.openqa.selenium.TimeoutException e) {
            if (retry > 0) {
                int minutes = (MAX_RETRY - retry + 1);
                System.out.println("搜索页调用失败,等待" + minutes + "分钟后进行重试,剩余重试次数:" + retry);
                ThreadUtil.sleep((long) minutes * 60 * 1000);
                cookieCleaner.run();
                return crawlSearchPage(keyword, sortMode, page, retry - 1);
            }
            e.printStackTrace();
            throw new RuntimeException("搜索页调用失败,程序终止");
        }
    }

    private String buildSearchUrl(String keyword, SearchSortMode sortMode, int page) {
        return String.format(SEARCH_URL_TEMPLATE, URLEncoder.encode(keyword, StandardCharsets.UTF_8), sortMode.getValue(), page);
    }

    private List<Zdm> filterRecentArticles(List<Zdm> articles, int withinDays) {
        if (withinDays <= 0)
            return articles;

        LocalDateTime threshold = LocalDateTime.now(ZoneId.of("GMT+8")).minus(withinDays, ChronoUnit.DAYS);
        List<Zdm> filtered = new ArrayList<>();
        for (Zdm article : articles) {
            LocalDateTime articleTime = LocalDateTime.parse(article.getArticle_time());
            if (!articleTime.isBefore(threshold))
                filtered.add(article);
        }
        return filtered;
    }

    private List<Zdm> parseSearchHtml(String html) {
        Document document = Jsoup.parse(html, "https://search.smzdm.com/");
        List<Zdm> articles = new ArrayList<>();
        for (Element row : document.select(".feed-row-wide")) {
            Zdm zdm = parseRow(row);
            if (zdm != null)
                articles.add(zdm);
        }
        return articles;
    }

    private Zdm parseRow(Element row) {
        Element titleLink = row.selectFirst(".feed-block-title a.feed-nowrap[href*=/p/]");
        if (titleLink == null)
            titleLink = row.selectFirst("a[href*=/p/][title]");
        if (titleLink == null)
            return null;

        String articleId = parseArticleId(titleLink.attr("abs:href"));
        if (StringUtils.isBlank(articleId))
            return null;

        Zdm zdm = new Zdm();
        zdm.setArticleId(articleId);
        zdm.setTitle(StringUtils.defaultIfBlank(titleLink.attr("title"), titleLink.text()).trim());
        zdm.setUrl(titleLink.attr("abs:href"));
        zdm.setPicUrl(normalizeUrl(row.selectFirst(".z-feed-img img") == null ? "" : row.selectFirst(".z-feed-img img").attr("src")));
        zdm.setPrice(StringUtils.trimToEmpty(row.select(".z-highlight").text()));
        zdm.setVoted(Utils.strNumberFormat(StringUtils.defaultIfBlank(row.select(".price-btn-up .unvoted-wrap span").text(), "0").trim()));
        zdm.setComments(Utils.strNumberFormat(StringUtils.defaultIfBlank(row.select(".feed-btn-comment").text(), "0").trim()));
        zdm.setArticleMall(parseMall(row));
        zdm.setArticle_time(parseArticleTime(row.text()));
        return zdm;
    }

    private String parseArticleId(String url) {
        Matcher matcher = ARTICLE_ID_PATTERN.matcher(url);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String normalizeUrl(String url) {
        if (StringUtils.isBlank(url))
            return "";
        if (url.startsWith("//"))
            return "https:" + url;
        return url;
    }

    private String parseMall(Element row) {
        Matcher matcher = MALL_PATTERN.matcher(row.html());
        if (matcher.find())
            return matcher.group(1);

        String[] lines = row.text().split("\\s+");
        return lines.length == 0 ? "" : lines[lines.length - 1];
    }

    private String parseArticleTime(String text) {
        ZoneId zoneId = ZoneId.of("GMT+8");
        LocalDateTime now = LocalDateTime.now(zoneId);
        Matcher matcher = TIME_PATTERN.matcher(text);
        String timeText = "";
        while (matcher.find())
            timeText = matcher.group(1);
        if (StringUtils.isBlank(timeText))
            return now.toString();

        if (timeText.contains("-")) {
            MonthDay monthDay = MonthDay.parse("--" + timeText.substring(0, 5));
            LocalTime time = LocalTime.parse(timeText.substring(6));
            LocalDateTime parsed = LocalDate.of(now.getYear(), monthDay.getMonth(), monthDay.getDayOfMonth()).atTime(time);
            if (parsed.isAfter(now.plusDays(1)))
                parsed = parsed.minusYears(1);
            return parsed.toString();
        }

        return LocalDate.now(zoneId).atTime(LocalTime.parse(timeText)).toString();
    }

    public interface CookieHeaderProvider {
        String buildCookieHeader() throws TimeoutException;
    }
}
