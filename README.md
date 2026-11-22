# 重返未来英语字幕助手

Android 10 及以上的本地字幕助手，当前版本为 0.3.3。

- 捕获《重返未来：1999》的英文配音，用 Vosk 生成实时字幕。
- 用 PP-OCRv4 识别中文字幕，用 OPUS-MT 在手机上译成英文。
- ASR 和 OCR 可独立勾选，双开时按时间和句子顺序配对。
- 两行滚动字幕，支持拖动、字号、底色透明度、完整历史和 OCR 区域框选。
- 四六级词汇高亮，排除高中基础词，支持中文释义、本地句子、接触次数和高亮开关。

## 源码结构

| 位置 | 内容 |
| --- | --- |
| `app/src/main/java/com/local/reverse1999/subtitles/` | Android 应用代码 |
| `app/src/main/res/` | 界面文案、主题、图标 |
| `app/src/main/assets/vocabulary/` | 本地四六级词表和中文释义 |
| `app/src/main/assets/licenses/` | 模型、词表和依赖的来源与许可 |
| `tools/` | APK 打包、词表构建、翻译词表预处理脚本 |
| `tests/` | Java 桌面回归检查 |
| `CHANGELOG.md` | 按版本记录的改动和验证范围 |

## 构建准备

使用 JDK 17、Android SDK API 35、Build Tools 35 和 Gradle 8.9。当前仓库包含源码及文本资源，运行需要的模型另行准备到以下路径。

| 本地路径 | 来源 |
| --- | --- |
| `app/src/main/assets/vosk-model-small-en-us-0.15.zip` | [Vosk 英文模型](https://alphacephei.com/vosk/models) |
| `app/src/main/assets/ocr/ch_PP-OCRv4_det_infer.onnx` | [RapidOCR 1.4.4 发布包](https://pypi.org/project/rapidocr-onnxruntime/1.4.4/) |
| `app/src/main/assets/ocr/ch_PP-OCRv4_rec_infer.onnx` | 同上 |
| `app/src/main/assets/translation/encoder_model_quantized.onnx` | [Xenova/opus-mt-zh-en](https://huggingface.co/Xenova/opus-mt-zh-en/tree/main/onnx) |
| `app/src/main/assets/translation/decoder_model_merged_quantized.onnx` | 同上 |
| `app/src/main/assets/translation/opus-vocab.bin` | 由下述脚本生成 |

从 Xenova 模型仓库取得 `source.spm`、`vocab.json` 和上表的两个 ONNX 文件，放入 `.tools/opus/`，在项目根目录执行。

```powershell
python tools/prepare_opus.py
```

在 `local.properties` 设置 SDK 路径后，执行 `gradle :app:assembleDebug`。Maven 依赖在 `app/build.gradle` 中声明。仓库未附 Gradle wrapper，需使用本地 Gradle。

也可使用本项目打包脚本。它面向 Windows，需要本地工具目录 `.tools/build-tools/android-15/` 和 `.tools/platform/android-35/android.jar`。将三份依赖 AAR 放入 `app/libs/`。

- `com.alphacephei:vosk-android:0.3.75`
- `net.java.dev.jna:jna:5.18.1`
- `com.microsoft.onnxruntime:onnxruntime-android:1.23.2`

```powershell
$env:SUBTITLE_JDK = 'C:/path/to/jdk-17'
python tools/build_apk.py
```

打包脚本在 `.tools/package/` 创建本地调试签名，产物写入 `dist/`。覆盖安装已有 APK 需要相同签名。原开发机的签名保留在本机工具目录。

## 回归检查

在项目根目录执行以下命令，覆盖字幕融合、历史、滚动与词汇匹配。

```powershell
$source = 'app/src/main/java/com/local/reverse1999/subtitles'
New-Item -ItemType Directory -Force .tools/checks | Out-Null
javac -encoding UTF-8 -d .tools/checks "$source/CaptionFusion.java" "$source/SubtitleHistory.java" "$source/RollingSubtitleLines.java" "$source/CetVocabulary.java" tests/FusionCheck.java tests/HistoryCheck.java tests/RollingLinesCheck.java tests/VocabularyCheck.java
java -ea -cp .tools/checks com.local.reverse1999.subtitles.FusionCheck
java -ea -cp .tools/checks com.local.reverse1999.subtitles.HistoryCheck
java -ea -cp .tools/checks com.local.reverse1999.subtitles.RollingLinesCheck
java -ea -cp .tools/checks com.local.reverse1999.subtitles.VocabularyCheck
```

`tests/OcrPipelineCheck.java` 另需桌面 ONNX Runtime 1.23.2、上述 OCR 和翻译模型、中文字体。

Android 权限、横屏拖动、屏幕采集和持续运行效果需在手机上验证。模型、词表与第三方库使用各自许可，详见 `app/src/main/assets/licenses/`。
