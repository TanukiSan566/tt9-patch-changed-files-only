package io.github.sspanak.tt9.commands;

import androidx.annotation.Nullable;

import java.util.ArrayList;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.ime.TraditionalT9;
import io.github.sspanak.tt9.ime.modes.InputMode;
import io.github.sspanak.tt9.ime.modes.InputModeKind;
import io.github.sspanak.tt9.ui.StatusIcon;

/**
 * Customized build: key "7" of the command popup (hold "*"). It cycles the typing modes of the current
 * language: Predictive (T9) -> abc -> ABC -> 123, so letters and digits can be typed directly from
 * the keypad, for example in account names and passwords. It closes the popup and then does exactly
 * what the old "Toggle predictive mode" hotkey did.
 */
public class CmdTypingModeCycle implements Command {
	public static final String ID = "key_typing_mode_cycle";
	private final CmdTogglePredictiveMode toggleMode = new CmdTogglePredictiveMode();

	@Override public String getId() { return ID; }
	@Override public int getIcon() { return StatusIcon.getCachedResourceId(); }
	@Override public int getName() { return R.string.function_toggle_predictive_mode; }
	@Override public int getHardKey() { return 7; }
	@Override public int getPaletteKey() { return R.id.soft_key_7; }


	@Override
	public boolean isAvailable(@Nullable TraditionalT9 tt9) {
		if (tt9 == null || tt9.shouldBeOff() || tt9.isVoiceInputActive() || tt9.getLanguage() == null) {
			return false;
		}

		final InputMode mode = tt9.getInputMode();
		final ArrayList<Integer> allowedModes = tt9.getAllowedInputModes();
		return
			allowedModes.size() > 1
			&& (InputModeKind.isPredictive(mode) || InputModeKind.isABC(mode) || InputModeKind.is123(mode));
	}


	@Override
	public boolean run(@Nullable TraditionalT9 tt9) {
		if (tt9 == null || !isAvailable(tt9)) {
			return false;
		}

		if (tt9.getMainView() != null && tt9.getMainView().isCommandPaletteShown()) {
			new CmdCommandPalette().hideCommandPalette(tt9);
		}

		return toggleMode.run(tt9);
	}
}
