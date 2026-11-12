"""
Alphabetic languages from hermitdave/FrequencyWords (word frequency lists built from OpenSubtitles, ~60 languages):
https://github.com/hermitdave/FrequencyWords       Lines look like:  "word 12345"

Used for English out of the box. Every other alphabetic language works the same way: add an entry for it to config.json.
An optional `reference_wordlist_url` (one word per line) is used as a spelling filter, so that subtitle noise such as
"hmm", "uh" or misspellings is not added: a word is only accepted when it also appears in that reference list.
"""
import math
import re

from . import register, SourceResult, fingerprint_of

# Strong profanity that TT9's own English dictionary deliberately leaves out ("damn", "hell" are in it, these are not).
PROFANITY = {
	'en': {'fuck', 'fucked', 'fucker', 'fuckers', 'fucking', 'fucks', 'shit', 'shitty', 'shits', 'bullshit', 'bitch', 'bitches',
		'asshole', 'assholes', 'ass', 'dick', 'dicks', 'cock', 'pussy', 'cunt', 'whore', 'slut', 'motherfucker', 'dickhead'},
}

URL = 'https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/{year}/{lang}/{lang}_{size}.txt'


@register('frequencywords')
class FrequencyWords:
	def __init__(self, cfg, http, alphabet=''):
		self.cfg, self.http, self.alphabet = cfg, http, alphabet

	def fetch(self):
		lang = self.cfg['lang']
		url = self.cfg.get('url') or URL.format(year=self.cfg.get('year', 2018), lang=lang, size=self.cfg.get('size', 'full'))
		min_count = self.cfg.get('min_count', 50)
		min_len = self.cfg.get('min_word_length', 2)
		out_max = self.cfg.get('max_frequency', 200)
		urls = [url]

		reference = None
		ref_url = self.cfg.get('reference_wordlist_url')
		if ref_url:
			urls.append(ref_url)
			reference = {w.strip().lower() for w in self.http.text(ref_url).split('\n') if w.strip()}

		excluded = {w.lower() for w in self.cfg.get('exclude_words', [])}
		if self.cfg.get('exclude_profanity', False):
			excluded |= PROFANITY.get(lang, set())

		letters = re.escape(self.cfg.get('letters', ''.join(sorted(set(self.alphabet.lower()) - set("'-.")))) or 'a-z')
		valid = re.compile('^[' + letters + ']+$')

		counts = {}
		for line in self.http.text(url).split('\n'):
			parts = line.split(' ')
			if len(parts) != 2 or not parts[1].isdigit():
				continue
			word, count = parts[0].lower(), int(parts[1])
			if count < min_count or len(word) < min_len or not valid.match(word):
				continue
			if word in excluded:
				continue
			if reference is not None and word not in reference:
				continue
			counts[word] = counts.get(word, 0) + count

		top = max(counts.values()) if counts else 0
		entries = []
		for word, count in counts.items():
			ratio = (math.log(count) - math.log(min_count)) / (math.log(top) - math.log(min_count)) if top > min_count else 0
			entries.append((word, '', max(1, min(out_max, int(round(ratio * out_max))))))

		attribution = [
			f'{lang}: FrequencyWords (OpenSubtitles), https://github.com/hermitdave/FrequencyWords',
			'  license: see the repository README (content is CC-BY-SA-4.0 at the time of writing)',
		]
		if ref_url:
			attribution.append('  spelling filter: ' + ref_url)
		return SourceResult(entries, fingerprint_of(self.http, urls), attribution)
