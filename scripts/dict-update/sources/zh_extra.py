"""
Extra Simplified Chinese (pinyin) sources that add breadth on top of rime-ice's pinyin-annotated files:

  zh-thuocl   THUOCL (清华大学开放中文词库), 11 domain lists: IT, animal, caijing, car, chengyu, diming, food, law,
              lishimingren, medical, poem. Lines are "word TAB count". https://github.com/thunlp/THUOCL
  zh-tencent  rime-ice's `tencent.dict.yaml` (Tencent AI Lab embedding vocabulary, 3+ character words). It has NO pinyin
              and no real weights (every row is 100), so the pinyin is generated with `pypinyin` and the words get no
              frequency. To keep the quality up, only words made entirely of the common characters from rime-ice's
              8105 list are used, up to `max_word_length` characters. The file is sorted alphabetically, so a plain
              cut-off would only keep words from "a" to "zhen": instead the words are ranked by how common their
              characters are (average of the 8105 character weights) and the best `max_words` are kept.

Both need `pip install pypinyin` (see requirements.txt). Generated pinyin can be wrong for rare polyphonic words, which is
why these words get a low or no frequency and never outrank the curated rime-ice words.
"""
import math
import re

from . import register, SourceResult, fingerprint_of

HAN = re.compile(r'^[\u3400-\u4dbf\u4e00-\u9fff]+$')
RIME_URL = 'https://raw.githubusercontent.com/{repo}/{branch}/cn_dicts/{name}.dict.yaml'
THUOCL_URL = 'https://raw.githubusercontent.com/{repo}/{branch}/data/THUOCL_{name}.txt'
THUOCL_FILES = ['IT', 'animal', 'caijing', 'car', 'chengyu', 'diming', 'food', 'law', 'lishimingren', 'medical', 'poem']


def _pinyin(word):
	"""Upper-case pinyin without tones (ü -> V), one string per word, or None when any character has no reading."""
	try:
		from pypinyin import lazy_pinyin, Style
	except ImportError as e:
		raise RuntimeError('pypinyin is not installed: pip install -r scripts/dict-update/requirements.txt') from e
	parts = lazy_pinyin(word, style=Style.NORMAL, errors='ignore')
	if len(parts) != len(word):
		return None
	result = []
	for p in parts:
		p = p.lower().replace('ü', 'v')
		if not re.fullmatch('[a-z]+', p):
			return None
		result.append(p)
	return ''.join(result).upper()


def _rime_rows(http, repo, branch, name):
	"""Yields the tab separated rows (after the YAML header) of a rime-ice dictionary file."""
	url = RIME_URL.format(repo=repo, branch=branch, name=name)
	in_body = False
	for line in http.text(url).split('\n'):
		if not in_body:
			in_body = line.strip() == '...'
			continue
		if line and not line.startswith('#'):
			yield line.split('\t')


@register('zh-thuocl')
class Thuocl:
	def __init__(self, cfg, http):
		self.cfg, self.http = cfg, http

	def fetch(self):
		repo = self.cfg.get('repo', 'thunlp/THUOCL')
		branch = self.cfg.get('branch', 'master')
		names = self.cfg.get('files', THUOCL_FILES)
		min_len, max_len = self.cfg.get('min_word_length', 2), self.cfg.get('max_word_length', 8)
		out_max = self.cfg.get('max_frequency', 100)  # kept low on purpose: domain words must not outrank everyday words
		urls, best = [], {}

		for name in names:
			url = THUOCL_URL.format(repo=repo, branch=branch, name=name)
			urls.append(url)
			rows = []
			for line in self.http.text(url).split('\n'):
				parts = line.strip().split('\t')
				if len(parts) != 2 or not parts[1].isdigit():
					continue
				word, count = parts[0].strip(), int(parts[1])
				if min_len <= len(word) <= max_len and HAN.match(word) and count > 0:
					rows.append((word, count))
			if not rows:
				continue
			low, high = min(c for _, c in rows), max(c for _, c in rows)
			for word, count in rows:
				ratio = (math.log(count) - math.log(low)) / (math.log(high) - math.log(low)) if high > low else 1
				freq = max(1, min(out_max, int(round(1 + ratio * (out_max - 1)))))
				best[word] = max(best.get(word, 0), freq)

		entries, skipped = [], 0
		for word, freq in best.items():
			trans = _pinyin(word)
			if trans:
				entries.append((word, trans, freq))
			else:
				skipped += 1
		print(f'   zh-thuocl: {len(entries)} words with pinyin ({skipped} without a reading skipped)')
		attribution = ['Simplified Chinese (extra): THUOCL, https://github.com/' + repo,
			'  domain lists: ' + ', '.join(names) + '; pinyin generated with pypinyin',
			'  license: see the THUOCL repository']
		result = SourceResult(entries, fingerprint_of(self.http, urls), attribution)
		result.generated = {(w, t) for w, t, _ in entries}
		return result


@register('zh-tencent')
class TencentPinyin:
	def __init__(self, cfg, http):
		self.cfg, self.http = cfg, http

	def fetch(self):
		repo = self.cfg.get('repo', 'iDvel/rime-ice')
		branch = self.cfg.get('branch', 'main')
		min_len, max_len = self.cfg.get('min_word_length', 3), self.cfg.get('max_word_length', 6)
		max_words = self.cfg.get('max_words', 200000)
		chars_file = self.cfg.get('common_chars_file', '8105')

		weights = {}  # character -> sum of its rime-ice weights (all readings)
		for row in _rime_rows(self.http, repo, branch, chars_file):
			if len(row) >= 2 and len(row[0]) == 1:
				try:
					weight = int(row[2]) if len(row) > 2 else 0
				except ValueError:
					weight = 0
				weights[row[0]] = weights.get(row[0], 0) + max(weight, 0)
		if len(weights) < 5000:
			raise RuntimeError(f'only {len(weights)} common characters read from {chars_file}.dict.yaml, the file format has probably changed')

		urls = [RIME_URL.format(repo=repo, branch=branch, name=n) for n in (chars_file, 'tencent')]
		candidates, seen = [], set()
		for n, row in enumerate(_rime_rows(self.http, repo, branch, 'tencent')):
			word = row[0]
			if not (min_len <= len(word) <= max_len) or word in seen or not all(c in weights for c in word):
				continue
			seen.add(word)
			score = sum(math.log(weights[c] + 10) for c in word) / len(word)
			candidates.append((-score, len(word), n, word))
		candidates.sort()  # most common characters first, shorter first, file order last

		entries, no_reading = [], 0
		for _, _, _, word in candidates:
			if len(entries) >= max_words:
				break
			trans = _pinyin(word)
			if not trans:
				no_reading += 1
				continue
			entries.append((word, trans, 0))
		print(f'   zh-tencent: kept {len(entries)} of {len(candidates)} eligible words ({no_reading} without a reading skipped)')
		attribution = ['Simplified Chinese (extra): rime-ice tencent.dict.yaml (Tencent AI Lab vocabulary), https://github.com/' + repo,
			'  pinyin generated with pypinyin; only words made of the 8105 common characters, no frequency',
			'  license: GPL-3.0 (rime-ice); Tencent AI Lab embedding data: see https://ai.tencent.com/ailab/nlp/zh/embedding.html']
		result = SourceResult(entries, fingerprint_of(self.http, urls), attribution)
		result.generated = {(w, t) for w, t, _ in entries}
		return result
