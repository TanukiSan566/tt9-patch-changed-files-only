#!/usr/bin/env python3
"""
Refreshes the TT9 dictionaries (Chinese, English, Japanese, and any other language enabled in config.json) from fresh
open-source word lists. Used by .github/workflows/update-dictionaries.yml, but it also runs fine on a PC:

    python scripts/dict-update/update_dictionaries.py                 # all enabled languages
    python scripts/dict-update/update_dictionaries.py --languages zh  # only Chinese
    python scripts/dict-update/update_dictionaries.py --dry-run       # report only, write nothing

Merge rules (the existing dictionaries are never shrunk):
  * words that are already in the dictionary stay exactly as they are; only their frequency may go up
    (policy "max"), or be filled in when it was empty (policy "fill-missing"), or stay untouched ("none");
  * words that are new are appended at the end of the file, most frequent first, up to "max_new_words" per run;
  * every candidate is checked with a mirror of `./gradlew validateLanguages` before it is added.
"""
import argparse
import datetime
import hashlib
import json
import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import abbreviations  # noqa: E402
import dictlib  # noqa: E402
import sources  # noqa: E402


def make_source(name, cfg, http, definition):
	cls = sources.REGISTRY.get(name)
	if cls is None:
		raise SystemExit(f"Unknown source '{name}'. Registered sources: {', '.join(sorted(sources.REGISTRY))}")
	if name in ('frequencywords', 'en-wordfreq'):
		return cls(cfg, http, alphabet=definition.alphabet)
	return cls(cfg, http)


def fetch_all(code, lang, http, definition, failures):
	"""Fetches the main source and every `extra_sources` entry of a language and merges them into one candidate list
	(the highest frequency wins when a word comes from several sources). A failing extra source is reported, but does not
	stop the other sources: the language is still updated from the ones that worked."""
	try:
		main = make_source(lang['source'], lang.get('params', {}), http, definition).fetch()
	except Exception as e:
		failures.append(f'{code}: failed to fetch the source: {e}')
		return None

	best, order, generated = {}, [], set()

	def absorb(items):
		for word, trans, freq in items:
			key = (word, trans)
			if key not in best:
				order.append(key)
				best[key] = freq
			elif freq > best[key]:
				best[key] = freq

	absorb(main.entries)
	fingerprints, attribution = [main.fingerprint], list(main.attribution)
	for extra in lang.get('extra_sources', []):
		if extra.get('enabled', True) is False:
			continue
		name = extra['source']
		try:
			result = make_source(name, extra.get('params', {}), http, definition).fetch()
		except Exception as e:
			failures.append(f'{code}: extra source {name} failed: {e}')
			continue
		absorb(result.entries)
		generated |= getattr(result, 'generated', set())
		fingerprints.append(f'{name}:{result.fingerprint}')
		attribution += result.attribution

	# the settings are part of the fingerprint: after changing a threshold in config.json the next run merges again
	settings = json.dumps({'params': lang.get('params', {}), 'extra_sources': lang.get('extra_sources', []), 'max_new_words': lang.get('max_new_words')}, sort_keys=True)
	fingerprint = hashlib.sha256(('|'.join(fingerprints) + settings).encode()).hexdigest()[:16]
	entries = [(w, t, best[(w, t)]) for (w, t) in order]
	result = sources.SourceResult(entries, fingerprint, attribution)
	result.generated = generated
	return result


def merge(dictionary, definition, candidates, policy, max_new, generated=frozenset()):
	"""Merges candidates into the dictionary. Returns (added, updated, rejected) where added/updated are lists."""
	updated, new_words, rejected = [], [], 0
	transcribed = any(c[1] for c in candidates[:50])
	known_words = {k[0] for k in dictionary.index} if generated else set()

	for word, trans, freq in candidates:
		entry = dictionary.index.get((word, trans))
		if entry is not None:
			if policy == 'max' and freq > entry.freq:
				updated.append((entry, freq))
			elif policy == 'fill-missing' and entry.freq == 0 and freq > 0:
				updated.append((entry, freq))
			continue
		if (word, trans) in generated and word in known_words:
			continue  # the word is already there with a curated reading, do not add a machine-generated one next to it
		if not transcribed and word.lower() in dictionary.lower_words:
			continue  # "Foo" already there, do not add "foo" next to it
		new_words.append((word, trans, freq))

	# most frequent first; sorted() is stable, so ties keep the upstream order
	new_words = sorted(new_words, key=lambda c: -c[2])
	added = []
	for word, trans, freq in new_words:
		if len(added) >= max_new:
			break
		line = '\t'.join([word] + ([trans] if trans else []) + ([str(freq)] if freq > 0 else []))
		if dictlib.entry_error(definition, line):
			rejected += 1
			continue
		added.append((word, trans, freq))

	for entry, freq in updated:
		dictionary.set_freq(entry, freq)
	for word, trans, freq in added:
		dictionary.add(word, trans, freq)
	return added, updated, rejected


