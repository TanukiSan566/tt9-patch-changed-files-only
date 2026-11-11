package io.github.sspanak.tt9.commands;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.ime.TraditionalT9;
import io.github.sspanak.tt9.ime.modes.InputMode;
import io.github.sspanak.tt9.ime.modes.InputModeKind;
import io.github.sspanak.tt9.ui.StatusIcon;

/**
 * Customized build: cycles the typing modes: Predictive (T9) -> abc -> ABC -> 123 -> Predictive...
 * Modes that are not allowed in the current field (or are disabled in the settings) are skipped.
 * The ID is kept as-is, so the existing hotkey and swipe settings continue to work.
 */
public class CmdTogglePredictiveMode implements Command {
	public static final String ID = "key_toggle_predictive_mode";
	@Override public String getId() { return ID; }
	@Override public int getIcon() { return StatusIcon.getCachedResourceId(); }
	@Override public int getName() { return R.string.function_toggle_predictive_mode; }

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
			&& isAvailableStd(tt9)
			&& !tt9.isVoiceInputActive()
			&& !tt9.isFnPanelVisible()
			&& tt9.getLanguage() != null
			&& (
				InputModeKind.isPredictive(tt9.getInputMode())
				|| InputModeKind.isABC(tt9.getInputMode())
				|| InputModeKind.is123(tt9.getInputMode())
			)
			&& tt9.getAllowedInputModes().size() > 1;
	}

	@Override
	public boolean run(@Nullable TraditionalT9 tt9) {
		if (tt9 == null || !isAvailable(tt9)) {
			return false;
		}

		final ArrayList<Integer> allowed = tt9.getAllowedInputModes();
		final boolean hasPredictive = allowed.contains(InputMode.MODE_PREDICTIVE);
		final boolean hasAbc = allowed.contains(InputMode.MODE_ABC);
		final boolean has123 = allowed.contains(InputMode.MODE_123);
		final InputMode mode = tt9.getInputMode();

		if (InputModeKind.isPredictive(mode)) {
			// T9 -> abc
			if (hasAbc) {
				switchToAbc(tt9);
			} else if (has123) {
				tt9.setInputMode(InputMode.MODE_123);
			}
		} else if (InputModeKind.isABC(mode)) {
			// abc -> ABC -> 123
			if (!switchToUpperCase(tt9)) {
				if (has123) {
					tt9.setInputMode(InputMode.MODE_123);
				} else if (hasPredictive) {
					tt9.setInputMode(InputMode.MODE_PREDICTIVE);
				}
			}
		} else if (InputModeKind.is123(mode)) {
			// 123 -> T9
			if (hasPredictive) {
				tt9.setInputMode(InputMode.MODE_PREDICTIVE);
			} else if (hasAbc) {
				switchToAbc(tt9);
			}
		}

		return true;
	}


	private void switchToAbc(@NonNull TraditionalT9 tt9) {
		tt9.setInputMode(InputMode.MODE_ABC);
		final InputMode abc = tt9.getInputMode();
		if (InputModeKind.isABC(abc) && abc.getTextCase() != InputMode.CASE_LOWER && abc.setTextCase(InputMode.CASE_LOWER)) {
			tt9.getSettings().saveTextCase(InputMode.CASE_LOWER);
			refresh(tt9);
		}
	}


	/**
	 * @return true when the mode was changed from abc to ABC. false, when it is already ABC (or ABC is not allowed).
	 */
	private boolean switchToUpperCase(@NonNull TraditionalT9 tt9) {
		final InputMode abc = tt9.getInputMode();
		if (abc.getTextCase() == InputMode.CASE_UPPER) {
			return false;
		}

		tt9.getSuggestionOps().cancelDelayedAccept();
		abc.onAcceptSuggestion(tt9.getSuggestionOps().acceptIncomplete());

		if (!abc.setTextCase(InputMode.CASE_UPPER)) {
			return false;
		}

		abc.skipNextTextCaseDetection();
		tt9.getSettings().saveTextCase(InputMode.CASE_UPPER);
		refresh(tt9);
		return true;
	}


	private void refresh(@NonNull TraditionalT9 tt9) {
		final InputMode mode = tt9.getInputMode();

		tt9.getDisplayTextCase(tt9.getLanguage(), mode.getTextCase());
		tt9.setStatusIcon(mode, tt9.getLanguage());

		if (tt9.getStatusBar() != null) {
			tt9.getStatusBar().setText(mode);
			tt9.getStatusBar().setAccessibilityTextCase(mode);
		}

		if (tt9.getMainView() != null) {
			tt9.getMainView().render();
		}
	}
}
