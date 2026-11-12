package io.github.sspanak.tt9.ime;

import android.view.KeyEvent;

import java.util.ArrayList;

import io.github.sspanak.tt9.commands.CmdEditAdjacentLetter;
import io.github.sspanak.tt9.commands.CmdEditDuplicateLetter;
import io.github.sspanak.tt9.commands.CmdFilterSuggestions;
import io.github.sspanak.tt9.commands.CmdShift;
import io.github.sspanak.tt9.commands.CmdSuggestionNext;
import io.github.sspanak.tt9.commands.CmdSpaceKorean;
import io.github.sspanak.tt9.commands.Command;
import io.github.sspanak.tt9.commands.CommandCollection;
import io.github.sspanak.tt9.ime.helpers.InputConnectionAsync;
import io.github.sspanak.tt9.ime.helpers.Key;
import io.github.sspanak.tt9.ime.helpers.TextField;
import io.github.sspanak.tt9.ime.modes.InputMode;
import io.github.sspanak.tt9.ime.modes.InputModeKind;
import io.github.sspanak.tt9.languages.LanguageKind;
import io.github.sspanak.tt9.util.Ternary;
import io.github.sspanak.tt9.util.chars.Characters;

public abstract class HotkeyHandler extends CommandHandler {
	private final CmdEditDuplicateLetter duplicateLetter = new CmdEditDuplicateLetter();
	private final CmdEditAdjacentLetter editAdjacentLetter = new CmdEditAdjacentLetter();
	private final CmdShift shift = new CmdShift();
	private final CmdSpaceKorean spaceKorean = new CmdSpaceKorean();

	private boolean waitingForSpaceTrim = false;

	// Customized build: the typing mode we return to, when "*" closes the Symbols mode
	private int modeBeforeSymbols = InputMode.MODE_PREDICTIVE;


	protected boolean isHoldHotkey(int keyCode) {
		return
			keyCode < 0
			&& (
				Key.isHotkey(settings, keyCode)
				|| (Key.isArrowLeft(-keyCode) && InputModeKind.isRecomposing(mInputMode))
				|| (Key.isArrowRight(-keyCode) && InputModeKind.isRecomposing(mInputMode))
			);
	}


	@Override
	protected void onInit() {
		super.onInit();
		if (settings.areHotkeysInitialized()) {
			settings.setDefaultKeys();
		}
	}


	@Override
	public Ternary onBack() {
		waitingForSpaceTrim = false;
		if (super.onBack() == Ternary.TRUE) {
			return Ternary.TRUE;
		} else if (settings.isMainLayoutLarge()) {
			return Ternary.ALTERNATIVE;
		} else {
			return Ternary.FALSE;
		}
	}


	@Override
	protected boolean onNumber(int key, boolean hold, int repeat) {
		stopWaitingForSpaceTrimKey();

		if (hold && onHotkey(-Key.numberToCode(settings, key), false, false)) {
			return true;
		}

		return super.onNumber(key, hold, repeat);
	}


	@Override public boolean onOK() {
		return onOK(KeyEvent.KEYCODE_ENTER);
	}


	protected boolean onOK(int fromKey) {
		suggestionOps.cancelDelayedAccept();
		stopWaitingForSpaceTrimKey();

		if (suggestionOps.containsOnlyGuesses()) {
			onAcceptSuggestionManually(suggestionOps.acceptCurrent(), fromKey);
			return true;
		} else if (!suggestionOps.isEmpty()) {
			if (mInputMode.isSelectingSound()) {
				// Customized build: "OK" confirms the Pinyin sound and goes to its words
				mInputMode.confirmSound();
			} else if (mInputMode.shouldReplacePreviousSuggestion(suggestionOps.getCurrent())) {
				mInputMode.onReplaceSuggestion(suggestionOps.getCurrentRaw());
			} else if (InputModeKind.isRecomposing(mInputMode)) {
				onAcceptSuggestionManually(suggestionOps.acceptEdited(), fromKey);
			} else {
				onAcceptSuggestionManually(suggestionOps.acceptCurrent(), fromKey);
			}

			return true;
		}

		int action = appHacks.getEditorAction(settings);

		boolean actionPerformed;

		if (action == TextField.IME_ACTION_ENTER) {
			actionPerformed = appHacks.onEnter();
			if (actionPerformed) {
				forceShowWindow();
			}

			// we don't want to use cached beforeCursor, because the action might have changed it
			updateShiftState(null, true, false);
			return actionPerformed;
		}

		actionPerformed = appHacks.onAction(action) || textField.performAction(action);
		if (!actionPerformed) {
			updateShiftState(null, true, false);
		}

		return actionPerformed;
	}


