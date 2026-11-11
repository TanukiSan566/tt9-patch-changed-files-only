package io.github.sspanak.tt9.ime.modes;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.sspanak.tt9.hacks.InputType;
import io.github.sspanak.tt9.ime.helpers.TextField;
import io.github.sspanak.tt9.ime.modes.predictions.IdeogramPredictions;
import io.github.sspanak.tt9.languages.Language;
import io.github.sspanak.tt9.languages.LanguageKind;
import io.github.sspanak.tt9.preferences.settings.SettingsStore;
import io.github.sspanak.tt9.util.Logger;
import io.github.sspanak.tt9.util.Text;

public class ModeIdeograms extends ModeWords {
	private static final String LOG_TAG = ModeIdeograms.class.getSimpleName();
	protected String NAME;

	protected boolean isFiltering = false;
	@NonNull private String lastAcceptedSequence = "";
	@NonNull private String lastAcceptedWord = "";
	@NonNull private String lastTextBeforeDelete = "";


	protected ModeIdeograms(@NonNull SettingsStore settings, @NonNull Language lang, @Nullable InputType inputType, @Nullable TextField textField) {
		super(settings, lang, inputType, textField);
		NAME = super.toString();
	}


	@Override protected String adjustSuggestionTextCase(String word, int newTextCase) { return word; }
	@Override public void determineNextWordTextCase(@Nullable String beforeCursor, int nextDigit) {}
	@Override public boolean nextTextCase(@Nullable String currentWord, int displayTextCase) { return false; }


	@Override
	public boolean validateLanguage(@Nullable Language newLanguage) {
		return newLanguage != null && newLanguage.isTranscribed() && !LanguageKind.isKorean(newLanguage);
	}


	@Override
	public void reset() {
		super.reset();
		isFiltering = false;
	}

	/******************************* LOAD SUGGESTIONS *********************************/

	@Override
	protected void initPredictions() {
		predictions = new IdeogramPredictions(settings);
		predictions.setWordsChangedHandler(this::onPredictions);
	}


	@Override
	protected void onPredictions() {
		if (language.hasTranscriptionsEmbedded()) {
			if (!isFiltering) {
				filterPredictions((IdeogramPredictions) predictions);
			}

			if (isFiltering) {
				((IdeogramPredictions) predictions).stripNativeWords();
			} else {
				((IdeogramPredictions) predictions).stripTranscriptions();
			}
		}

		if (!isFiltering) {
			// We can reorder by pairs only after stripping the transcriptions, if any.
			// Otherwise, the input field words will not match with any pair.
			((IdeogramPredictions) predictions).orderByPairs();

			if (language.hasTranscriptionsEmbedded()) {
				onSoundsReady((IdeogramPredictions) predictions);
			}
		}

		super.onPredictions();
	}


	/**
	 * Customized build: a chance for the language-specific modes to drop words that do not match what
	 * the user typed. Called with the transcriptions still attached to the words, e.g. "BIAN币安".
	 */
	protected void filterPredictions(@NonNull IdeogramPredictions ideogramPredictions) {}


	/**
	 * Customized build: called when the words are ready for display. The transcriptions are still
	 * available in the predictions, so the language-specific modes can build a list of sounds.
	 */
	protected void onSoundsReady(@NonNull IdeogramPredictions ideogramPredictions) {}


	@Override
	public void beforeDeleteText() {
		if (textField == null) {
			lastTextBeforeDelete = "";
			return;
		}

		final String textBefore = textField.getComposingText();
		lastTextBeforeDelete = textBefore.isEmpty() ? textField.getTextBeforeCursor(language, 1).toString() : textBefore;
	}


	@Override
	public String recompose() {
		if (textField == null || lastAcceptedWord.isEmpty()) {
			return null;
		}

		Text before = textField.getTextBeforeCursor(language, lastAcceptedWord.length());
		char after = lastTextBeforeDelete.isEmpty() ? 0 : lastTextBeforeDelete.charAt(0);
		if (lastAcceptedWord.equals(before.toString()) && Character.isWhitespace(after)) {
			reset();
			digitSequence = lastAcceptedSequence;
			return lastAcceptedWord;
		} else {
			Logger.d(LOG_TAG, "Not recomposing word: '" + before + "' != last word: '" + lastAcceptedWord + "' and followed by: '" + after + "'");
			return null;
		}
	}

