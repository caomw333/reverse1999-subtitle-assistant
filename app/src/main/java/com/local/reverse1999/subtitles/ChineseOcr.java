package com.local.reverse1999.subtitles;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class ChineseOcr implements AutoCloseable {
    private static final float DETECTION_THRESHOLD = 0.30f;
    private static final int RECOGNITION_HEIGHT = 48;

    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession detector;
    private final OrtSession recognizer;
    private final String[] characters;

    ChineseOcr(String detectionModel, String recognitionModel) throws OrtException {
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2);
            detector = environment.createSession(detectionModel, options);
            try {
                recognizer = environment.createSession(recognitionModel, options);
            } catch (OrtException error) {
                detector.close();
                throw error;
            }
        }
        characters = recognizer.getMetadata().getCustomMetadataValue("character")
                .orElseThrow(() -> new IllegalStateException("OCR dictionary missing")).split("\n");
    }

    String read(int[] pixels, int width, int height) throws OrtException {
        double scale = Math.min(1.0, 800.0 / width);
        int inputWidth = align32(width * scale);
        int inputHeight = align32(height * scale);
        Box full = new Box(0, 0, width, height);
        float[] input = imageTensor(pixels, width, full, inputWidth, inputHeight, inputWidth);
        float[][] score;
        try (OnnxTensor tensor = tensor(input, inputHeight, inputWidth);
             OrtSession.Result result = detector.run(Collections.singletonMap("x", tensor))) {
            score = ((float[][][][]) result.get(0).getValue())[0][0];
        }
        List<Box> boxes = mergeLineFragments(components(score));
        boxes.sort(Comparator.comparingInt(Box::width).reversed());
        List<Line> lines = new ArrayList<>();
        for (Box box : boxes.subList(0, Math.min(8, boxes.size()))) {
            int padding = Math.max(3, box.height() / 2);
            Box crop = new Box(
                    Math.max(0, (box.left - padding) * width / inputWidth),
                    Math.max(0, (box.top - padding) * height / inputHeight),
                    Math.min(width, (box.right + padding) * width / inputWidth),
                    Math.min(height, (box.bottom + padding) * height / inputHeight));
            if (crop.height() == 0 || crop.width() == 0) continue;
            String text = recognize(pixels, width, crop);
            if (hanCount(text) >= 2) lines.add(new Line(box, text));
        }
        if (lines.isEmpty()) return "";

        Line first = lines.get(0);
        lines.removeIf(line -> Math.abs(line.box.centerY() - first.box.centerY()) > first.box.height() * 4
                || Math.abs(line.box.centerX() - first.box.centerX()) > inputWidth / 5);
        lines.sort(Comparator.comparingInt(line -> line.box.top));
        StringBuilder text = new StringBuilder();
        for (Line line : lines) {
            if (text.length() != 0) text.append(' ');
            text.append(line.text);
        }
        return text.toString().trim();
    }

    private String recognize(int[] pixels, int width, Box crop) throws OrtException {
        int textWidth = Math.min(768, (int) Math.ceil(RECOGNITION_HEIGHT * crop.width() / (double) crop.height()));
        int inputWidth = Math.max(320, ((textWidth + 31) / 32) * 32);
        float[] input = imageTensor(pixels, width, crop, textWidth, RECOGNITION_HEIGHT, inputWidth);
        try (OnnxTensor tensor = tensor(input, RECOGNITION_HEIGHT, inputWidth);
             OrtSession.Result result = recognizer.run(Collections.singletonMap("x", tensor))) {
            float[][] scores = ((float[][][]) result.get(0).getValue())[0];
            StringBuilder text = new StringBuilder();
            int previous = 0;
            int count = 0;
            float confidence = 0;
            for (float[] step : scores) {
                int index = 0;
                for (int i = 1; i < step.length; i++) if (step[i] > step[index]) index = i;
                if (index != previous && index > 0) {
                    text.append(index <= characters.length ? characters[index - 1] : " ");
                    confidence += step[index];
                    count++;
                }
                previous = index;
            }
            return count > 0 && confidence / count >= 0.58f ? text.toString().trim() : "";
        }
    }

    private OnnxTensor tensor(float[] data, int height, int width) throws OrtException {
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(data), new long[]{1, 3, height, width});
    }

    private static float[] imageTensor(int[] pixels, int stride, Box crop, int width, int height,
                                       int paddedWidth) {
        int plane = height * paddedWidth;
        float[] values = new float[3 * plane];
        for (int y = 0; y < height; y++) {
            int sourceY = crop.top + y * crop.height() / height;
            for (int x = 0; x < width; x++) {
                int pixel = pixels[sourceY * stride + crop.left + x * crop.width() / width];
                for (int channel = 0; channel < 3; channel++) {
                    float component = ((pixel >> (channel * 8)) & 255) / 255f;
                    values[channel * plane + y * paddedWidth + x] = component * 2 - 1;
                }
            }
        }
        return values;
    }

    private static List<Box> components(float[][] scores) {
        int height = scores.length;
        int width = scores[0].length;
        int[] queue = new int[width * height];
        List<Box> boxes = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (scores[y][x] < DETECTION_THRESHOLD) continue;
                int end = 1;
                int left = x, right = x, top = y, bottom = y;
                queue[0] = y * width + x;
                float confidence = scores[y][x];
                scores[y][x] = 0;
                for (int start = 0; start < end; start++) {
                    int px = queue[start] % width;
                    int py = queue[start] / width;
                    left = Math.min(left, px);
                    right = Math.max(right, px);
                    top = Math.min(top, py);
                    bottom = Math.max(bottom, py);
                    for (int ny = Math.max(0, py - 1); ny <= Math.min(height - 1, py + 1); ny++) {
                        for (int nx = Math.max(0, px - 1); nx <= Math.min(width - 1, px + 1); nx++) {
                            if (scores[ny][nx] < DETECTION_THRESHOLD) continue;
                            queue[end++] = ny * width + nx;
                            confidence += scores[ny][nx];
                            scores[ny][nx] = 0;
                        }
                    }
                }
                Box box = new Box(left, top, right + 1, bottom + 1);
                if (box.width() >= 8 && box.height() >= 5 && box.height() <= height / 4
                        && box.width() >= box.height() * 0.6 && confidence / end >= 0.46f) {
                    boxes.add(box);
                }
            }
        }
        return boxes;
    }

    private static List<Box> mergeLineFragments(List<Box> boxes) {
        boxes.sort(Comparator.comparingInt(box -> box.left));
        List<Box> lines = new ArrayList<>();
        for (Box box : boxes) {
            Box previous = null;
            for (Box line : lines) {
                int height = Math.max(box.height(), line.height());
                if (box.left - line.right <= height * 1.6
                        && Math.abs(box.centerY() - line.centerY()) < height * 0.45) {
                    previous = line;
                    break;
                }
            }
            if (previous != null) {
                lines.remove(previous);
                lines.add(new Box(Math.min(previous.left, box.left), Math.min(previous.top, box.top),
                        Math.max(previous.right, box.right), Math.max(previous.bottom, box.bottom)));
            } else {
                lines.add(box);
            }
        }
        lines.removeIf(line -> line.width() < line.height() * 1.6);
        return lines;
    }

    private static long hanCount(String text) {
        return text.codePoints().filter(point -> Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN).count();
    }

    private static int align32(double size) {
        return Math.max(32, (int) Math.round(size / 32) * 32);
    }

    @Override
    public void close() throws OrtException {
        try {
            recognizer.close();
        } finally {
            detector.close();
        }
    }

    private static final class Box {
        final int left, top, right, bottom;

        Box(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        int width() { return right - left; }
        int height() { return bottom - top; }
        int centerX() { return (left + right) / 2; }
        int centerY() { return (top + bottom) / 2; }
    }

    private static final class Line {
        final Box box;
        final String text;

        Line(Box box, String text) {
            this.box = box;
            this.text = text;
        }
    }
}
