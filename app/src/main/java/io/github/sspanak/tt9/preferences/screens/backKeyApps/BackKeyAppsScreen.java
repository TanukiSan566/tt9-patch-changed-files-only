package io.github.sspanak.tt9.preferences.screens.backKeyApps;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;

import java.util.ArrayList;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.preferences.PreferencesActivity;
import io.github.sspanak.tt9.preferences.screens.BaseScreenFragment;

/**
 * Customized build: the apps in which "Back" is always "Backspace". In an app like this, "Back" never
 * leaves the screen, even when the keyboard thinks the text field is empty. It is for apps whose text
 * fields do not tell us what they contain (e.g. the search in Bilibili).
 */
public class BackKeyAppsScreen extends BaseScreenFragment {
	public final static String NAME = "BackKeyApps";

	private final static String ADD_BUTTON = "back_key_apps_add";
	private final static String LIST = "back_key_apps_list";

	public BackKeyAppsScreen() { super(); }
	public BackKeyAppsScreen(@Nullable PreferencesActivity activity) { super(activity); }

	@Override public String getName() { return NAME; }
	@Override protected int getTitle() { return R.string.pref_back_key_apps; }
	@Override protected int getXml() { return R.xml.prefs_screen_back_key_apps; }


	@Override
	protected void onCreate() {
		final Preference add = findPreference(ADD_BUTTON);
		if (add != null && activity != null) {
			add.setOnPreferenceClickListener(p -> {
				activity.startActivity(new Intent(activity, BackKeyAppPickerActivity.class));
				return true;
			});
		}

		refreshList();
	}


	@Override
	public void onResume() {
		super.onResume();
		refreshList(); // we may be back from the app picker
	}


	private void refreshList() {
		final PreferenceCategory list = findPreference(LIST);
		if (list == null || activity == null) {
			return;
		}

		list.removeAll();

		final PackageManager pm = activity.getPackageManager();
		final ArrayList<String> apps = activity.getSettings().getBackKeyApps();
		list.setVisible(!apps.isEmpty());

		for (String packageName : apps) {
			final Preference item = new Preference(activity);
			item.setKey("back_key_app_" + packageName);
			item.setPersistent(false);
			item.setTitle(getLabel(pm, packageName));
			item.setSummary(packageName);
			item.setIcon(getIcon(pm, packageName));
			item.setOnPreferenceClickListener(p -> {
				confirmRemove(packageName, p.getTitle());
				return true;
			});
			list.addPreference(item);
		}

		resetFontSize(true);
	}


	private void confirmRemove(@NonNull String packageName, @Nullable CharSequence name) {
		if (activity == null) {
			return;
		}

		new AlertDialog.Builder(activity)
			.setTitle(name)
			.setMessage(R.string.pref_back_key_apps_remove)
			.setPositiveButton(R.string.pref_back_key_apps_remove_yes, (d, w) -> {
				if (activity != null) {
					activity.getSettings().removeBackKeyApp(packageName);
				}
				refreshList();
			})
			.setNegativeButton(android.R.string.cancel, null)
			.show();
	}


	@NonNull
	static CharSequence getLabel(@NonNull PackageManager pm, @NonNull String packageName) {
		try {
			final ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
			return pm.getApplicationLabel(info);
		} catch (Exception e) {
			return packageName;
		}
	}


	@NonNull
	static Drawable getIcon(@NonNull PackageManager pm, @NonNull String packageName) {
		try {
			return pm.getApplicationIcon(packageName);
		} catch (Exception e) {
			return pm.getDefaultActivityIcon();
		}
	}
}
