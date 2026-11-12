"""
Shared helpers for the dictionary updater: reading / writing TT9 CSV dictionaries, merging fresh words into them,
and a Python mirror of the checks that `./gradlew validateLanguages` performs, so that a broken dictionary is
caught before it is ever committed.

TT9 dictionary line format (TAB separated, no spaces, no empty lines):
	alphabetic languages:   word [TAB frequency]
	transcribed languages:  word TAB TRANSCRIPTION [TAB frequency]      (Chinese pinyin, Japanese romaji)
Frequency is an integer in 0..9999. A missing frequency means 0. A (word, transcription) pair must be unique.
"""
import math
import re
import unicodedata
from pathlib import Path

MAX_WORD_FREQUENCY = 9999
MAX_ERRORS = 50
TRANSCRIPTION = re.compile(r'^[a-zA-Z]+$')


# ----------------------------------------------------------------------------------------------------------------
# language definition (mirror of parseLanguageDefintion in app/validate-languages.gradle)
# ----------------------------------------------------------------------------------------------------------------

class Definition:
	def __init__(self, path: Path):
		self.path = Path(path)
		self.name = self.path.name
		self.alphabet = ''
		self.sounds = {}
		self.layout_sounds = {}
		self.has_sounds = False
		self.locale = ''
		self.dictionary_file = ''
		self._parse()

	@staticmethod
	def _is_layout_line(line):
		return bool(re.match(r'\s+- \[.*?\].*', line)) and not re.search(r'\d+]', line)

	def _parse(self):
		layout_key = 0
		for raw in self.path.read_text(encoding='utf-8').splitlines():
			line = re.sub(r'#[\s\S]+$', '', raw)
			if line.startswith('sounds'):
				self.has_sounds = True
			if line.startswith('locale'):
				self.locale = line.replace('locale:', '').strip()
			if line.startswith('dictionaryFile'):
				self.dictionary_file = line.replace('dictionaryFile:', '').strip()

			chars = self._alphabet_chars(line)
			if not chars:
				chars = self._alphabet_extra_chars(line)
			self.alphabet += chars
			for ch in chars:
				self.layout_sounds[ch] = str(layout_key)
			if self._is_layout_line(line):
				layout_key += 1

			m = re.match(r'\s+- \[(\w+)\s*,\s*(\d+)\].*', line)
			if m:
				self.sounds[m.group(1)] = m.group(2)

		if not self.has_sounds:
			for ch, seq in self.layout_sounds.items():
				self.sounds[ch.upper()] = seq

	def _alphabet_chars(self, line):
		if 'PUNCTUATION' in line or 'SPECIAL' in line or not self._is_layout_line(line):
			return ''
		return re.sub(r'\].*', '', re.sub(r'^\s+- \[', '', line)).replace(',', '').replace(' ', '')

	def _alphabet_extra_chars(self, line):
		if 'PUNCTUATION' not in line or not self._is_layout_line(line):
			return ''
		default = "'-."
		if 'Korean' in self.name:
			return default
		if 'Hebrew' in self.name or 'Yiddish' in self.name:
			return default + '"'
		chars = re.sub(r'\].*', '', line, count=1)
		chars = re.sub(r'^\s+- \[', '', chars)
		chars = re.sub(r'PUNCTUATION[^,\s]*', '', chars).replace(',', '').replace(' ', '')
		if 'PUNCTUATION_FA' in line or 'PUNCTUATION_IN' in line:
			chars += '\u200c'
		if 'Japanese' in self.name:
			chars += '〇'
		return default + chars

	@property
	def is_alphabetic(self):
		return not self.has_sounds

	@property
	def valid_chars_regex(self):
		safe = re.sub(r'[\[\]\-.]', '', self.alphabet)
		if self.alphabet.upper() == self.alphabet:
			return re.compile('^[' + safe + r'.\-]+$')
		return re.compile('^[' + safe + safe.upper() + r'.\-]+$')

	def digit_sequence(self, word, transcribed):
		"""Mirror of DictionaryTools.wordToDigitSequence. Raises ValueError when the word cannot be typed."""
		text = word if transcribed else word.upper()
		seq, sound = [], ''
		for i, ch in enumerate(text):
			nxt = text[i + 1] if i + 1 < len(text) else ''
			sound += ch
			if not transcribed or i == len(text) - 1 or nxt.isupper():
				if sound not in self.sounds:
					raise ValueError(f"'{sound}' is not in the language sound list")
				seq.append(self.sounds[sound])
				sound = ''
		if not seq:
			raise ValueError('the word does not contain any valid sounds')
		return ''.join(seq)


