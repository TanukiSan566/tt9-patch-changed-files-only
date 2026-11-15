"""
English word frequencies from the `wordfreq` Python package (https://github.com/rspeer/wordfreq): a blend of Wikipedia, subtitles,
news, books, web text, Twitter and Reddit. Gives a much broader and cleaner ranking than a single subtitle corpus.
Needs `pip install wordfreq` (see requirements.txt). Note: the package is no longer updated upstream (its data is from ~2021),
so this source adds breadth, it does not follow new vocabulary. A spelling filter (`reference_wordlist_url`) drops garbage.
"""
import re

from . import register, SourceResult, fingerprint_of
from .frequency_words import PROFANITY


@register('en-wordfreq')
class WordFreqEnglish:
	def __init__(self, cfg, http, alphabet=''):
		self.cfg, self.http, self.alphabet = cfg, http, alphabet

	def fetch(self):
		try:
			import wordfreq
		except ImportError as e:
			raise RuntimeError('wordfreq is not installed: pip install -r scripts/dict-update/requirements.txt') from e

		lang = self.cfg.get('lang', 'en')
		top_n = self.cfg.get('top_n', 150000)
		min_zipf = self.cfg.get('min_zipf', 2.5)      # 2.5 ~ once per 3 million words; 3 = once per million
		out_max = self.cfg.get('max_frequency', 200)
		min_len = self.cfg.get('min_word_length', 2)
		urls = []

		reference = None
		ref_url = self.cfg.get('reference_wordlist_url')
		if ref_url:
			urls.append(ref_url)
			reference = {w.strip().lower() for w in self.http.text(ref_url).split('\n') if w.strip()}

		excluded = {w.lower() for w in self.cfg.get('exclude_words', [])}
		if self.cfg.get('exclude_profanity', True):
			excluded |= PROFANITY.get(lang, set())

		valid = re.compile(r"^[a-z]+(?:['-][a-z]+)*$")
		entries = []
		for word in wordfreq.top_n_list(lang, top_n, wordlist=self.cfg.get('wordlist', 'large')):
			if len(word) < min_len or not valid.match(word) or word in excluded:
				continue
			if reference is not None and word not in reference and "'" not in word and '-' not in word:
				continue
			zipf = wordfreq.zipf_frequency(word, lang, wordlist=self.cfg.get('wordlist', 'large'))
			if zipf < min_zipf:
				continue
			freq = max(1, min(out_max, int(round((zipf - min_zipf) / (7.7 - min_zipf) * out_max))))
			if word.startswith("i'"):
				word = 'I' + word[1:]  # I'm, I'll, I've, I'd
			entries.append((word, '', freq))

		version = getattr(wordfreq, '__version__', None)
		if version is None:
			try:
				from importlib.metadata import version as pkg_version
				version = pkg_version('wordfreq')
			except Exception:
				version = 'unknown'
		fingerprint = fingerprint_of(self.http, urls) + '-wf' + str(version) + f'-{top_n}-{min_zipf}'
		attribution = [f'{lang}: wordfreq {version}, https://github.com/rspeer/wordfreq',
			'  license: code Apache-2.0, data CC-BY-SA-4.0 (see the repository for the sources it combines)']
		if ref_url:
			attribution.append('  spelling filter: ' + ref_url)
		return SourceResult(entries, fingerprint, attribution)
