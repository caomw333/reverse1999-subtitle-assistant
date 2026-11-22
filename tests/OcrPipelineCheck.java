package com.local.reverse1999.subtitles;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.FileInputStream;

final class OcrPipelineCheck {
    public static void main(String[] args) throws Exception {
        BufferedImage sample = new BufferedImage(1120, 380, BufferedImage.TYPE_INT_RGB);
        Graphics2D canvas = sample.createGraphics();
        canvas.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        canvas.setColor(new Color(24, 32, 44));
        canvas.fillRect(0, 0, sample.getWidth(), sample.getHeight());
        canvas.setColor(Color.WHITE);
        canvas.setFont(new Font("Microsoft YaHei", Font.PLAIN, 37));
        canvas.drawString("暴雨就要来了。我们必须离开这里。", 250, 250);
        canvas.dispose();
        String root = "app/src/main/assets/";
        try (ChineseOcr ocr = new ChineseOcr(root + "ocr/ch_PP-OCRv4_det_infer.onnx",
                     root + "ocr/ch_PP-OCRv4_rec_infer.onnx");
             OpusTranslator translator = new OpusTranslator(root + "translation/encoder_model_quantized.onnx",
                     root + "translation/decoder_model_merged_quantized.onnx",
                     new FileInputStream(root + "translation/opus-vocab.bin"))) {
            int w = sample.getWidth(), h = sample.getHeight();
            String chinese = ocr.read(sample.getRGB(0, 0, w, h, null, 0, w), w, h);
            System.out.println("OCR: " + chinese);
            assert chinese.contains("暴雨") && chinese.contains("离开") : chinese;
            String english = translator.translate(chinese, () -> false);
            System.out.println("EN: " + english);
            String meaning = english.toLowerCase();
            assert (meaning.contains("rain") || meaning.contains("storm"))
                    && (meaning.contains("leave") || meaning.contains("get out")) : english;
        }
    }
}