# ----------------------------------------------------------------------------------------------------------------
# dictionary
# ----------------------------------------------------------------------------------------------------------------

class Entry:
	__slots__ = ('word', 'trans', 'freq', 'raw')

	def __init__(self, word, trans, freq, raw=None):
		self.word, self.trans, self.freq, self.raw = word, trans, freq, raw

	def line(self):
		if self.raw is not None:
			return self.raw
		parts = [self.word]
		if self.trans:
			parts.append(self.trans)
		if self.freq > 0:
			parts.append(str(self.freq))
		return '\t'.join(parts)


def parse_line(line):
	parts = line.split('\t', 2)
	word = parts[0]
	trans = parts[1] if len(parts) > 1 and TRANSCRIPTION.match(parts[1]) else ''
	idx = 2 if trans else 1
	try:
		freq = int(parts[idx]) if len(parts) > idx else 0
	except ValueError:
		freq = -1
	return word, trans, freq


class Dictionary:
	def __init__(self, path: Path):
		self.path = Path(path)
		self.entries = []
		self.index = {}      # (word, trans) -> Entry
		self.lower_words = set()  # lower-cased words, to avoid adding "Foo" next to "foo"
		self.trailing_newline = True
		self._load()

	def _load(self):
		text = self.path.read_text(encoding='utf-8')
		self.trailing_newline = text.endswith('\n')
		for line in text.split('\n')[:-1] if self.trailing_newline else text.split('\n'):
			word, trans, freq = parse_line(line)
			entry = Entry(word, trans, freq, raw=line)
			self.entries.append(entry)
			self.index.setdefault((word, trans), entry)
			self.lower_words.add(word.lower())

	def __len__(self):
		return len(self.entries)

	def add(self, word, trans, freq):
		entry = Entry(word, trans, freq)
		self.entries.append(entry)
		self.index[(word, trans)] = entry
		self.lower_words.add(word.lower())
		return entry

	def set_freq(self, entry, freq):
		entry.freq = freq
		entry.raw = None

	def text(self):
		# keep the file's original ending, so unchanged dictionaries produce no diff at all
		return '\n'.join(e.line() for e in self.entries) + ('\n' if self.trailing_newline else '')

	def save(self):
		self.path.write_text(self.text(), encoding='utf-8', newline='\n')


# ----------------------------------------------------------------------------------------------------------------
# validation (mirror of validateDictionary in app/validate-languages.gradle)
# ----------------------------------------------------------------------------------------------------------------

def entry_error(definition: Definition, line: str):
	"""Returns a message when a dictionary line breaks the rules of `./gradlew validateLanguages`, otherwise None."""
	valid = definition.valid_chars_regex
	word, trans, freq = parse_line(line)

	if line == '':
		return 'empty line'
	if ' ' in line:
		return f'contains a space: {line!r}'
	if freq < 0 or freq > MAX_WORD_FREQUENCY:
		return f'frequency out of range: {line!r}'
	if re.fullmatch(r'(\d.+?|.+?\d|\d)', word):
		return f'numbers in word {word!r}'
	if not any(unicodedata.category(c).startswith('L') for c in word) and word not in definition.alphabet:
		return f'garbage word {word!r}'
	if definition.is_alphabetic and len(word.strip()) == 1:
		return f'single letter {word!r}'
	if definition.is_alphabetic and not valid.match(word):
		return f'word {word!r} has characters outside of the alphabet'
	try:
		definition.digit_sequence(trans or word, bool(trans))
	except ValueError as e:
		return f'cannot type {word!r} [{trans}]: {e}'
	return None


def validate(dictionary: Dictionary, definition: Definition, max_errors=MAX_ERRORS):
	errors = []
	seen = set()
	for n, entry in enumerate(dictionary.entries, start=1):
		if len(errors) >= max_errors:
			break
		line = entry.line()
		error = entry_error(definition, line)
		if error:
			errors.append(f'line {n}: {error}')
			continue
		word, trans, _ = parse_line(line)
		if trans + word in seen:
			errors.append(f'line {n}: duplicate {word!r} [{trans}]')
		seen.add(trans + word)
	return errors


# ----------------------------------------------------------------------------------------------------------------
# frequency helpers
# ----------------------------------------------------------------------------------------------------------------

def log_scale(value, low, high, out_max):
	"""Maps value in (low..high] on a logarithmic scale to 1..out_max. value <= low gives 0."""
	if value <= low or high <= low:
		return 0
	ratio = (math.log(value) - math.log(low)) / (math.log(high) - math.log(low))
	return max(1, min(out_max, int(round(ratio * out_max))))
