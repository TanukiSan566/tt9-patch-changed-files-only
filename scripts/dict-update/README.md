# TT9 词库自动更新（中文 / 英语 / 日语）

GitHub Actions 自动运行：从开源词库下载**最新**数据 → 合并进 TT9 词典 → 检查 → 提交 → 自动触发 APK 构建。

## 什么时候会运行

| 触发 | 说明 |
|---|---|
| **上游 APK 更新** | 每天检查一次 `sspanak/tt9` 的最新 release（`UPSTREAM_REPO`，在工作流顶部修改）。出现新版本就强制重建词库、启动 APK 构建，并开一个 Issue 提醒你合并上游源码。没有新版本时，这次检查只花几秒。 |
| 你自己发布 Release | `release: published` |
| 外部通知 | `repository_dispatch`，类型 `apk-updated` |
| 每周一 | 开源词表本身更新很慢，所以每周刷新一次 |
| 手动 | Actions → Update dictionaries → Run workflow（可选 `dry_run`、`force`、只更新某种语言） |

已记录的上游版本保存在 `state.json` 的 `_apk` 里。

## 词源

| 语言 | 词源 | 更新频率 | 说明 |
|---|---|---|---|
| 中文（简体拼音） | [rime-ice 雾凇拼音](https://github.com/iDvel/rime-ice) | 每几周 | 带读音的 `8105 / base / others / ext`。`8105`、`others` 全部采用；`base`、`ext` 只采用有真实词频的词（没有词频的多是自动提取的杂乱短语）。 |
| 日语（罗马字） | [Mozc 开源词典](https://github.com/google/mozc) | 随 Mozc 提交 | 假名读音用 `kana.py` 转成 TT9 的罗马字写法（对照现有词典的 6.4 万个纯平假名词，一致率 99.6%）。`max_cost` 决定收录的生僻程度。 |
| 英语 | [FrequencyWords](https://github.com/hermitdave/FrequencyWords)（OpenSubtitles 词频）＋ [dwyl/english-words](https://github.com/dwyl/english-words) 拼写过滤 | 词频表为 2018 年版，基本不再更新 | 英语没有“经常更新”的开放词频源，所以只补充新词、补全缺失的词频，不改已有词频。 |

> 说明：百度、讯飞等商业输入法的云词库没有公开接口，也不能合法抓取后随 APK 分发，所以这里不使用。
>
> 没有收录的：`rime-community/rime-hotdict` 和 `outloudvi/moegirl-fcitx5` 这两个仓库不存在（`outloudvi/mw2fcitx` 只是生成工具，不是现成词库）；万象拼音的拼音列带辅助码、格式不同，需要另写解析器；汉典禁止批量抓取。

## 更宽的词源（`extra_sources`）

主词源之外，每种语言还可以挂任意多个附加词源；附加词源失败不会影响其他词源（会在报告的 Problems 里列出，作业变红，但成功的部分照常提交）。

| 语言 | 附加词源 | 内容 |
|---|---|---|
| 中文 | `zh-thuocl` | THUOCL 11 个领域词表（IT、动物、财经、汽车、成语、地名、食物、法律、历史名人、医学、诗词），拼音由 `pypinyin` 生成，词频上限 100 |
| 中文 | `zh-tencent` | rime-ice 的腾讯词向量词库（无拼音、无词频）。只取全部由 8105 常用字组成的 3–6 字词，按字的常用程度排序后取前 `max_words`（默认 20 万），拼音由 `pypinyin` 生成，词频为 0 |
| 中文 | `zh-zhwiki` | fcitx5-pinyin-zhwiki 每月发布的中文维基词条标题（人名、地名、机构、作品、术语），以及同一发布里的 `web-slang` 网络流行语（内卷、躺平……）。词条没有词频，按“字的常用程度”排序后取前 `max_words`（默认 6 万）；网络流行语词频为 `slang_frequency`（默认 60），排在普通维基词之前。拼音是机器生成的，所以词典里已有人工读音的词不会再多出一个猜的读音 |
| 中文 | `zh-cedict` | CC-CEDICT（MDBG，约每周更新）。只取简体词和它的拼音（去掉声调；释义不用），只收全部由 8105 常用字组成的 2–6 字词，儿化词（`r5`）跳过。主要补多音字的人工读音和 rime-ice 没有的词。许可 CC BY-SA 4.0，署名会写进 `docs/dictionaries/auto-update-sources.txt` |
| 中文（默认关闭） | `zh`（rime-frost 白霜） | 版式和 rime-ice 一样、词频重新统计。想要更大的词库时，把 `config.json` 里那一项的 `enabled` 改成 `true` |
| 英文 | `en-wordfreq` | `wordfreq` 多语料词频（`top_n` 15 万，`min_zipf` 2.5），用 dwyl 词表做拼写过滤，默认排除强粗口 |
| 日语 | `ja-sudachi` | SudachiDict Full（small+core+notcore）里的专有名词和普通名词，片假名读音转罗马字，词频上限 60，不会压过 Mozc 的常用词 |

- 机器生成拼音的词（`generated`）：如果词典里已经有同一个词的人工读音，就**不再**加第二个读音（例如已有「布什 BUSHI」，就不会再加 pypinyin 猜出的错误读音）。
- 想要更小的词库：调小 `max_words`、`top_n`、`max_cost`，或把某个附加词源的 `"enabled"` 设成 `false`。
- 修改 `config.json` 里的任何阈值后，下一次运行会自动重新合并（设置也计入指纹）。
- 需要 `pip install -r scripts/dict-update/requirements.txt`（工作流已自动安装）。

## 合并规则（不会让词库变小）

- 已有的词保持原样；词频只会**升高**（中、日：`max`），或只在原来为 0 时补上（英：`fill-missing`）。
- 新词追加到文件末尾，每一条都先用与 `./gradlew validateLanguages` 相同的规则检查，不合格的丢弃。
- 合并后整个词典再检查一遍；词典变小、或一次增长超过 `max_growth_ratio` 倍，就**拒绝写入**。
- 工作流里再跑一次真正的 `./gradlew validateLanguages`，通过才会提交。
- `state.json` 记录各词源的指纹：词源没变化就跳过，不产生无意义的提交（避免仓库体积膨胀）。

## 使用

1. 把本目录、`.github/workflows/update-dictionaries.yml` 放进仓库并提交。
2. 仓库 **Settings → Actions → General → Workflow permissions** 选 **Read and write permissions**。
3. **Actions → Update dictionaries → Run workflow**。第一次建议勾选 `dry_run`，先在运行摘要里看报告；确认后再取消勾选运行一次。
4. 以后每周一自动运行；有新词才会提交，并自动启动 `build.yml` 构建 APK。

手动运行时可以只更新某种语言（`languages` 填 `zh`），或勾选 `force` 忽略“词源没变化”的跳过。

## 配置（`config.json`）

- `enabled`：该语言是否参与更新。
- `max_new_words`：每次运行新增词数的**安全上限**。真正决定收录多少的是 `params` 里的阈值：
  - 中文 `weight_floor`（越大越严）、`max_word_length`、`curated_files`；
  - 日语 `max_cost`（越小越严；6000 约新增 10 万词，7000 约 22 万词）；
  - 英语 `min_count`（越大越严）、`exclude_words`、`exclude_profanity`（默认不加入强粗口，与 TT9 自带英语词典一致）。
- `frequency_policy`：`max` / `fill-missing` / `none`。

## 增加其他语言

- **字母语言**（德、法、西、俄……）：FrequencyWords 有约 60 种语言，通常只需在 `config.json` 里加一项，参考已写好但默认关闭的 `de`：把 `enabled` 改成 `true`，`definition` 填 `app/languages/definitions/` 里对应的 `.yml`，`lang` 填语言代码即可。
- **需要转写的语言**（如繁体拼音、注音、韩语）：复制 `sources/template.py`，实现 `fetch()` 返回 `(词, 转写, 词频)` 列表，用 `@register('名字')` 注册，在 `sources/__init__.py` 末尾 import，再在 `config.json` 里启用。转写必须与该语言现有词典的写法一致。

## 本机运行

```
python scripts/dict-update/update_dictionaries.py --dry-run          # 只看报告
python scripts/dict-update/update_dictionaries.py --languages zh     # 只更新中文
```
核心更新器只需 Python 3.9+；使用 `zh-thuocl` / `zh-tencent` / `en-wordfreq` 需要 `pip install -r scripts/dict-update/requirements.txt`。

## 注意

- **许可**：rime-ice 为 GPL-3.0，Mozc 为 BSD-3-Clause，FrequencyWords 内容为 CC-BY-SA，wordfreq 数据为 CC-BY-SA-4.0，SudachiDict 为 Apache-2.0（数据另有 UniDic 等声明），THUOCL 请看其仓库。更新后的出处会写入 `docs/dictionaries/auto-update-sources.txt`。如果要公开分发 APK，请先自行确认这些许可对你的要求。
- **仓库体积**：每次真正更新都会提交大文件（日语词典约 47 MB）。GitHub 单文件上限 100 MB，请留意；想减小体积就调低 `max_cost`。
- **回滚**：不满意就在 GitHub 上 `Revert` 那次 `Update dictionaries` 提交即可。

## 简拼 / 混拼（提高 T9 命中率）

TT9 靠“按键数字序列”查词，词典里只有全拼：周杰伦 = `ZHOUJIELUN`，所以按 `zjl`（9-5-5）或 `zhoujl` 找不到它。
更新时（`scripts/dict-update/abbreviations.py`）会给常用词额外生成两种行，例如：

```
周杰伦	ZJL       # 每个音节的首字母
周杰伦	ZHOUJL    # 第一个音节全拼，后面取首字母（只对 3 字以上的词）
```

这些是普通的“词 + 拼音 + 词频”行，App 不需要任何改动；选中后 App 会像普通词一样学习词频。每次更新都会重新检查：已有的行保留，只在词频变高时抬高。

`config.json` → `languages.zh.abbreviations`：

| 设置 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 总开关 |
| `patterns` | `initials`, `head_full` | 生成哪几种 |
| `min_frequency` | 60 | 3 字以上的词词频不低于它才生成（词典里有很多“在充分”“给朋友”这类短语碎片，所以不放得太低） |
| `two_character_min_frequency` / `two_character_max_words` | 150 / 3000 | 2 字词（北京=BJ）最容易和别的词、和真实拼音（WM=96=WO）撞键，只给最常用的 |
| `skip_prefixes` / `skip_suffixes` | 在把被给让从向与就 / 了的地着过吗呢吧么 | 这类开头或结尾的词不生成 |
| `initials_frequency_factor` / `head_full_frequency_factor` | 0.5 / 0.3 | 简拼行的词频 = 原词频 × 系数，所以同样按键下全拼词排在前面 |
| `max_entries` | 80000 | 最多生成多少行 |

已知的取舍：同样的按键下，简拼词会和别的词一起出现在候选里（比如按 `ZJL` 会有 总经理、周杰伦、主教练……），按词频排序；拼音行里也会出现 `zjl` 这样的“音”。想关掉就把 `enabled` 设为 `false`（已生成的行不会自动删除）。
