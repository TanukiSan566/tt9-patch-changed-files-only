"""
More Simplified Chinese (pinyin) sources for the updater, registered next to the ones in zh_extra.py:

  zh-zhwiki   felixonmars/fcitx5-pinyin-zhwiki: the titles of Chinese Wikipedia articles (people, places, organisations,
              works, terms) with pinyin, rebuilt by its author every month. The same release also has `web-slang`: a
              small hand-kept list of internet slang (e.g. 内卷, 躺平) which is the only "hot words" list of this kind
              that is actually maintained. https://github.com/felixonmars/fcitx5-pinyin-zhwiki
              Rows are "word TAB pinyin with spaces" and carry no weight, so the words are ranked by how common their
              characters are (the 8105 list of rime-ice) and only the best `max_words` are kept, without a frequency.
              Slang words get `slang_frequency`, so they are offered before the rank-less encyclopedia words.
              The pinyin there is machine generated, so it is flagged as such: a word that is already in the dictionary
              with a curated reading never gets a second, guessed one.

  zh-cedict   CC-CEDICT (MDBG), a community dictionary with hand-written pinyin, updated about weekly. Only the
              simplified word and its pinyin are used (the English glosses are not needed to type). The tone numbers are
              removed. Good for readings of polyphonic words and for words rime-ice does not have.
              https://www.mdbg.net/chinese/dictionary?page=cc-cedict  (license: CC BY-SA 4.0, attribution is written to
              docs/dictionaries/auto-update-sources.txt)
"""
import io
import math
import re
import urllib.request
import zipfile

from . import register, SourceResult, fingerprint_of
from .zh_extra import HAN, RIME_URL, _rime_rows

ZHWIKI_RELEASES = 'https://github.com/{repo}/releases/latest'
ZHWIKI_ASSETS = 'https://github.com/{repo}/releases/expanded_assets/{tag}'
ZHWIKI_FILE = 'https://github.com/{repo}/releases/download/{tag}/{asset}'
CEDICT_URL = 'https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.zip'
CEDICT_ROW = re.compile(r'^(\S+) (\S+) \[([^\]]*)\] /(.*)/\s*$')
CEDICT_SKIP_GLOSS = re.compile(r'^(?:\(?(?:old |archaic |erhua |Taiwan |Japanese )?variant of|also written|see |same as|abbr\. for)', re.I)


def _common_characters(http, cfg):
	"""character -> weight, from rime-ice's 8105 list. Used to keep only common simplified characters."""
	repo, branch = cfg.get('rime_repo', 'iDvel/rime-ice'), cfg.get('rime_branch', 'main')
	name = cfg.get('common_chars_file', '8105')
	weights = {}
	for row in _rime_rows(http, repo, branch, name):
		if len(row) >= 2 and len(row[0]) == 1:
			try:
				weight = int(row[2]) if len(row) > 2 else 0
			except ValueError:
				weight = 0
			weights[row[0]] = weights.get(row[0], 0) + max(weight, 0)
	if len(weights) < 5000:
		raise RuntimeError(f'only {len(weights)} common characters read from {name}.dict.yaml, the file format has probably changed')
	return weights, RIME_URL.format(repo=repo, branch=branch, name=name)


def _score(word, weights):
	return sum(math.log(weights[c] + 10) for c in word) / len(word)


def _letters(syllables):
	"""['hao3'] / ['xi', 'an'] -> 'HAO' / 'XIAN', or None when something is not plain pinyin."""
	result = []
	for s in syllables:
		s = re.sub(r'[0-5]$', '', s.strip().lower()).replace('u:', 'v').replace('ü', 'v')
		if not re.fullmatch('[a-z]+', s):
			return None
		result.append(s)
	return ''.join(result).upper()


def _open(url):
	req = urllib.request.Request(url, headers={'User-Agent': 'tt9-dictionary-updater'})
	return urllib.request.urlopen(req, timeout=120)


def _latest_release_assets(repo, names):
	"""Finds the files of the newest release of a GitHub repository without the API (so no rate limit applies).
	Returns {name: url}, where name is 'zhwiki' for 'zhwiki-20261018.dict.yaml', and the tag."""
	with _open(ZHWIKI_RELEASES.format(repo=repo)) as response:
		tag = response.geturl().rstrip('/').split('/')[-1]
		if not tag or tag == 'latest':
			raise RuntimeError(f'cannot find the newest release of {repo}')
	with _open(ZHWIKI_ASSETS.format(repo=repo, tag=tag)) as response:
		html = response.read().decode('utf-8', errors='replace')
	found = {}
	for asset in re.findall(r'/releases/download/[^/"]+/([^"/]+\.dict\.yaml)"', html):
		m = re.fullmatch(r'(.+?)-\d{8}\.dict\.yaml', asset)
		if m and m.group(1) in names:
			found[m.group(1)] = ZHWIKI_FILE.format(repo=repo, tag=tag, asset=asset)
	return found, tag


def _yaml_rows(text):
	in_body = False
	for line in text.split('\n'):
		if not in_body:
			in_body = line.strip() == '...'
			continue
		if line and not line.startswith('#'):
			yield line.rstrip('\r').split('\t')


