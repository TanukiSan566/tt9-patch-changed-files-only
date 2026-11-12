package io.github.sspanak.tt9.ime.modes;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;

import io.github.sspanak.tt9.hacks.InputType;
import io.github.sspanak.tt9.languages.Language;
import io.github.sspanak.tt9.preferences.settings.SettingsStore;
import io.github.sspanak.tt9.util.chars.Characters;

/**
 * Symbols mode (customized build). Press a key to get a group of symbols, press it again to
 * cycle through them. English, Chinese and "numeric" symbols are available at the same time.
 * Key 1 also has the emoticons (kaomoji) and the emoji, after the Chinese punctuation.
 * Long-press a key to type the digit.
 */
class ModeSymbols extends InputMode {
	private static final String[][] KEY_SYMBOLS = {
		/* 0 */ {" ", "\n", "　", "_"},
		/* 1 */ {"，", "。", "！", "？", "、", "；", "：", "…", "～", "—"},
		/* 2 */ {"(", ")", "[", "]", "{", "}", "<", ">", "（", "）", "《", "》", "「", "」", "『", "』", "【", "】"},
		/* 3 */ {"!", "@", "#", "$", "%", "^", "&", "*", "~", "`"},
		/* 4 */ {".", ",", "?", "!", "'", "\"", ":", ";", "-", "_"},
		/* 5 */ {"+", "-", "*", "/", "=", "<", ">", "±", "×", "÷", "≠", "≈"},
		/* 6 */ {"¥", "$", "€", "£", "¢", "₩", "₽", "₿", "%", "‰"},
		/* 7 */ {"\\", "|", "/", "&", "^", "~", "§", "¶", "†", "‡"},
		/* 8 */ {"“", "”", "‘", "’", "\"", "'", "«", "»"},
		/* 9 */ {"©", "®", "™", "°", "²", "³", "№", "·", "•"}
	};

	private static final String[] KAOMOJI = {
		"(^_^)", "(^o^)", "(≧▽≦)", "(*^▽^*)", "(｡◕‿◕｡)", "(´▽`)", "(｡･ω･｡)", "(^_−)☆",
		"(T_T)", "(；へ；)", "(ಥ_ಥ)", "(╥﹏╥)", "(>_<)", "(¬_¬)", "(－_－)zzz", "(╯°□°）╯︵ ┻━┻",
		"ヽ(✿ﾟ▽ﾟ)ノ", "\\(^o^)/", "(ง •̀_•́)ง", "(^人^)", "orz", "¯\\_(ツ)_/¯", "( ﾟдﾟ)", "(◔◡◔)"
	};

	private boolean shouldSelectNextSymbol = false;

	@Override public int getId() { return MODE_SYMBOLS; }
	@Override @NonNull public String toString() { return "!@#"; }
	@Override @NonNull public String toAccessibilityString(@NonNull Context ctx) { return "Symbols"; }
	@Override protected boolean shouldDisplayEmojis() { return false; }


	protected ModeSymbols(@NonNull SettingsStore settings, @NonNull Language lang, @Nullable InputType inputType) {
		super(settings, inputType);
		setLanguage(lang);
		defaultTextCase();
	}


	@Override
	public boolean onBackspace() {
		if (!suggestions.isEmpty()) {
			reset();
		}

		return false;
	}


	@Override
	public boolean onNumber(int number, boolean hold, int repeat, @NonNull String[] surroundingChars) {
		return onNumber(number, hold, repeat);
	}


	private boolean onNumber(int number, boolean hold, int repeat) {
		if (number < 0 || number > 9) {
			return false;
		}

		if (hold) {
			reset();
			autoAcceptTimeout = 0;
			digitSequence = String.valueOf(number);
			shouldSelectNextSymbol = false;

			ArrayList<String> newSuggestions = new ArrayList<>(1);
			newSuggestions.add(String.valueOf(number));
			suggestions = newSuggestions;
		} else if (repeat > 0 && !suggestions.isEmpty()) {
			autoAcceptTimeout = settings.getAutoAcceptTimeoutAbc();
			shouldSelectNextSymbol = true;
		} else {
			reset();
			autoAcceptTimeout = settings.getAutoAcceptTimeoutAbc();
			digitSequence = String.valueOf(number);
			shouldSelectNextSymbol = false;

			ArrayList<String> newSuggestions = new ArrayList<>(Arrays.asList(KEY_SYMBOLS[number]));
			if (number == 1 && !isEmailMode) {
				newSuggestions.addAll(Arrays.asList(KAOMOJI));
				if (settings.areEmojisEnabled()) {
					for (int group = 0; group < Characters.getMaxEmojiLevel(); group++) {
						newSuggestions.addAll(Characters.getEmoji(group));
					}
				}
			}
			newSuggestions.add(String.valueOf(number));
			suggestions = newSuggestions;
		}

		return true;
	}


	private void refreshSuggestions() {
		if (digitSequence.isEmpty()) {
			suggestions = new ArrayList<>();
		} else {
			onNumber(digitSequence.charAt(0) - '0', false, 0);
		}
	}


	@Override
	public void setSequence(@NonNull String sequence) {
		super.setSequence(sequence);
		refreshSuggestions();
		shouldSelectNextSymbol = true;
	}


	@Override
	public void onAcceptSuggestion(@NonNull String word) {
		reset();
	}


	@Override
	public boolean shouldAcceptPreviousSuggestion(String word) {
		return !shouldSelectNextSymbol && word != null && !word.isEmpty() && !Characters.PLACEHOLDER.equals(word);
	}


	@Override
	public boolean shouldSelectNextSuggestion() {
		return shouldSelectNextSymbol;
	}


	@Override
	public void reset() {
		super.reset();
		digitSequence = "";
		shouldSelectNextSymbol = false;
	}
}
