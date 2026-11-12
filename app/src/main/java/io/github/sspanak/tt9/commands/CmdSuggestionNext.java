package io.github.sspanak.tt9.commands;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.ime.TraditionalT9;
import io.github.sspanak.tt9.ime.modes.InputModeKind;
import io.github.sspanak.tt9.languages.LanguageKind;

public class CmdSuggestionNext implements Command {
	public static final String ID = "key_next_suggestion";

	public String getId() { return ID; }
	public int getIcon() { return R.drawable.ic_dpad_right; }
	public int getName() { return R.string.function_next_suggestion; }


	@Override
	public boolean isAvailable(@Nullable TraditionalT9 tt9) {
		return tt9 != null && !tt9.getSuggestionOps().isEmpty();
	}


	public boolean run(@Nullable TraditionalT9 tt9) {
		if (tt9 == null || tt9.getSuggestionOps().isEmpty()) {
			return false;
		}

		final boolean backward = LanguageKind.isRTL(tt9.getLanguage());
		scrollSuggestions(tt9, backward);

		if (tt9.getMainView() != null) {
			tt9.getMainView().renderDynamicKeys();
		}

		return true;
	}


	public static void scrollSuggestions(@NonNull TraditionalT9 tt9, boolean backward) {
		// Customized build: while the Pinyin sounds are being selected, Left / Right move between the sounds
		if (tt9.getInputMode().isSelectingSound()) {
			tt9.getSuggestionOps().cancelDelayedAccept();
			tt9.getInputMode().moveSound(backward ? -1 : 1);
			return;
		}

		tt9.getSuggestionOps().cancelDelayedAccept();
		tt9.getSuggestionOps().flashArrow(backward);
		tt9.getSuggestionOps().scrollTo(backward ? -1 : 1);
		applyCurrentSuggestion(tt9);
	}


	/**
	 * Customized build: the "Down" key. Jumps to the next page of suggestions.
	 */
	public static void scrollSuggestionsPage(@NonNull TraditionalT9 tt9) {
		tt9.getSuggestionOps().cancelDelayedAccept();
		tt9.getSuggestionOps().flashArrow(false);
		tt9.getSuggestionOps().scrollPage();
		applyCurrentSuggestion(tt9);
	}


	/**
	 * Customized build: the "Up" key. Jumps to the previous page of suggestions.
	 * @return false, when we are already on the first page and nothing has changed.
	 */
	public static boolean scrollSuggestionsPagePrevious(@NonNull TraditionalT9 tt9) {
		tt9.getSuggestionOps().cancelDelayedAccept();
		if (!tt9.getSuggestionOps().scrollPagePrevious()) {
			return false;
		}

		tt9.getSuggestionOps().flashArrow(true);

		applyCurrentSuggestion(tt9);
		return true;
	}


	private static void applyCurrentSuggestion(@NonNull TraditionalT9 tt9) {
		tt9.getInputMode().setWordStem(tt9.getSuggestionOps().getCurrent(), true);
		if (InputModeKind.isRecomposing(tt9.getInputMode())) {
			tt9.getAppHacks().setComposingTextPartsWithHighlightedJoining(tt9.getInputMode().getWordStem() + tt9.getSuggestionOps().getCurrent(), tt9.getInputMode().getRecomposingSuffix());
		} else if (!tt9.getInputMode().isShowingSounds()) {
			// Customized build: sounds (Pinyin) are never written to the text field
			tt9.getAppHacks().setComposingTextWithHighlightedStem(tt9.getSuggestionOps().getCurrent(), tt9.getInputMode().getWordStem(), tt9.getInputMode().isStemFilterFuzzy());
		}
	}
}
