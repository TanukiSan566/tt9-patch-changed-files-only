"""
Simplified Chinese (pinyin) from rime-ice (雾凇拼音): https://github.com/iDvel/rime-ice
A long-maintained open-source word list, updated every few weeks. We read the `cn_dicts/*.dict.yaml` files that carry a
pinyin column (8105 = single characters, base = common words, ext = extended words, others = corrections).
(`tencent.dict.yaml` has no pinyin column, so it cannot be used.)
"""
import math
import re

from . import register, SourceResult, fingerprint_of

HAN = re.compile(r'^[\u3400-\u4dbf\u4e00-\u9fff\U00020000-\U0002ffff]+$')
BASE_URL = 'https://raw.githubusercontent.com/{repo}/{branch}/cn_dicts/{name}.dict.yaml'


def _pinyin_to_transcription(code):
	letters = []
	for syllable in code.split(' '):
		syllable = syllable.strip().lower().replace('ü', 'v')
		if not re.fullmatch('[a-z]+', syllable):
			return None
		letters.append(syllable)
	return ''.join(letters).upper()


@register('zh')
class RimeIce:
	def __init__(self, cfg, http):
		self.cfg, self.http = cfg, http

	def fetch(self):
		repo = self.cfg.get('repo', 'iDvel/rime-ice')
		branch = self.cfg.get('branch', 'main')
		files = self.cfg.get('files', ['8105', 'base', 'others', 'ext'])
		max_len = self.cfg.get('max_word_length', 10)
		low = self.cfg.get('weight_floor', 100)   # weights <= this carry no real frequency information
		# 8105 (characters and their readings) and others (pronunciation fixes) are hand-curated, so every row is taken.
		# The other files contain many rows without a real weight (mostly auto-extracted phrases): those are skipped.
		curated = set(self.cfg.get('curated_files', ['8105', 'others']))
		urls, versions = [], []
		rows = {}  # (word, trans) -> weight

		for name in files:
			url = BASE_URL.format(repo=repo, branch=branch, name=name)
			urls.append(url)
			text = self.http.text(url)
			in_body = False
			for line in text.split('\n'):
				if not in_body:
					m = re.match(r'version:\s*"?([^"\s]+)', line)
					if m:
						versions.append(f'{name} {m.group(1)}')
					if line.strip() == '...':
						in_body = True
					continue
				if not line or line.startswith('#'):
					continue
				parts = line.split('\t')
				if len(parts) < 2:
					continue
				word, code = parts[0], parts[1]
				if not HAN.match(word) or len(word) > max_len:
					continue
				trans = _pinyin_to_transcription(code)
				if not trans:
					continue
				try:
					weight = int(parts[2]) if len(parts) > 2 else 0
				except ValueError:
					weight = 0
				if name not in curated and weight <= low:
					continue
				key = (word, trans)
				if weight > rows.get(key, -1):
					rows[key] = weight

		top = max(rows.values()) if rows else 0
		out_max = self.cfg.get('max_frequency', 255)
		entries = []
		for (word, trans), weight in rows.items():
			if weight <= low:
				freq = 0
			else:
				ratio = (math.log(weight) - math.log(low)) / (math.log(top) - math.log(low))
				freq = max(1, min(out_max, int(round(ratio * out_max))))
			entries.append((word, trans, freq))

		attribution = [
			'Simplified Chinese: rime-ice (雾凇拼音), https://github.com/' + repo,
			'  versions: ' + ', '.join(versions),
			'  license: GPL-3.0 (see the repository for details)',
		]
		return SourceResult(entries, fingerprint_of(self.http, urls), attribution)
