"""
Japanese proper nouns and extra vocabulary from SudachiDict (WorksApplications): https://github.com/WorksApplications/SudachiDict
Adds people, places, brands and titles that Mozc's open-source dictionary lacks. The raw lexicon CSV files are published as
  https://d2ej7fkh96fzlu.cloudfront.net/sudachidict-raw/<YYYYMMDD>/{small,core,notcore}_lex.zip     (small + core + notcore = "Full")
Row layout (18 columns): 0 surface, 1 left-id, 2 right-id, 3 cost, 4 display form, 5..10 part of speech (pos1, pos2, pos3,
pos4, conjugation type, conjugation form), 11 reading (katakana), 12 normalized form, 13.. split information.
The katakana reading is converted to hiragana and then to TT9's romaji with kana.py, exactly like the Mozc source.
The newest dictionary date is read from the GitHub releases of the repository (set `version` in config.json to pin it).
A lower cost means a more common word.
"""
import io
import json
import os
import re
import urllib.request
import zipfile

from . import register, SourceResult, fingerprint_of
from kana import to_romaji

RAW_URL = 'https://d2ej7fkh96fzlu.cloudfront.net/sudachidict-raw/{version}/{edition}_lex.zip'
SURFACE = re.compile(r'^[\u3041-\u3096\u30a1-\u30fa\u30fc\u3400-\u4dbf\u4e00-\u9fff\u3005]+$')
KATAKANA = re.compile(r'^[\u30a1-\u30f4\u30fc]+$')


def katakana_to_hiragana(text):
	return ''.join(chr(ord(c) - 0x60) if '\u30a1' <= c <= '\u30f6' else c for c in text)


def latest_version(repo, token=None):
	"""'20250129' from the newest release tag (v20250129) of the repository."""
	req = urllib.request.Request(f'https://api.github.com/repos/{repo}/releases/latest', headers={'User-Agent': 'tt9-dictionary-updater'})
	if token:
		req.add_header('Authorization', f'Bearer {token}')
	with urllib.request.urlopen(req, timeout=60) as response:
		tag = json.load(response)['tag_name']
	m = re.search(r'(\d{8})', tag)
	if not m:
		raise RuntimeError(f'cannot find a date in the SudachiDict release tag {tag!r}')
	return m.group(1)


def parse_lexicon(text, pos_prefixes, max_cost, max_len):
	"""Yields (surface, hiragana reading, cost) for the rows that pass the filters."""
	for line in text.split('\n'):
		cols = line.split(',')
		if len(cols) < 13:
			continue
		surface, reading = cols[4] or cols[0], cols[11]
		try:
			cost = int(cols[3])
		except ValueError:
			continue
		if cost > max_cost or len(surface) > max_len or not SURFACE.match(surface) or not KATAKANA.match(reading):
			continue
		pos = ','.join(cols[5:8])
		if not any(pos.startswith(p) for p in pos_prefixes):
			continue
		yield surface, katakana_to_hiragana(reading), cost


@register('ja-sudachi')
class Sudachi:
	def __init__(self, cfg, http):
		self.cfg, self.http = cfg, http

	def fetch(self):
		repo = self.cfg.get('repo', 'WorksApplications/SudachiDict')
		version = self.cfg.get('version') or ''
		if not version:
			try:
				version = latest_version(repo, os.environ.get('GITHUB_TOKEN'))
			except Exception as e:
				version = self.cfg.get('fallback_version', '')
				if not version:
					raise RuntimeError(f'cannot find the newest SudachiDict version ({e}); set "version" in config.json') from e
		editions = self.cfg.get('editions', ['small', 'core', 'notcore'])
		pos_prefixes = self.cfg.get('pos_prefixes', ['名詞,固有名詞', '名詞,普通名詞'])
		max_cost = self.cfg.get('max_cost', 9000)
		best_cost = self.cfg.get('best_cost', 4000)
		out_max = self.cfg.get('max_frequency', 60)   # proper nouns must not outrank everyday words from Mozc
		max_len = self.cfg.get('max_word_length', 12)
		urls = [RAW_URL.format(version=version, edition=e) for e in editions]

		costs = {}
		for url in urls:
			with zipfile.ZipFile(io.BytesIO(self.http.get(url))) as z:
				for name in z.namelist():
					if not name.endswith('.csv'):
						continue
					text = z.read(name).decode('utf-8', errors='replace')
					for surface, reading, cost in parse_lexicon(text, pos_prefixes, max_cost, max_len):
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
		print(f'   ja-sudachi {version}: {len(entries)} candidates')

		attribution = [f'Japanese (extra): SudachiDict {version} ({"+".join(editions)}), https://github.com/{repo}',
			'  license: Apache-2.0 (code), the dictionary data is under the Sudachi / UniDic / NEologd notices, see the repository']
		return SourceResult(entries, fingerprint_of(self.http, urls), attribution)