	public boolean onHotkey(int keyCode, boolean repeat, boolean validateOnly) {
		if (super.onHotkey(keyCode, repeat, validateOnly)) {
			return true;
		}

		if (keyCode == KeyEvent.KEYCODE_UNKNOWN || (keyCode < 0 && Key.isNumber(-keyCode) && !settings.getHoldToType())) {
			return false;
		}

		return
			onHardcodedKey(keyCode, validateOnly)
			|| onDynamicKey(keyCode, repeat, validateOnly);
	}


	private boolean onHardcodedKey(int keyCode, boolean validateOnly) {
		if (!validateOnly && Key.isArrow(keyCode)) {
			mindReader.clearContext();
		}

		if (Key.isArrowUp(keyCode) && duplicateLetter.runFromHotkey(getFinalContext(), validateOnly)) {
			return true;
		}

		// Customized build: "Down" jumps to the next page of suggestions. When there are none,
		// it is not handled, so the application receives it as a regular arrow key.
		if (Key.isArrowDown(keyCode) && onNextSuggestionPage(validateOnly)) {
			return true;
		}

		// Customized build: "Up" jumps to the previous page of suggestions. On the first page, it is
		// not handled here, so it continues as the "Filter" hotkey, which is the Pinyin (sound) selection.
		if (Key.isArrowUp(keyCode) && onPreviousSuggestionPage(validateOnly)) {
			return true;
		}

		// Customized build: "*" turns the Symbols mode on and off. This is the same in all languages.
		if (keyCode == KeyEvent.KEYCODE_STAR && onToggleSymbols(validateOnly)) {
			return true;
		}

		// Customized build: a short "#" switches the typing mode of the current language:
		// Predictive (T9 / Pinyin...) -> ABC (letters, capital letters and digits) -> Predictive.
		// Japanese has Hiragana and Katakana in between. A long "#" switches the language.
		if (keyCode == KeyEvent.KEYCODE_POUND && onNextTypingMode(validateOnly)) {
			return true;
		}

		if (Key.isArrowLeft(-keyCode) || Key.isArrowRight(-keyCode)) {
			if (editAdjacentLetter.runFromHotkey(getFinalContext(), validateOnly, Key.isArrowLeft(-keyCode))) {
				return true;
			}
		}

		return Key.isArrowLeft(keyCode) && onTrimTrailingSpace(validateOnly);
	}


	private boolean onNextSuggestionPage(boolean validateOnly) {
		if (shouldBeOff() || isFnPanelVisible() || suggestionOps.isEmpty()) {
			return false;
		}

		// Customized build: "Down" leaves the Pinyin sounds and goes to the words
		if (mInputMode.isSelectingSound()) {
			if (!validateOnly) {
				suggestionOps.cancelDelayedAccept();
				mInputMode.confirmSound();
				if (mainView != null) {
					mainView.renderDynamicKeys();
				}
			}
			return true;
		}

		if (!validateOnly) {
			CmdSuggestionNext.scrollSuggestionsPage(getFinalContext());
			if (mainView != null) {
				mainView.renderDynamicKeys();
			}
		}

		return true;
	}


	private boolean onPreviousSuggestionPage(boolean validateOnly) {
		if (shouldBeOff() || isFnPanelVisible() || suggestionOps.isEmpty()) {
			return false;
		}

		// already selecting the Pinyin sounds: "Up" has nowhere else to go
		if (mInputMode.isSelectingSound()) {
			return true;
		}

		if (suggestionOps.canScrollPagePrevious()) {
			if (!validateOnly) {
				CmdSuggestionNext.scrollSuggestionsPagePrevious(getFinalContext());
				if (mainView != null) {
					mainView.renderDynamicKeys();
				}
			}

			return true;
		}

		// the first page: select the Pinyin sound, when there is a row for it
		if (suggestionOps.hasSoundRow() && mInputMode.getSoundRow() != null) {
			if (!validateOnly) {
				suggestionOps.cancelDelayedAccept();
				mInputMode.enterSoundSelection();
				if (mainView != null) {
					mainView.renderDynamicKeys();
				}
			}

			return true;
		}

		return false;
	}


