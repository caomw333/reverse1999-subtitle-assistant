# 变更记录

## v0.3.3 · 2026-11-22

字幕来源按应用名称或桌面启动名称中的「重返未来」关键词筛选，使用应用名称作为显示名称，并按包名去重。改动在 `MainActivity.java` 的 `loadGames()`。版本号和构建产物路径同步更新到 0.3.3。

## v0.3.2

| 改动位置 | 实际变更 |
| --- | --- |
| `CaptionFusion.java`、`SubtitleHistory.java`、`SubtitleService.java` | 结合 OCR 台词可见区间、配音时间和前后顺序配对。两候选时间接近时避免猜配，OCR 补位会标记「未配对」。日志记录配对的时间差、重叠和补位原因。 |
| `OcrRegion.java`、`OcrRegionPicker.java`、`ScreenSubtitleReader.java`、`SubtitleOverlay.java` | 从通知栏或展开的字幕历史框选 OCR 区域并记住位置。用区域亮度变化决定是否运行 OCR，另有周期复查。采样时遮去自身字幕窗覆盖的像素。 |
| `LearningActivity.java`、`LearningStore.java`、`CetVocabulary.java` | 词库显示中文释义，点词按页查看本地出现过的原句。沿用旧版句子、词频和高亮偏好，无需迁移数据库。 |
| `tools/build_vocabulary.py`、`assets/vocabulary/cet.tsv`、`assets/licenses/vocabulary-NOTICE.txt` | 从原四六级词表抽取中文释义，3,553 条候选词均有释义。 |
| `res/values/strings.xml`、`app/build.gradle`、`.tools/build_apk.py` | 加入界面文案，版本更新至 0.3.2。 |

桌面回归覆盖可见区间配对、相邻句、OCR 补位和迟到替换，以及词库读取、历史和滚动。框选位置和屏幕采集还需要用 Android 真机验证。

## v0.3.1

修复双开 ASR 和 OCR 时，同一句台词先显示 OCR 英译、再作为另一条 ASR 字幕显示的问题。

| 改动位置 | 实际变更 |
| --- | --- |
| `CaptionFusion.java` | 用 ASR 已处理到的音频时间辅助决定 OCR 补位时机。已显示的 OCR 译文保留配对窗口。迟到的 ASR 在时间匹配时更新同一条字幕，措辞相似度用于标记对照结果。 |
| `SubtitleService.java` | 混合模式将音频处理进度和 Vosk 的词级起止时间交给融合模块。诊断日志记录识别时间范围。 |
| `.tools/history-check/FusionCheck.java` | 加入措辞不同的迟到 ASR、ASR 处理落后、OCR 到达等待上限，以及相邻台词应独立显示的回归场景。 |
| `app/build.gradle`、`.tools/build_apk.py` | 版本更新为 0.3.1，构建产物为 `dist/Reverse1999-Subtitles-0.3.1-test.apk`。 |

## v0.3.0 · 2026-11-12

以 v0.2.2 为基线。本版加入本地学习记录，并修复横屏字幕框的拖动范围。

| 需求 | 改动位置 | 实际变更 |
| --- | --- | --- |
| 横屏拖动 | `app/src/main/java/com/local/reverse1999/subtitles/SubtitleOverlay.java` | 按整屏尺寸和系统安全区域计算边界，用屏幕宽高区分横竖屏位置。横屏字幕宽度上限从可用宽度的 88% 调为 70%，让出横向移动空间。拖到边缘后反向移动会立即跟手。 |
| 四六级高亮 | `CetVocabulary.java`、`LearningWords.java`、`SubtitleOverlay.java` | 按英文词匹配，合并常见词形。高中词优先排除，目标词用金色显示。跨行时沿用整句词的位置。 |
| 本地句子 | `SubtitleService.java`、`LearningStore.java` | 后台把 ASR 最终字幕、OCR 英译及混合模式的 OCR 对照译文写进应用私有 SQLite。字幕会话结束时保存等待配对的 OCR 译文。 |
| 接触次数 | `LearningStore.java`、`LearningActivity.java` | 按最终字幕正文中的词次计数，同一句重复出现的词分别计数。同一会话、同一字幕段只保留一条记录，修订正文时替换该句的计数。OCR 对照译文随句保存，统计以主字幕正文为准。 |
| 关闭或恢复高亮 | `LearningActivity.java`、`LearningWords.java` | 词库支持搜索，按接触次数排序。点单词可取消或恢复高亮，偏好保存在本地。取消高亮后仍累计该词的接触次数。 |
| 应用入口与版本 | `MainActivity.java`、`AndroidManifest.xml`、`res/values/strings.xml`、`app/build.gradle`、`.tools/build_apk.py` | 新增「本地学习记录」入口和页面注册。版本更新为 0.3.0，构建产物为 `dist/Reverse1999-Subtitles-0.3.0-test.apk`。 |

表中 Java 文件均位于 `app/src/main/java/com/local/reverse1999/subtitles/`，资源路径相对于 `app/src/main/`。

### 词表来源

使用 [C3H3-AI/Vocab WordBank](https://github.com/C3H3-AI/vocab-wordbank) 的 `cet4.json`、`cet6.json`、`gaokao-3500.json` 和 `senior.json`。四、六级及补充高中词表来自 [KyleBing/english-vocabulary](https://github.com/KyleBing/english-vocabulary)。高考词表上游标为 [lilinji/English](https://github.com/lilinji/English)。合并高中和高考词表是为了覆盖整理版高考词表漏收的常见基础词，例如 `and` 和 `of`。

生成脚本为 `tools/build_vocabulary.py`。它提取、规范化和去重单词，将 4,040 条排除词和 3,553 条四、六级候选词写入 `app/src/main/assets/vocabulary/cet.tsv`。来源说明与许可在 `app/src/main/assets/licenses/vocabulary-*`。可从项目根目录运行 `python tools/build_vocabulary.py .tools/wordbank` 重新生成。

| 输入词表 | SHA-256 |
| --- | --- |
| cet4.json | `7b869cbe4bfeec34266e69f1a2044c91f262232a9cdf8e11094877020fffd827` |
| cet6.json | `50607e374353a904a83d8eac2bd79bbb330fb2528d1fb0fc2591ee6db4220c51` |
| gaokao-3500.json | `857de1b1bdbc8f73d62a0cf15fec5572fc5bd5f6c073366ca482121a4bf2ea46` |
| senior.json | `76f30da973aa2ef310c1be351aebbf15ea7c0262f29a04d348414207fb00e85a` |

### 验证

- 用实际词表运行 Java 检查，验证高中词排除、常见词形匹配、文字位置和重复词次。
- 用 SQLite 验证应用的表定义与汇总查询，覆盖字幕段唯一性、修订后的词频替换和 OCR 对照译文存储。
- 混合字幕和两行滚动的回归检查通过。APK 已检查签名、词表内容和编译产物，签名证书与 v0.2.2 相同。
- 横屏拖动和学习记录界面还待 Android 真机试用。学习记录从本版开始累积。
