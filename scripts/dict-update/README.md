# TT9 词库自动更新（中文 / 英语 / 日语）

每周一自动（也可手动）运行 GitHub Actions：从开源词库下载**最新**数据 → 合并进 TT9 词典 → 检查 → 提交 → 自动触发 APK 构建。

## 词源

| 语言 | 词源 | 更新频率 | 说明 |
|---|---|---|---|
| 中文（简体拼音） | [rime-ice 雾凇拼音](https://github.com/iDvel/rime-ice) | 每几周 | 带读音的 `8105 / base / others / ext`。`8105`、`others` 全部采用；`base`、`ext` 只采用有真实词频的词（没有词频的多是自动提取的杂乱短语）。 |
| 日语（罗马字） | [Mozc 开源词典](https://github.com/google/mozc) | 随 Mozc 提交 | 假名读音用 `kana.py` 转成 TT9 的罗马字写法（对照现有词典的 6.4 万个纯平假名词，一致率 99.6%）。`max_cost` 决定收录的生僻程度。 |
| 英语 | [FrequencyWords](https://github.com/hermitdave/FrequencyWords)（OpenSubtitles 词频）＋ [dwyl/english-words](https://github.com/dwyl/english-words) 拼写过滤 | 词频表为 2018 年版，基本不再更新 | 英语没有“经常更新”的开放词频源，所以只补充新词、补全缺失的词频，不改已有词频。 |

> 说明：百度、讯飞等商业输入法的云词库没有公开接口，也不能合法抓取后随 APK 分发，所以这里不使用。

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
只需 Python 3.9+，不需要额外依赖。

## 注意

- **许可**：rime-ice 为 GPL-3.0，Mozc 为 BSD-3-Clause，FrequencyWords 内容为 CC-BY-SA。更新后的出处会写入 `docs/dictionaries/auto-update-sources.txt`。如果要公开分发 APK，请先自行确认这些许可对你的要求。
- **仓库体积**：每次真正更新都会提交大文件（日语词典约 47 MB）。GitHub 单文件上限 100 MB，请留意；想减小体积就调低 `max_cost`。
- **回滚**：不满意就在 GitHub 上 `Revert` 那次 `Update dictionaries` 提交即可。
