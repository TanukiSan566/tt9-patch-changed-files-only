package io.github.sspanak.tt9.commands;

import androidx.annotation.Nullable;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.ime.TraditionalT9;

public class CmdHideKeyboard implements Command {
	public static final String ID = "key_hide_keyboard";
	public String getId() { return ID; }
	public int getIcon() { return R.drawable.ic_fn_hide_keyboard; }
	public int getName() { return R.string.function_hide_keyboard; }

	// Customized build: key "0" of the command popup (hold "*")
	@Override public int getHardKey() { return 0; }
	@Override public int getPaletteKey() { return R.id.soft_key_0; }


	@Override
	public boolean isAvailable(@Nullable TraditionalT9 tt9) {
		return tt9 != null && !tt9.shouldBeOff();
	}


	@Override
	public boolean run(@Nullable TraditionalT9 tt9) {
		if (tt9 == null) {
			return false;
		}

		tt9.getSuggestionOps().cancelDelayedAccept();
		tt9.getSuggestionOps().acceptIncomplete();
		tt9.getInputMode().reset();

		// the popup must not be still open the next time the keyboard shows up
		new CmdCommandPalette().hideCommandPalette(tt9);

		// Customized build: from now on, "Back" goes to the application (in Bilibili and the "Back key apps" list)
		tt9.setKeyboardCollapsedByUser(true);
		tt9.requestHideSelf(0);

		return true;
	}
}