	/**
	 * Customized build: a short "#" cycles the typing modes of the current language:
	 * Predictive (T9, Pinyin...) -> ABC -> Predictive... In Japanese it is Kanji (T9) -> Hiragana -> Katakana -> ABC.
	 * ABC is the merged mode: letters with a short press, capital letters with "1" after the letter and
	 * digits with a long press, so there are no separate abc / ABC / 123 modes to go through any more.
	 * The modes that are not allowed in the current field are skipped.
	 *
	 * The key is always consumed when typing text, so it never types a "#". In the numeric modes it
	 * remains a regular "#" and we return false.
	 */
	private boolean onNextTypingMode(boolean validateOnly) {
		if (shouldBeOff() || isFnPanelVisible() || getFinalContext().isVoiceInputActive() || InputModeKind.isNumeric(mInputMode)) {
			return false;
		}

		if (validateOnly) {
			return true;
		}

		final int[] cycle = LanguageKind.isJapanese(mLanguage)
			? new int[] {InputMode.MODE_PREDICTIVE, InputMode.MODE_HIRAGANA, InputMode.MODE_KATAKANA, InputMode.MODE_ABC}
			: new int[] {InputMode.MODE_PREDICTIVE, InputMode.MODE_ABC};

		int current = -1;
		for (int i = 0; i < cycle.length; i++) {
			if (mInputMode.getId() == cycle[i]) {
				current = i;
				break;
			}
		}

		// Symbols or editing a word: "#" has nothing to switch.
		if (current < 0 || InputModeKind.isRecomposing(mInputMode)) {
			return true;
		}

		final ArrayList<Integer> allowedModes = getFinalContext().getAllowedInputModes();
		for (int step = 1; step < cycle.length; step++) {
			final int nextMode = cycle[(current + step) % cycle.length];
			if (allowedModes.contains(nextMode)) {
				setInputMode(nextMode);
				break;
			}
		}

		return true;
	}


	/**
	 * Customized build: "*" switches to the Symbols mode and back to the mode we were in before.
	 * In the numeric mode, it remains a regular "*" character.
	 */
	private boolean onToggleSymbols(boolean validateOnly) {
		if (shouldBeOff() || isFnPanelVisible() || getFinalContext().isVoiceInputActive() || InputModeKind.isNumeric(mInputMode)) {
			return false;
		}

		final ArrayList<Integer> allowedModes = getFinalContext().getAllowedInputModes();
		final boolean inSymbols = InputModeKind.isSymbols(mInputMode);
		if (!inSymbols && !allowedModes.contains(InputMode.MODE_SYMBOLS)) {
			return false;
		}

		if (validateOnly) {
			return true;
		}

		if (inSymbols) {
			int previousMode = modeBeforeSymbols;
			if (!allowedModes.contains(previousMode)) {
				previousMode = allowedModes.contains(InputMode.MODE_PREDICTIVE) ? InputMode.MODE_PREDICTIVE : InputMode.MODE_ABC;
			}
			setInputMode(previousMode);
		} else {
			modeBeforeSymbols = mInputMode.getId();
			setInputMode(InputMode.MODE_SYMBOLS);
		}

		return true;
	}


	private boolean onDynamicKey(int keyCode, boolean repeat, boolean validateOnly) {
		if (keyCode == settings.getKeyShift()) {
			return
				shift.runFromHotkey(getFinalContext(), validateOnly)
				// when "Shift" and "Korean Space" share the same key, allow typing a space, when there
				// are no special characters to shift
				|| (keyCode == settings.getKeySpaceKorean() && spaceKorean.runFromHotkey(getFinalContext(), validateOnly));
		}

		final Command cmd = CommandCollection.getByHotkey(settings, keyCode);
		if (cmd instanceof CmdFilterSuggestions) {
			// "Up" is also the "Page Up" key, so it must never be treated as a repeated "Filter" press
			return ((CmdFilterSuggestions) cmd).runFromHotkey(getFinalContext(), validateOnly, repeat && !Key.isArrowUp(keyCode));
		} else {
			return cmd.runFromHotkey(getFinalContext(), validateOnly);
		}
	}


	@Override
	protected void waitForSpaceTrimKey() {
		waitingForSpaceTrim = true;
	}


	@Override
	protected void stopWaitingForSpaceTrimKey() {
		waitingForSpaceTrim = false;
	}


	public boolean onTrimTrailingSpace(boolean validateOnly) {
		if (!waitingForSpaceTrim || !settings.getAutoTrimTrailingSpace() || !suggestionOps.isEmpty()) {
			return false;
		}

		String after = textField.getStringAfterCursor(1);
		if (!after.isEmpty() && after.charAt(0) != '\n') {
			stopWaitingForSpaceTrimKey();
			return false;
		}

		String before = textField.getStringBeforeCursor(2);
		if (before.equals(InputConnectionAsync.TIMEOUT_SENTINEL) || before.length() != 2 || Character.isWhitespace(before.charAt(0)) || before.charAt(1) != Characters.getSpace(mLanguage).charAt(0)) {
			stopWaitingForSpaceTrimKey();
			return false;
		}

		if (!validateOnly) {
			textField.deleteChars(mLanguage, 1);
			stopWaitingForSpaceTrimKey();
		}

		return true;
	}
}
