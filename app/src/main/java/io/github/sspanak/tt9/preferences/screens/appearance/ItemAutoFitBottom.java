package io.github.sspanak.tt9.preferences.screens.appearance;

import androidx.preference.SwitchPreferenceCompat;

import io.github.sspanak.tt9.preferences.items.ItemSwitch;
import io.github.sspanak.tt9.preferences.settings.SettingsStore;

public class ItemAutoFitBottom extends ItemSwitch {
	public static final String NAME = "pref_auto_fit_bottom";
	private final SettingsStore settings;

	public ItemAutoFitBottom(SwitchPreferenceCompat item, SettingsStore settings) {
		super(item);
		this.settings = settings;
	}

	@Override
	protected boolean getDefaultValue() {
		return settings.getAutoFitBottom();
	}
}
