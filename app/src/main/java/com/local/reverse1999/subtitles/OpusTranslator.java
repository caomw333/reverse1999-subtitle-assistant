package com.local.reverse1999.subtitles;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxTensorLike;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

final class OpusTranslator implements AutoCloseable {
    private static final int EOS = 0;
    private static final int UNKNOWN = 1;
    private static final int START = 65000;
    private static final int MAX_SOURCE_CHARS = 200;
    private static final int MAX_OUTPUT_TOKENS = 80;
    private static final char SPACE = '\u2581';

    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession encoder;
    private final OrtSession decoder;
    private final Vocabulary vocabulary;

    OpusTranslator(String encoderPath, String decoderPath, InputStream vocabularyInput)
            throws IOException, OrtException {
        vocabulary = new Vocabulary(vocabularyInput);
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2);
            encoder = environment.createSession(encoderPath, options);
            try {
                decoder = environment.createSession(decoderPath, options);
            } catch (OrtException error) {
                encoder.close();
                throw error;
            }
        }
    }

    String translate(String chinese, BooleanSupplier cancelled) throws OrtException {
        long[] ids = vocabulary.encode(chinese);
        long[] attention = new long[ids.length];
        Arrays.fill(attention, 1);
        try (OnnxTensor input = OnnxTensor.createTensor(environment, new long[][]{ids});
             OnnxTensor mask = OnnxTensor.createTensor(environment, new long[][]{attention});
             OnnxTensor noCache = OnnxTensor.createTensor(environment, new boolean[]{false});
             OnnxTensor useCache = OnnxTensor.createTensor(environment, new boolean[]{true});
             OnnxTensor initialCache = OnnxTensor.createTensor(environment,
                     FloatBuffer.allocate(8 * 64), new long[]{1, 8, 1, 64})) {
            Map<String, OnnxTensorLike> encoding = new HashMap<>();
            encoding.put("input_ids", input);
            encoding.put("attention_mask", mask);
            try (OrtSession.Result result = encoder.run(encoding)) {
                return decode(mask, (OnnxTensor) result.get(0), noCache, useCache, initialCache, cancelled);
            }
        }
    }

    private String decode(OnnxTensor mask, OnnxTensor encoded, OnnxTensor noCache, OnnxTensor useCache,
                          OnnxTensor initialCache, BooleanSupplier cancelled) throws OrtException {
        Map<String, OnnxTensorLike> inputs = new HashMap<>();
        inputs.put("encoder_attention_mask", mask);
        inputs.put("encoder_hidden_states", encoded);
        int token = START;
        StringBuilder output = new StringBuilder();
        OrtSession.Result initial = null;
        OrtSession.Result previous = null;
        try {
            for (int step = 0; step < MAX_OUTPUT_TOKENS && !cancelled.getAsBoolean(); step++) {
                inputs.put("use_cache_branch", step == 0 ? noCache : useCache);
                for (int layer = 0; layer < 6; layer++) {
                    putCache(inputs, layer + ".decoder.key", previous, initialCache);
                    putCache(inputs, layer + ".decoder.value", previous, initialCache);
                    putCache(inputs, layer + ".encoder.key", initial, initialCache);
                    putCache(inputs, layer + ".encoder.value", initial, initialCache);
                }
                try (OnnxTensor current = OnnxTensor.createTensor(environment, new long[][]{{token}})) {
                    inputs.put("input_ids", current);
                    OrtSession.Result next = decoder.run(inputs);
                    if (previous != null && previous != initial) previous.close();
                    previous = next;
                    if (initial == null) initial = next;
                }
                FloatBuffer scores = ((OnnxTensor) previous.get(0)).getFloatBuffer();
                token = 0;
                for (int id = 1; id < scores.limit(); id++) {
                    if (id != START && scores.get(id) > scores.get(token)) token = id;
                }
                if (token == EOS) break;
                if (token != UNKNOWN) output.append(vocabulary.words[token]);
            }
        } finally {
            if (previous != null && previous != initial) previous.close();
            if (initial != null) initial.close();
        }
        return output.toString().replace(SPACE, ' ').trim();
    }

    private static void putCache(Map<String, OnnxTensorLike> inputs, String suffix,
                                 OrtSession.Result result, OnnxTensor initialCache) {
        inputs.put("past_key_values." + suffix, result == null ? initialCache
                : (OnnxTensor) result.get("present." + suffix).orElseThrow(IllegalStateException::new));
    }

    @Override
    public void close() throws OrtException {
        try {
            decoder.close();
        } finally {
            encoder.close();
        }
    }

    private static final class Vocabulary {
        final String[] words;
        final Map<String, Piece> source = new HashMap<>();
        int maxLength;

        Vocabulary(InputStream input) throws IOException {
            try (DataInputStream data = new DataInputStream(new BufferedInputStream(input))) {
                int count = data.readInt();
                if (count != START + 1) throw new IOException("Unexpected OPUS vocabulary size");
                words = new String[count];
                for (int id = 0; id < count; id++) {
                    byte[] bytes = new byte[data.readUnsignedShort()];
                    data.readFully(bytes);
                    words[id] = new String(bytes, StandardCharsets.UTF_8);
                    float weight = data.readFloat();
                    if (Float.isFinite(weight)) {
                        source.put(words[id], new Piece(id, weight));
                        maxLength = Math.max(maxLength, words[id].length());
                    }
                }
            }
        }

        long[] encode(String text) {
            String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).trim();
            int cutoff = normalized.offsetByCodePoints(0,
                    Math.min(MAX_SOURCE_CHARS, normalized.codePointCount(0, normalized.length())));
            normalized = SPACE + normalized.substring(0, cutoff).replaceAll("\\s+", String.valueOf(SPACE));
            int size = normalized.length();
            float[] scores = new float[size + 1];
            Arrays.fill(scores, Float.NEGATIVE_INFINITY);
            scores[0] = 0;
            int[] predecessor = new int[size + 1];
            int[] ids = new int[size + 1];
            for (int start = 0; start < size; start++) {
                if (!Float.isFinite(scores[start])) continue;
                int nextChar = start + Character.charCount(normalized.codePointAt(start));
                if (scores[start] - 30 > scores[nextChar]) {
                    scores[nextChar] = scores[start] - 30;
                    predecessor[nextChar] = start;
                    ids[nextChar] = UNKNOWN;
                }
                for (int end = nextChar; end <= Math.min(size, start + maxLength); end++) {
                    Piece piece = source.get(normalized.substring(start, end));
                    if (piece == null || scores[start] + piece.weight <= scores[end]) continue;
                    scores[end] = scores[start] + piece.weight;
                    predecessor[end] = start;
                    ids[end] = piece.id;
                }
            }
            int count = 1;
            for (int cursor = size; cursor > 0; cursor = predecessor[cursor]) count++;
            long[] tokens = new long[count];
            for (int cursor = size, index = count - 2; cursor > 0; cursor = predecessor[cursor]) {
                tokens[index--] = ids[cursor];
            }
            tokens[count - 1] = EOS;
            return tokens;
        }
    }

    private static final class Piece {
        final int id;
        final float weight;

        Piece(int id, float weight) {
            this.id = id;
            this.weight = weight;
        }
    }
}
