package com.local.reverse1999.subtitles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class VocabularyCheck {
    public static void main(String[] args) throws Exception {
        CetVocabulary vocabulary = new CetVocabulary(Files.newBufferedReader(
                Path.of("app/src/main/assets/vocabulary/cet.tsv")));
        assert vocabulary.words().size() == 3553;
        assert vocabulary.meaning("subtle").matches("(?s).*[\\u4e00-\\u9fff].*");
        String sentence = "The storm and abandoned school. Subtle, subsequent; subtle. Endeavors.";
        List<CetVocabulary.Match> hits = vocabulary.matches(sentence);
        assert hits.size() == 4 : hits.size();
        assert hits.get(0).word.equals("subtle");
        assert sentence.substring(hits.get(0).start, hits.get(0).end).equals("Subtle");
        assert hits.get(1).word.equals("subsequent");
        assert hits.get(2).word.equals("subtle");
        assert hits.get(3).word.equals("endeavor");
        assert vocabulary.matches("THE and of be leaves school leaving abandoned storm").isEmpty();
        System.out.println("Verified vocabulary sources, school exclusions, word forms, and repeated occurrences.");
    }
}