@register('zh-zhwiki')
class ZhWiki:
	def __init__(self, cfg, http):
		self.cfg, self.http = cfg, http

	def fetch(self):
		repo = self.cfg.get('repo', 'felixonmars/fcitx5-pinyin-zhwiki')
		wanted = self.cfg.get('files', ['zhwiki', 'web-slang'])
		min_len, max_len = self.cfg.get('min_word_length', 3), self.cfg.get('max_word_length', 8)
		max_words = self.cfg.get('max_words', 100000)
		slang_frequency = self.cfg.get('slang_frequency', 60)
		weights, chars_url = _common_characters(self.http, self.cfg)

		assets, tag = _latest_release_assets(repo, set(wanted))
		missing = [n for n in wanted if n not in assets]
		if len(missing) == len(wanted):
			raise RuntimeError(f'release {tag} of {repo} has none of the files {wanted}')

		entries, urls, slang, no_reading = [], [chars_url], 0, 0
		for name in wanted:
			if name not in assets:
				print(f'   zh-zhwiki: release {tag} has no {name}, skipped')
				continue
			urls.append(assets[name])
			is_slang = 'slang' in name
			ranked = []
			for row in _yaml_rows(self.http.text(assets[name])):
				if len(row) < 2:
					continue
				word, pinyin = row[0].strip(), row[1].strip()
				if not (min_len <= len(word) <= max_len or (is_slang and 2 <= len(word) <= max_len)):
					continue
				if not HAN.match(word) or not all(c in weights for c in word):
					continue
				syllables = pinyin.split()
				if len(syllables) != len(word):
					no_reading += 1
					continue
				trans = _letters(syllables)
				if not trans:
					no_reading += 1
					continue
				if is_slang:
					entries.append((word, trans, slang_frequency))
					slang += 1
				else:
					ranked.append((-_score(word, weights), len(word), word, trans))
			ranked.sort()
			entries += [(w, t, 0) for _, _, w, t in ranked[:max_words]]

		print(f'   zh-zhwiki ({tag}): {len(entries)} words ({slang} slang), {no_reading} without a usable reading skipped')
		attribution = [f'Simplified Chinese (extra): fcitx5-pinyin-zhwiki {tag}, https://github.com/{repo}',
			'  Chinese Wikipedia titles + web slang; only words made of the 8105 common characters, pinyin as published there',
			'  license: Unlicense (the tool), the Wikipedia titles are CC BY-SA 4.0']
		result = SourceResult(entries, fingerprint_of(self.http, urls), attribution)
		result.generated = {(w, t) for w, t, f in entries if f == 0}
		return result


@register('zh-cedict')
class CcCedict:
	def __init__(self, cfg, http):
		self.cfg, self.http = cfg, http

	def fetch(self):
		url = self.cfg.get('url', CEDICT_URL)
		min_len, max_len = self.cfg.get('min_word_length', 2), self.cfg.get('max_word_length', 6)
		max_words = self.cfg.get('max_words', 120000)
		weights, chars_url = _common_characters(self.http, self.cfg)

		with zipfile.ZipFile(io.BytesIO(self.http.get(url))) as z:
			name = next((n for n in z.namelist() if n.endswith('.u8') or n.endswith('.txt')), None)
			if not name:
				raise RuntimeError('there is no text file in the CC-CEDICT archive')
			text = z.read(name).decode('utf-8', errors='replace')

		version = re.search(r'#! date=(\S+)', text)
		ranked, seen, skipped = [], set(), 0
		for line in text.split('\n'):
			if line.startswith('#'):
				continue
			m = CEDICT_ROW.match(line)
			if not m:
				continue
			simplified, syllables, glosses = m.group(2), m.group(3).split(), m.group(4).split('/')
			if not (min_len <= len(simplified) <= max_len) or not HAN.match(simplified):
				continue
			if not all(c in weights for c in simplified) or len(syllables) != len(simplified) or any(re.fullmatch(r'r[0-5]?', x, re.I) for x in syllables):
				skipped += 1  # also drops the erhua forms (这儿 = zhe4 r5), where "r" is a syllable of its own
				continue
			if all(CEDICT_SKIP_GLOSS.match(g.strip()) for g in glosses if g.strip()):
				skipped += 1
				continue
			trans = _letters(syllables)
			if not trans or (simplified, trans) in seen:
				continue
			seen.add((simplified, trans))
			ranked.append((-_score(simplified, weights), len(simplified), simplified, trans))
		ranked.sort()

		entries = [(w, t, 0) for _, _, w, t in ranked[:max_words]]
		print(f'   zh-cedict: {len(entries)} of {len(ranked)} eligible words ({skipped} variants / mismatches skipped)')
		attribution = ['Simplified Chinese (extra): CC-CEDICT, ' + url + (f' (dated {version.group(1)})' if version else ''),
			'  only the simplified word and its pinyin (tones removed); only words made of the 8105 common characters, no frequency',
			'  license: CC BY-SA 4.0, https://creativecommons.org/licenses/by-sa/4.0/ , (c) the CC-CEDICT contributors and MDBG']
		result = SourceResult(entries, fingerprint_of(self.http, [url, chars_url]), attribution)
		result.generated = set()  # the readings are hand-written
		return result
