"""
Word sources for the dictionary updater.

A source turns some upstream data into a list of (word, transcription, frequency) tuples that the updater merges into
the matching TT9 dictionary. To support one more language, add a module here (copy `template.py`), decorate the class with
`@register('xx')` and enable the language in `config.json`.
"""
import hashlib
import time
import urllib.request
from pathlib import Path

REGISTRY = {}


def register(name):
	def wrap(cls):
		REGISTRY[name] = cls
		cls.source_name = name
		return cls
	return wrap


class SourceResult:
	def __init__(self, entries, fingerprint, attribution):
		self.entries = entries            # list of (word, transcription, frequency 0..9999)
		self.fingerprint = fingerprint    # changes whenever the upstream data changes
		self.attribution = attribution    # list of text lines: name, URL, version, license


class Http:
	"""A tiny cached downloader. Remembers the SHA-256 of everything it downloaded to build source fingerprints."""

	def __init__(self, cache_dir, offline=False):
		self.cache_dir = Path(cache_dir)
		self.cache_dir.mkdir(parents=True, exist_ok=True)
		self.offline = offline
		self.hashes = {}

	def get(self, url, retries=4):
		cached = self.cache_dir / hashlib.sha1(url.encode()).hexdigest()
		if self.offline and cached.exists():
			data = cached.read_bytes()
		else:
			data, last = None, None
			for attempt in range(retries):
				try:
					req = urllib.request.Request(url, headers={'User-Agent': 'tt9-dictionary-updater'})
					with urllib.request.urlopen(req, timeout=120) as response:
						data = response.read()
					break
				except Exception as e:  # network hiccups are common on CI
					last = e
					time.sleep(2 ** attempt)
			if data is None:
				raise RuntimeError(f'Could not download {url}: {last}')
			cached.write_bytes(data)
		self.hashes[url] = hashlib.sha256(data).hexdigest()
		return data

	def text(self, url):
		return self.get(url).decode('utf-8', errors='replace')


def fingerprint_of(http, urls):
	h = hashlib.sha256()
	for url in sorted(urls):
		h.update(http.hashes.get(url, '').encode())
	return h.hexdigest()[:16]


from . import zh_rime_ice, ja_mozc, frequency_words  # noqa: E402,F401  (registers the built-in sources)
