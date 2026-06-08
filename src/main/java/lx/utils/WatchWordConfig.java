package lx.utils;

import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

public class WatchWordConfig {

    private static final String WATCH_WORDS_PATH = "./watch_words.txt";

    public static List<String> readWatchWords() {
        HashSet<String> words = Utils.readFile(WATCH_WORDS_PATH);
        return words.stream()
                .map(StringUtils::trimToEmpty)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());
    }
}
