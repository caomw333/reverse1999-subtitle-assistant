package com.local.reverse1999.subtitles;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CetVocabulary {
    private static final Pattern TOKEN = Pattern.compile(
            "(?<![\\p{L}\\p{N}])[A-Za-z]+(?:['’][A-Za-z]+)*(?![\\p{L}\\p{N}])");
    private final Set<String> schoolWords = new HashSet<>();
    private final Map<String, String> targetWords = new HashMap<>();
    private final Map<String, String> meanings = new HashMap<>();

    CetVocabulary(Reader source) throws IOException {
        try (BufferedReader reader = new BufferedReader(source)) {
            String line;
            while ((line = reader.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab < 0) throw new IOException("Invalid vocabulary row");
                String category = line.substring(0, tab);
                int definition = line.indexOf('\t', tab + 1);
                String word = definition < 0 ? line.substring(tab + 1) : line.substring(tab + 1, definition);
                if (category.equals("school")) schoolWords.add(word);
                else {
                    targetWords.put(word, category);
                    if (definition >= 0) meanings.put(word, line.substring(definition + 1));
                }
            }
        }
        targetWords.keySet().removeAll(schoolWords);
    }

    Set<String> words() {
        return targetWords.keySet();
    }

    String level(String word) {
        return targetWords.get(word);
    }

    String meaning(String word) {
        return meanings.getOrDefault(word, "");
    }

    List<Match> matches(String text) {
        List<Match> results = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group().toLowerCase(Locale.ROOT).replace('’', '\'');
            String word = targetWord(token);
            if (word != null) results.add(new Match(word, matcher.start(), matcher.end()));
        }
        return results;
    }

    private String targetWord(String token) {
        if (token.endsWith("'s")) token = token.substring(0, token.length() - 2);
        if (schoolWords.contains(token)) return null;
        String target = targetWords.containsKey(token) ? token : null;
        for (String form : baseForms(token)) {
            if (schoolWords.contains(form)) return null;
            if (target == null && targetWords.containsKey(form)) target = form;
        }
        return target;
    }

    private static List<String> baseForms(String word) {
        List<String> forms = new ArrayList<>(4);
        if (word.length() <= 3 || word.indexOf('\'') >= 0) return forms;
        if (word.endsWith("ies") || word.endsWith("ied")) {
            forms.add(word.substring(0, word.length() - 3) + "y");
        }
        if (word.endsWith("s") && !word.endsWith("ss")) {
            forms.add(word.substring(0, word.length() - 1));
            if (word.endsWith("es")) forms.add(word.substring(0, word.length() - 2));
        }
        int suffix = word.endsWith("ing") ? 3 : word.endsWith("ed") ? 2 : 0;
        if (suffix != 0 && word.length() - suffix >= 3) {
            String stem = word.substring(0, word.length() - suffix);
            forms.add(stem);
            forms.add(stem + "e");
            int end = stem.length() - 1;
            if (stem.charAt(end) == stem.charAt(end - 1)) forms.add(stem.substring(0, end));
        }
        return forms;
    }

    static final class Match {
        final String word;
        final int start;
        final int end;

        Match(String word, int start, int end) {
            this.word = word;
            this.start = start;
            this.end = end;
        }
    }
}
