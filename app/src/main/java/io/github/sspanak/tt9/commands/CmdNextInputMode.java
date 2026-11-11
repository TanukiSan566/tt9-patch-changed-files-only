package io.github.sspanak.tt9.commands;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.ime.TraditionalT9;
import io.github.sspanak.tt9.ime.modes.InputMode;
import io.github.sspanak.tt9.ime.modes.InputModeKind;
import io.github.sspanak.tt9.languages.Language;
import io.github.sspanak.tt9.languages.LanguageCollection;
import io.github.sspanak.tt9.ui.StatusIcon;

public class CmdNextInputMode implements Command {
	public static final String ID = "key_next_input_mode";
	public String getId() { return ID; }
	public int getIcon() { return StatusIcon.getCachedResourceId(); }
	public int getName() { return R.string.function_next_mode; }

	public void invalidateIcon(@Nullable TraditionalT9 tt9) {
		new StatusIcon(
			tt9 != null ? tt9.getInputMode() : null,
			tt9 != null ? tt9.getLanguage() : null,
			tt9 != null ? tt9.getDisplayTextCase() : 0
		);
	}

	@Override
	public boolean isAvailable(@Nullable TraditionalT9 tt9) {
		return
			tt9 != null
			&& tt9.getAllowedInputModes().size() > 1
			&& !tt9.shouldBeOff()
			&& !tt9.isVoiceInputActive()
			&& !InputModeKind.isPassthrough(tt9.getInputMode());
	}


	private static final int SLOT_123 = -1;
	private static final int SLOT_SYMBOLS = -2;


	/**
	 * Customized build: "#" cycles through Numbers -> English -> Chinese -> (other enabled languages) -> Symbols.
	 * Languages are the ones enabled in the settings, so adding Japanese there also adds it to the "#" cycle.
	 */
	public boolean run(@Nullable TraditionalT9 tt9) {
		if (tt9 == null || !isAvailable(tt9)) {
			return false;
		}

		InputMode inputMode = tt9.getInputMode();

		tt9.getSuggestionOps().cancelDelayedAccept();
		inputMode.onAcceptSuggestion(tt9.getSuggestionOps().acceptIncomplete());

		final ArrayList<Integer> slots = getSlots(tt9);
		if (!slots.isEmpty()) {
			final int currentSlot = getCurrentSlot(tt9);
			final int nextSlot = slots.get((slots.indexOf(currentSlot) + 1) % slots.size());
			applySlot(tt9, nextSlot);
		}

		tt9.forceShowWindow();
		return true;
	}


	@NonNull
	private ArrayList<Integer> getSlots(@NonNull TraditionalT9 tt9) {
		final ArrayList<Integer> allowedModes = tt9.getAllowedInputModes();
		final ArrayList<Integer> slots = new ArrayList<>();

		if (allowedModes.contains(InputMode.MODE_123)) {
			slots.add(SLOT_123);
		}

		if (allowedModes.contains(InputMode.MODE_PREDICTIVE) || allowedModes.contains(InputMode.MODE_ABC)) {
			final ArrayList<Integer> languageIds = tt9.getSettings().getEnabledLanguageIds();
			Collections.sort(languageIds, (a, b) -> {
				final int rankA = getLanguageRank(a);
				final int rankB = getLanguageRank(b);
				return rankA != rankB ? Integer.compare(rankA, rankB) : Integer.compare(a, b);
			});
			slots.addAll(languageIds);
		}

		if (allowedModes.contains(InputMode.MODE_SYMBOLS)) {
			slots.add(SLOT_SYMBOLS);
		}

		return slots;
	}


	// English first, Chinese second, then everything else.
	private int getLanguageRank(int languageId) {
		final Language language = LanguageCollection.getLanguage(languageId);
		if (language == null) {
			return 9;
		}

		final String code = language.getLocale().getLanguage();
		if ("en".equals(code)) return 0;
		if ("zh".equals(code)) return 1;
		return 2;
	}


	private int getCurrentSlot(@NonNull TraditionalT9 tt9) {
		final InputMode mode = tt9.getInputMode();

		if (InputModeKind.is123(mode)) {
			return SLOT_123;
		}

		if (InputModeKind.isSymbols(mode)) {
			return SLOT_SYMBOLS;
		}

		return tt9.getLanguage() != null ? tt9.getLanguage().getId() : SLOT_123;
	}


	private void applySlot(@NonNull TraditionalT9 tt9, int slot) {
		if (slot == SLOT_123) {
			tt9.setInputMode(InputMode.MODE_123);
			return;
		}

		if (slot == SLOT_SYMBOLS) {
			tt9.setInputMode(InputMode.MODE_SYMBOLS);
			return;
		}

		if (tt9.getLanguage() == null || tt9.getLanguage().getId() != slot) {
			tt9.setLang(slot);
		}

		final ArrayList<Integer> allowedModes = tt9.getAllowedInputModes();
		final boolean abcFirst = !tt9.getSettings().getPredictiveMode() && allowedModes.contains(InputMode.MODE_ABC);
		if (!abcFirst && allowedModes.contains(InputMode.MODE_PREDICTIVE)) {
			tt9.setInputMode(InputMode.MODE_PREDICTIVE);
		} else if (allowedModes.contains(InputMode.MODE_ABC)) {
			tt9.setInputMode(InputMode.MODE_ABC);
		}
	}
}
