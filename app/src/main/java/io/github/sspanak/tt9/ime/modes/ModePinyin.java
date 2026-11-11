package io.github.sspanak.tt9.ime.modes;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

import io.github.sspanak.tt9.hacks.InputType;
import io.github.sspanak.tt9.ime.helpers.TextField;
import io.github.sspanak.tt9.ime.modes.helpers.PinyinSyllables;
import io.github.sspanak.tt9.ime.modes.predictions.IdeogramPredictions;
import io.github.sspanak.tt9.languages.Language;
import io.github.sspanak.tt9.languages.LanguageKind;
import io.github.sspanak.tt9.preferences.settings.SettingsStore;
import io.github.sspanak.tt9.util.chars.Characters;

public class ModePinyin extends ModeIdeograms {
	// Customized build: the syllable separators ("1" key). Each number is the count of letters typed
	// before the separator. For "bi'an", it is [2].
	private ArrayList<Integer> syllableBreaks = new ArrayList<>();

	// Customized build: the Pinyin row above the words. The sounds are the ones the typed keys can make,
	// for example [bian, biao, chao, chan]. "soundIdx" is the highlighted one. While "selectingSound" is on,
	// the words below are only the ones of the highlighted sound and none of them is highlighted. While
	// "adjustingSound" is on, there is a "|" cursor inside the sound, at the "soundCursor" letter.
	private ArrayList<String> sounds = new ArrayList<>();
	private int soundIdx = 0;
	private boolean selectingSound = false;
	private boolean adjustingSound = false;
	private int soundCursor = 0;


	protected ModePinyin(SettingsStore settings, Language lang, InputType inputType, TextField textField) {
		super(settings, lang, inputType, textField);
	}


	@Override
	public boolean validateLanguage(@Nullable Language newLanguage) {
		return LanguageKind.isChinesePinyin(newLanguage);
	}


	@Override
	protected String getPreferredChar() {
		return Characters.getChar(language, settings.getDoubleZeroChar());
	}

	@NonNull
	@Override
	public String toAccessibilityString(@NonNull Context ctx) {
		return language.getName() + ", 拼音";
	}


	/******************************* SYLLABLE SEPARATOR *********************************/

	/**
	 * "1" is the syllable separator while typing Pinyin: "bi'an" (币安) instead of "bian" (变).
	 * It does not add a digit, it only remembers where one syllable ends.
	 */
	@Override
	public boolean onNumber(int number, boolean hold, int repeat, @NonNull String[] surroundingChars) {
		if (selectingSound) {
			if (number == 1 && !hold) {
				onSyllableKeyWhileSelecting();
				return true;
			}

			// any other key continues typing
			selectingSound = false;
			adjustingSound = false;
		}

		if (number == 1 && !hold && canAddSyllableBreak()) {
			syllableBreaks.add(digitSequence.length());
			return true;
		}

		return super.onNumber(number, hold, repeat, surroundingChars);
	}


	private boolean canAddSyllableBreak() {
		return
			syllableBreaks != null
			&& !digitSequence.isEmpty()
			&& !isFiltering
			&& !seq.isAnySpecialCharSequence(digitSequence)
			&& !digitSequence.startsWith(seq.EMOJI_SEQUENCE)
			&& (syllableBreaks.isEmpty() || syllableBreaks.get(syllableBreaks.size() - 1) != digitSequence.length());
	}


	@Override
	protected void filterPredictions(@NonNull IdeogramPredictions ideogramPredictions) {
		if (syllableBreaks == null || syllableBreaks.isEmpty()) {
			return;
		}

		final ArrayList<Integer> breaks = new ArrayList<>(syllableBreaks);
		ideogramPredictions.filterTranscriptions(word -> PinyinSyllables.matches(word, breaks));
	}


	@Override
	public boolean onBackspace() {
		// the first Backspace removes the separator, the next ones remove letters
		if (syllableBreaks != null && !syllableBreaks.isEmpty() && syllableBreaks.get(syllableBreaks.size() - 1) == digitSequence.length()) {
			syllableBreaks.remove(syllableBreaks.size() - 1);
			return true;
		}

		final boolean result = super.onBackspace();
		trimSyllableBreaks();
		return result;
	}


	@Override
	public void onAcceptSuggestion(@NonNull String currentWord, boolean preserveWords) {
		final int lengthBefore = digitSequence.length();
		super.onAcceptSuggestion(currentWord, preserveWords);

		if (syllableBreaks == null || syllableBreaks.isEmpty()) {
			return;
		}

		if (digitSequence.isEmpty()) {
			syllableBreaks.clear();
			return;
		}

		// A part of the sequence was accepted as a word and typing continues with the rest.
		final int accepted = lengthBefore - digitSequence.length();
		if (accepted > 0) {
			final ArrayList<Integer> shifted = new ArrayList<>();
			for (int position : syllableBreaks) {
				if (position > accepted) {
					shifted.add(position - accepted);
				}
			}
			syllableBreaks = shifted;
		}
	}