def main():
	ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
	ap.add_argument('--repo', default=str(HERE.parent.parent), help='repository root (default: the repo this script is in)')
	ap.add_argument('--languages', default='', help='comma separated list, e.g. zh,en. Default: everything enabled in config.json')
	ap.add_argument('--config', default=str(HERE / 'config.json'))
	ap.add_argument('--force', action='store_true', help='ignore state.json and refresh even if the sources have not changed')
	ap.add_argument('--dry-run', action='store_true', help='download and merge, but write nothing')
	ap.add_argument('--offline', action='store_true', help='use only the downloads that are already cached')
	ap.add_argument('--cache', default=str(HERE / '.cache'))
	ap.add_argument('--report', default='', help='write a Markdown report to this file')
	args = ap.parse_args()

	repo = Path(args.repo)
	definitions_dir = repo / 'app' / 'languages' / 'definitions'
	dictionaries_dir = repo / 'app' / 'languages' / 'dictionaries'
	state_file = HERE / 'state.json'
	state = json.loads(state_file.read_text(encoding='utf-8')) if state_file.exists() else {}
	config = json.loads(Path(args.config).read_text(encoding='utf-8'))
	max_growth = config.get('guards', {}).get('max_growth_ratio', 1.6)

	wanted = [x.strip() for x in args.languages.split(',') if x.strip()]
	http = sources.Http(args.cache, offline=args.offline)
	today = datetime.date.today().isoformat()

	report = [f'# Dictionary update {today}', '']
	attribution = {}
	changed_any = False
	failures = []

	for code, lang in config['languages'].items():
		if wanted and code not in wanted:
			continue
		if not wanted and not lang.get('enabled'):
			continue
		if code.startswith('_'):
			continue

		print(f'== {code}')
		definition = dictlib.Definition(definitions_dir / lang['definition'])
		dictionary_path = dictionaries_dir / definition.dictionary_file
		if not dictionary_path.exists():
			failures.append(f'{code}: dictionary file {dictionary_path} does not exist')
			continue

		result = fetch_all(code, lang, http, definition, failures)
		if result is None:
			continue
		attribution[code] = result.attribution

		abbreviation_cfg = lang.get('abbreviations') or {}
		abbreviations_on = bool(abbreviation_cfg) and abbreviation_cfg.get('enabled', True)
		if abbreviations_on:
			# the abbreviation settings are part of the fingerprint too: changing them makes the next run rebuild the rows
			result.fingerprint = hashlib.sha256((result.fingerprint + json.dumps(abbreviation_cfg, sort_keys=True)).encode()).hexdigest()[:16]

		if not args.force and state.get(code, {}).get('fingerprint') == result.fingerprint:
			print(f'   sources unchanged since {state[code].get("updated")}, skipping')
			report.append(f'## {code}\nSources unchanged since {state[code].get("updated")}, nothing to do.\n')
			continue

		dictionary = dictlib.Dictionary(dictionary_path)
		before = len(dictionary)
		added, updated, rejected = merge(
			dictionary, definition, result.entries, lang.get('frequency_policy', 'fill-missing'), lang.get('max_new_words', 0),
			getattr(result, 'generated', frozenset())
		)

		abbreviations_added = abbreviations_raised = abbreviations_rejected = 0
		if abbreviations_on:
			abbreviations_added, abbreviations_raised, abbreviations_rejected = abbreviations.apply(dictionary, definition, abbreviation_cfg)

		errors = dictlib.validate(dictionary, definition)
		if errors:
			failures.append(f'{code}: the merged dictionary is invalid, NOT written. First errors: ' + '; '.join(errors[:5]))
			continue
		growth = lang.get('max_growth_ratio', max_growth)
		if len(dictionary) < before or len(dictionary) > before * growth:
			failures.append(f'{code}: suspicious size change {before} -> {len(dictionary)}, NOT written')
			continue

		print(f'   {before} -> {len(dictionary)} words: {len(added)} new, {len(updated)} frequencies raised, {rejected} rejected')
		report += [
			f'## {code} ({lang["definition"]})',
			f'- words: {before:,} -> {len(dictionary):,}',
			f'- new words: {len(added):,}, frequencies raised: {len(updated):,}, rejected by validation: {rejected:,}',
			f'- source fingerprint: `{result.fingerprint}`',
			'- examples of new words: ' + ', '.join(w if not t else f'{w}[{t}]' for w, t, _ in added[:15]),
		]
		if abbreviations_on:
			report.append(f'- abbreviation rows (简拼/混拼, e.g. 周杰伦[ZJL]): {abbreviations_added:,} added, {abbreviations_raised:,} frequencies raised, {abbreviations_rejected:,} rejected by validation')
			print(f'   abbreviations: {abbreviations_added} added, {abbreviations_raised} raised, {abbreviations_rejected} rejected')
		report.append('')

		if added or updated or abbreviations_added or abbreviations_raised:
			changed_any = True
			if not args.dry_run:
				dictionary.save()
		if not args.dry_run:
			state[code] = {'fingerprint': result.fingerprint, 'updated': today}

	if failures:
		report += ['## Problems', *[f'- {f}' for f in failures], '']
		print('\nPROBLEMS:\n  ' + '\n  '.join(failures), file=sys.stderr)

	if not args.dry_run:
		state_file.write_text(json.dumps(state, indent=2, ensure_ascii=False, sort_keys=True) + '\n', encoding='utf-8')
		if attribution and not wanted:  # a partial run would drop the other languages from the notice
			notice = repo / 'docs' / 'dictionaries' / 'auto-update-sources.txt'
			notice.parent.mkdir(parents=True, exist_ok=True)
			lines = [
				'Words added by the automatic dictionary update (.github/workflows/update-dictionaries.yml).',
				'The sources below were merged into the dictionaries of the listed languages. Last run: ' + today, '',
			]
			for code, text in sorted(attribution.items()):
				lines += [f'[{code}]'] + text + ['']
			notice.write_text('\n'.join(lines), encoding='utf-8', newline='\n')

	if args.report:
		Path(args.report).write_text('\n'.join(report), encoding='utf-8')

	github_output = os.environ.get('GITHUB_OUTPUT')
	if github_output:
		with open(github_output, 'a', encoding='utf-8') as f:
			f.write(f'changed={"true" if changed_any else "false"}\n')
			f.write(f'failed={"true" if failures else "false"}\n')

	sys.exit(1 if failures else 0)


if __name__ == '__main__':
	main()
