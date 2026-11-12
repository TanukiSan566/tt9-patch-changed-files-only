"""
Japanese from the open-source Mozc dictionary: https://github.com/google/mozc (src/data/dictionary_oss/dictionaryNN.txt)
Each row is: reading(hiragana) TAB left-id TAB right-id TAB cost TAB surface. A lower cost means a more common word.
The reading is converted to the romaji convention used by TT9's ja-romaji.csv (see kana.py).
"""
import re

from . import register, SourceResult, fingerprint_of
from kana import to_romaji

URL = 'https://raw.githubusercontent.com/{repo}/{branch}/src/data/dictionary_oss/dictionary{n:02d}.txt'
SURFACE = re.compile(r'^[\u3041-\u3096\u30a1-\u30fa\u30fc\u3400-\u4dbf\u4e00-\u9fff\u3005]+$')
READING = re.compile(r'^[\u3041-\u3096\u30fc]+$')


@register('ja')
class Mozc:
	def __init__(self, cfg, http):
		self.cfg, self.http = cfg, http

	def fetch(self):
		repo = self.cfg.get('repo', 'google/mozc')
		branch = self.cfg.get('branch', 'master')
		files = self.cfg.get('files', list(range(10)))
		max_cost = self.cfg.get('max_cost', 7500)    # drop rare entries, otherwise the dictionary grows too much
		best_cost = self.cfg.get('best_cost', 2500)  # cost that maps to the highest frequency
		out_max = self.cfg.get('max_frequency', 200)
		max_len = self.cfg.get('max_word_length', 12)
		urls = [URL.format(repo=repo, branch=branch, n=n) for n in files]

		costs = {}  # (surface, romaji) -> lowest cost
		for url in urls:
			for line in self.http.text(url).split('\n'):
				parts = line.split('\t')
				if len(parts) < 5:
					continue
				reading, cost, surface = parts[0], int(parts[3]), parts[4]
				if cost > max_cost or len(surface) > max_len:
					continue
				if not READING.match(reading) or not SURFACE.match(surface):
					continue
				romaji = to_romaji(reading)
				if not romaji or not re.fullmatch('[A-Za-z]+', romaji):
					continue
				key = (surface, romaji)
				if cost < costs.get(key, 10 ** 9):
					costs[key] = cost

		span = max(1, max_cost - best_cost)
		entries = []
		for (surface, romaji), cost in costs.items():
			freq = int(round(out_max * (max_cost - min(cost, max_cost)) / span))
			entries.append((surface, romaji, max(1, min(out_max, freq))))

		attribution = [
			'Japanese: Mozc open-source dictionary, https://github.com/' + repo + ' (src/data/dictionary_oss)',
			'  license: BSD-3-Clause; the dictionary also carries the notices of its upstream sources, see Mozc\'s LICENSE',
		]
		return SourceResult(entries, fingerprint_of(self.http, urls), attribution)
