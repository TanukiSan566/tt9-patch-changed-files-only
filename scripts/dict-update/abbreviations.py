"""
Abbreviated pinyin ("简拼" / mixed pinyin) entries for the Chinese dictionary.

TT9 finds words by the digit sequence of their transcription, and the dictionary only has the full pinyin:
周杰伦 = ZHOUJIELUN (9-4-6-8-5-4-3-5-8-6). Typing the way people actually type, "zjl" (9-5-5) or "zhoujl" finds
nothing. This step adds extra rows for the words that are common enough:

	周杰伦	ZJL			initials of every syllable          ("initials")
	周杰伦	ZHOUJL		first syllable in full, the rest as initials   ("head_full")

The extra rows are ordinary (word, transcription, frequency) lines, so the app needs no change: when the typed digits
equal the digits of such a row, the word is offered, and picking it teaches the app the abbreviation like any other
word. Rows are only made from words that were already in the dictionary, so it is safe to run on every update:
already existing rows are kept and only get their frequency raised when the word became more common.

How a transcription is cut into syllables: the dictionary has no separators (ZHOUJIELUN), so the cut is searched
with the readings of each character, taken from the single-character rows of the same dictionary. Rows that cannot be
cut that way (including the abbreviation rows made earlier) are not used as a source.
"""
import re
from dictlib import entry_error

HAN = re.compile(r'^[\u4e00-\u9fff]+$')


def _char_syllables(dictionary):
	by_char, every = {}, set()
	for entry in dictionary.entries:
		if len(entry.word) == 1 and entry.trans and HAN.match(entry.word):
			by_char.setdefault(entry.word, set()).add(entry.trans)
			every.add(entry.trans)
	return by_char, every


def _cut(trans, word, by_char, every):
	"""Cuts 'ZHOUJIELUN' into one syllable per character of '周杰伦'. Returns a list of syllables or None."""
	n, length = len(word), len(trans)
	# no more than 6 letters per syllable (zhuang, chuang, shuang) and at least 1 per character
	if length < n or length > n * 6:
		return None

	def search(i, pos):
		if i == n:
			return [] if pos == length else None
		rest = n - i - 1
		for known in (by_char.get(word[i], ()), every):
			for end in range(min(length - rest, pos + 6), pos, -1):  # longest syllable first
				syllable = trans[pos:end]
				if syllable in known:
					tail = search(i + 1, end)
					if tail is not None:
						return [syllable] + tail
		return None

	return search(0, 0)


def build(dictionary, cfg):
	"""Returns [(word, abbreviation, frequency)] to add, best words first."""
	patterns = cfg.get('patterns', ['initials', 'head_full'])
	min_len, max_len = cfg.get('min_word_length', 3), cfg.get('max_word_length', 6)
	min_freq = cfg.get('min_frequency', 60)
	# the dictionary has many phrase fragments (在充分, 给朋友, 增加了) that would crowd out real words under one abbreviation
	skip_prefixes = tuple(cfg.get('skip_prefixes', '在把被给让从向与就'))
	skip_suffixes = tuple(cfg.get('skip_suffixes', '了的地着过吗呢吧么'))
	# two-character words (北京 = BJ) are the most ambiguous ones: BJ shares its keys with many other abbreviations
	# and with real syllables (WM = 96 = WO), so only the very common ones are taken
	two_min_freq = cfg.get('two_character_min_frequency', 150)
	max_two = cfg.get('two_character_max_words', 3000)
	factors = {'initials': cfg.get('initials_frequency_factor', 0.5), 'head_full': cfg.get('head_full_frequency_factor', 0.3)}
	max_entries = cfg.get('max_entries', 80000)

	by_char, every = _char_syllables(dictionary)
	candidates, seen_two = [], 0

	sources = [e for e in dictionary.entries if e.trans and e.freq >= min(min_freq, two_min_freq) and 2 <= len(e.word) <= max_len and HAN.match(e.word)]
	sources.sort(key=lambda e: -e.freq)

	for entry in sources:
		n = len(entry.word)
		if n >= 3 and (entry.word.startswith(skip_prefixes) or entry.word.endswith(skip_suffixes)):
			continue
		if n == 2:
			if entry.freq < two_min_freq or seen_two >= max_two:
				continue
		elif n < min_len or entry.freq < min_freq:
			continue

		syllables = _cut(entry.trans, entry.word, by_char, every)
		if not syllables:
			continue

		forms = []
		if 'initials' in patterns:
			forms.append(('initials', ''.join(s[0] for s in syllables)))
		if 'head_full' in patterns and n >= 3:
			forms.append(('head_full', syllables[0] + ''.join(s[0] for s in syllables[1:])))

		made = False
		for kind, abbreviation in forms:
			if abbreviation == entry.trans:
				continue
			candidates.append((entry.word, abbreviation, max(1, int(entry.freq * factors[kind]))))
			made = True
		if n == 2 and made:
			seen_two += 1

	best = {}
	for word, abbreviation, freq in candidates:
		key = (word, abbreviation)
		if freq > best.get(key, 0):
			best[key] = freq
	result = sorted(((w, a, f) for (w, a), f in best.items()), key=lambda c: -c[2])
	return result[:max_entries]


def apply(dictionary, definition, cfg):
	"""Adds the abbreviation rows to the dictionary. Returns (added, raised, rejected)."""
	added, raised, rejected = 0, 0, 0
	for word, abbreviation, freq in build(dictionary, cfg):
		entry = dictionary.index.get((word, abbreviation))
		if entry is not None:
			if freq > entry.freq:
				dictionary.set_freq(entry, freq)
				raised += 1
			continue
		if entry_error(definition, f'{word}\t{abbreviation}\t{freq}'):
			rejected += 1
			continue
		dictionary.add(word, abbreviation, freq)
		added += 1
	return added, raised, rejected
