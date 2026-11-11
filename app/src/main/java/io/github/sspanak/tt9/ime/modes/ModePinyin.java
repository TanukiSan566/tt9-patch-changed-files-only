package io.github.sspanak.tt9.ime.modes;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;

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