	/******************************* ACCEPT WORDS *********************************/

	@Override
	public void onAcceptSuggestion(@NonNull String currentWord, boolean preserveWords) {
		final Text text = new Text(currentWord);
		if (text.isEmpty() || text.startsWithWhitespace() || text.isNumeric() || !language.isValidWord(currentWord)) {
			reset();
			Logger.i(LOG_TAG, "Current word: '" + currentWord + "' is empty, numeric or invalid. Nothing to accept.");
			return;
		}

		if (isFiltering) {
			isFiltering = false;
			stem = currentWord;
			loadSuggestions("");
			return;
		}

		final int initialLength = digitSequence.length();
		boolean lastDigitBelongsToNewWord = preserveWords && initialLength >= 2;

		try {
			if (!seq.isAnySpecialCharSequence(digitSequence)) {
				lastAcceptedWord = currentWord;
				lastAcceptedSequence = lastDigitBelongsToNewWord ? digitSequence.substring(0, initialLength - 1) : digitSequence;

				predictions.setDigitSequence(lastAcceptedSequence);
				((IdeogramPredictions) predictions).onAcceptIdeogram(currentWord);
			}
		} catch (Exception e) {
			Logger.e(LOG_TAG, "Failed incrementing priority of word: '" + currentWord + "'. " + e.getMessage());
			lastAcceptedSequence = lastAcceptedWord = "";
		}

		if (lastDigitBelongsToNewWord) {
			digitSequence = digitSequence.substring(initialLength - 1);
			loadSuggestions("");
		} else {
			reset();
		}
	}


	@Override
	public boolean shouldAcceptPreviousSuggestion(String s) {
		return
			digitSequence.length() > 1
			&& predictions.noDbWords()
			&& !seq.isAnySpecialCharSequence(digitSequence)
			&& !digitSequence.startsWith(seq.EMOJI_SEQUENCE);
	}


	/**
	 * When we want to filter by a Latin transcription, we must have discarded it from the text field,
	 * then give it to this method. It will filter the suggestions and show only the ones that match
	 * the given Latin word.
	 */
	@Override
	public boolean onReplaceSuggestion(@NonNull String word) {
		if (word.isEmpty() || new Text(word).isNumeric()) {
			reset();
			Logger.i(LOG_TAG, "Can not replace an empty or numeric word.");
			return false;
		}

		if (super.onReplaceSuggestion(word)) {
			return true;
		}

		isFiltering = false;
		stem = word;
		loadSuggestions("");
		return true;
	}


	/**
	 * This should be called before accepting a word. It says whether we should discard the current
	 * word. Discarding it means we want to erase it from the text field and instead display a
	 * filtered list of suggestions that matches the word. If we don't discard it, usually we should
	 * accept it.
	 */
	@Override
	public boolean shouldReplacePreviousSuggestion(@Nullable String word) {
		return isFiltering || super.shouldReplacePreviousSuggestion(word);
	}

	/********************************* FILTERING *********************************/

	@Override
	public boolean clearWordStem() {
		if (!supportsFiltering()) {
			return false;
		}

		isFiltering = false;
		stem = "";
		return true;
	}


	@Override
	public boolean setWordStem(String newStem, boolean fromScrolling) {
		if (!supportsFiltering()) {
			return false;
		}

		if (!fromScrolling) {
			// Customized build: forget the previously chosen sound, so all the sounds for the typed keys are
			// listed again, e.g. both "DA" and "FA", not only the one we have already chosen.
			stem = "";
			isFiltering = true;
		} else if (isFiltering) {
			stem = newStem;
		}

		return true;
	}


	@Override
	public boolean supportsFiltering() {
		return language.hasTranscriptionsEmbedded();
	}


	@Override
	public boolean isShowingSounds() {
		return isFiltering;
	}


	@Override
	public boolean cancelSounds() {
		if (!isFiltering) {
			return false;
		}

		isFiltering = false;
		stem = "";
		return true;
	}


	@Override
	public boolean isStemFilterFuzzy() {
		return isFiltering;
	}


	@Override public void onCursorMove(@NonNull String word) {
		isFiltering = false;
		super.onCursorMove(word);
	}

	/********************************* NAME *********************************/

	@NonNull
	@Override
	public String toString() {
		return NAME;
	}
}
