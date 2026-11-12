"""
TEMPLATE for adding the word source of one more language. Copy this file, rename it, and:

1. Implement fetch() so that it returns SourceResult(entries, fingerprint, attribution):
     entries      list of (word, transcription, frequency)
                  - alphabetic languages (German, French, ...): transcription is '' ;
                  - transcribed languages (Chinese pinyin, Japanese romaji, ...): transcription is the UPPER-CASE
                    latin transcription, one letter per sound, exactly like the existing dictionary of that language;
                  - frequency is an integer 0..9999 (TT9's own dictionaries stay below 255);
     fingerprint  any string that changes when the upstream data changes (use fingerprint_of(self.http, urls));
     attribution  list of text lines (name, URL, version, license) that go to docs/dictionaries/auto-update-sources.txt.
2. Register it:  @register('my-source')
3. Import the module in sources/__init__.py (last line).
4. Enable the language in config.json:  "de": { "enabled": true, "definition": "German.yml", "source": "my-source", ... }

For plain frequency lists of alphabetic languages you do NOT need a new module: the built-in "frequencywords" source
already supports about 60 languages, just add a config.json entry (see the disabled "de" example there).
"""
from . import register, SourceResult, fingerprint_of  # noqa: F401


# @register('my-source')
class MySource:
	def __init__(self, cfg, http, alphabet=''):
		self.cfg, self.http, self.alphabet = cfg, http, alphabet

	def fetch(self):
		raise NotImplementedError('Copy template.py and implement fetch() for your language.')
