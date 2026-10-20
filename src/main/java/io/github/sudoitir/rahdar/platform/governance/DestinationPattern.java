package io.github.sudoitir.rahdar.platform.governance;

import java.util.regex.Pattern;

/**
 * An Artemis wildcard address pattern: {@code .} separates words, {@code *} is one word, {@code #}
 * is zero or more words. {@code orders.#} matches {@code orders} and {@code orders.eu.north}.
 */
final class DestinationPattern {

    private DestinationPattern() {}

    static Pattern compile(String pattern) {
        String[] words = pattern.split("\\.", -1);
        if (words.length == 1 && words[0].equals("#")) {
            return Pattern.compile("^.*$");
        }
        StringBuilder regex = new StringBuilder("^");
        boolean separatorAbsorbed = false;
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (word.equals("#")) {
                // A leading # swallows its trailing separator; any other # swallows its leading one.
                regex.append(i == 0 ? "(?:[^.]+\\.)*" : "(?:\\.[^.]+)*");
                separatorAbsorbed = i == 0;
                continue;
            }
            if (i > 0 && !separatorAbsorbed) {
                regex.append("\\.");
            }
            separatorAbsorbed = false;
            regex.append(word.equals("*") ? "[^.]+" : Pattern.quote(word));
        }
        return Pattern.compile(regex.append("$").toString());
    }

    /** A property, header or body-path glob: {@code *} is any run of characters, case-insensitive. */
    static Pattern glob(String glob) {
        StringBuilder regex = new StringBuilder("^");
        String[] parts = glob.split("\\*", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(parts[i]));
        }
        return Pattern.compile(regex.append("$").toString(), Pattern.CASE_INSENSITIVE);
    }
}