	@Override
	public void reset() {
		super.reset();
		if (syllableBreaks != null) {
			syllableBreaks.clear();
		}

		selectingSound = false;
		adjustingSound = false;
		if (sounds != null) {
			sounds.clear();
		}
	}


	/******************************* PINYIN ROW *********************************/

	@Override
	protected void onSoundsReady(@NonNull IdeogramPredictions ideogramPredictions) {
		selectingSound = false;
		adjustingSound = false;
		soundCursor = 0;
		soundIdx = 0;
		sounds = ideogramPredictions.getSoundList();

		final ArrayList<String> words = ideogramPredictions.getList();
		if (!words.isEmpty()) {
			final int first = sounds.indexOf(ideogramPredictions.getTranscription(words.get(0)));
			soundIdx = Math.max(first, 0);
		}
	}


	@Override
	public boolean isSelectingSound() {
		return selectingSound;
	}


	@Nullable
	@Override
	public ArrayList<String> getSoundRow() {
		if (sounds == null || sounds.isEmpty() || digitSequence.isEmpty()) {
			return null;
		}

		final ArrayList<String> row = new ArrayList<>(sounds.size());
		for (int i = 0; i < sounds.size(); i++) {
			final String sound = sounds.get(i).toLowerCase(Locale.ROOT);
			if (selectingSound && adjustingSound && i == soundIdx) {
				final int cursor = Math.max(0, Math.min(soundCursor, sound.length()));
				row.add(sound.substring(0, cursor) + "|" + sound.substring(cursor));
			} else {
				row.add(PinyinSyllables.format(sound, syllableBreaks));
			}
		}

		return row;
	}


	@Override
	public int getSoundRowSelected() {
		return sounds != null && soundIdx < sounds.size() ? soundIdx : -1;
	}


	@Override
	public boolean enterSoundSelection() {
		if (sounds == null || sounds.isEmpty() || soundIdx >= sounds.size()) {
			return false;
		}

		selectingSound = true;
		adjustingSound = false;
		showSelectedSoundWords();
		return true;
	}


	/**
	 * Left / Right. Moves between the sounds, or the "|" cursor inside the sound.
	 */
	@Override
	public boolean moveSound(int direction) {
		if (!selectingSound || sounds == null || sounds.isEmpty()) {
			return false;
		}

		if (adjustingSound) {
			final int length = sounds.get(soundIdx).length();
			soundCursor = Math.max(1, Math.min(soundCursor + direction, length));
		} else {
			soundIdx = (soundIdx + direction + sounds.size()) % sounds.size();
		}

		showSelectedSoundWords();
		return true;
	}


	/**
	 * Down / OK. Leaves the sound selection and goes back to the words, which are now the ones of the sound.
	 */
	@Override
	public boolean confirmSound() {
		if (!selectingSound) {
			return false;
		}

		selectingSound = false;
		adjustingSound = false;
		onSuggestionsUpdated.run();
		return true;
	}


	/**
	 * Back / Backspace. First leaves the "|" adjustment, then the sound selection.
	 */
	@Override
	public boolean cancelSounds() {
		if (selectingSound) {
			if (adjustingSound) {
				adjustingSound = false;
			} else {
				selectingSound = false;
			}

			return true;
		}

		return super.cancelSounds();
	}


	/**
	 * While selecting a sound, the words must not be reloaded, because this would drop the choice.
	 */
	@Override
	public void loadSuggestions(String currentWord) {
		if (selectingSound && sounds != null && !sounds.isEmpty()) {
			showSelectedSoundWords();
			return;
		}

		super.loadSuggestions(currentWord);
	}


	private void showSelectedSoundWords() {
		if (sounds == null || soundIdx < 0 || soundIdx >= sounds.size()) {
			return;
		}

		final IdeogramPredictions ideogramPredictions = (IdeogramPredictions) predictions;
		ideogramPredictions.showTranscription(sounds.get(soundIdx));
		suggestions = ideogramPredictions.getList();
		onSuggestionsUpdated.run();
	}


	/**
	 * "1" while a sound is selected. The first press shows the "|" cursor at the end of the sound.
	 * The next press puts a separator at the cursor, for example: "bi|an" -> "bi'an".
	 */
	private void onSyllableKeyWhileSelecting() {
		if (!adjustingSound) {
			adjustingSound = true;
			soundCursor = sounds.get(soundIdx).length();
			return;
		}

		final int length = sounds.get(soundIdx).length();
		final boolean isInsideSound = soundCursor > 0 && soundCursor < length;
		adjustingSound = false;

		if (isInsideSound && syllableBreaks != null && soundCursor <= digitSequence.length()) {
			if (!syllableBreaks.contains(soundCursor)) {
				syllableBreaks.add(soundCursor);
				Collections.sort(syllableBreaks);
			}

			// the words are loaded again, using the new separator
			selectingSound = false;
		}
	}


	private void trimSyllableBreaks() {
		if (syllableBreaks == null || syllableBreaks.isEmpty()) {
			return;
		}

		if (digitSequence.isEmpty()) {
			syllableBreaks.clear();
			return;
		}

		syllableBreaks.removeIf(position -> position > digitSequence.length());
	}
}
