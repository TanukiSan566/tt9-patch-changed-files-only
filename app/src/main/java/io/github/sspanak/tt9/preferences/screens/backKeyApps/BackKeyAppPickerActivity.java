package io.github.sspanak.tt9.preferences.screens.backKeyApps;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.preferences.settings.SettingsStore;
import io.github.sspanak.tt9.ui.EdgeToEdgeActivity;
import io.github.sspanak.tt9.ui.UI;

/**
 * Customized build: lets the user pick an installed app for the "Back key apps" list.
 * Shows the icon and the name of every app. The search works with the name, the pinyin of the
 * name (full and the initials) and the package name.
 */
public class BackKeyAppPickerActivity extends EdgeToEdgeActivity {
	private static class AppItem {
		final String packageName;
		final CharSequence label;
		final Drawable icon;
		final String searchKey;

		AppItem(String packageName, CharSequence label, Drawable icon, String searchKey) {
			this.packageName = packageName;
			this.label = label;
			this.icon = icon;
			this.searchKey = searchKey;
		}
	}


	private final ArrayList<AppItem> allApps = new ArrayList<>();
	private final ArrayList<AppItem> shownApps = new ArrayList<>();
	private LinearLayout root;
	private AppsAdapter adapter;
	private TextView emptyText;
	private SettingsStore settings;
	private volatile boolean destroyed = false;


	@Override
	public void onAttachedToWindow() {
		super.onAttachedToWindow();
		preventEdgeToEdge(root);
	}


	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setTitle(R.string.pref_back_key_apps_add);
		settings = new SettingsStore(getApplicationContext());

		// the keyboard (TT9) takes half of a small screen: shrink the list instead of hiding it behind the keyboard
		getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

		root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);

		final EditText search = new EditText(this);
		search.setHint(R.string.pref_back_key_apps_search);
		search.setSingleLine(true);
		root.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		emptyText = new TextView(this);
		emptyText.setText(R.string.pref_back_key_apps_loading);
		emptyText.setGravity(Gravity.CENTER);
		emptyText.setPadding(dp(16), dp(24), dp(16), dp(24));
		root.addView(emptyText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		final ListView list = new ListView(this);
		adapter = new AppsAdapter();
		list.setAdapter(adapter);
		list.setOnItemClickListener((parent, view, position, id) -> {
			if (position >= 0 && position < shownApps.size()) {
				final AppItem app = shownApps.get(position);
				settings.addBackKeyApp(app.packageName);
				UI.toast(this, getString(R.string.pref_back_key_apps_added, app.label));
				finish();
			}
		});
		root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

		search.addTextChangedListener(new TextWatcher() {
			@Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
			@Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
			@Override public void afterTextChanged(Editable s) { applyFilter(s.toString()); }
		});

		setContentView(root);
		new Thread(this::loadApps).start();
	}


	@Override
	protected void onDestroy() {
		destroyed = true;
		super.onDestroy();
	}


	private int dp(int value) {
		return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
	}


	private void loadApps() {
		final PackageManager pm = getPackageManager();
		final Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
		final List<ResolveInfo> resolved = pm.queryIntentActivities(launcher, 0);

		final HashSet<String> added = new HashSet<>(settings.getBackKeyApps());
		final HashSet<String> seen = new HashSet<>();
		final ArrayList<AppItem> loaded = new ArrayList<>();
		final Transliterator pinyin = new Transliterator();

		for (ResolveInfo info : resolved) {
			if (info.activityInfo == null) {
				continue;
			}

			final String packageName = info.activityInfo.packageName;
			if (packageName == null || added.contains(packageName) || !seen.add(packageName)) {
				continue;
			}

			final CharSequence label = info.loadLabel(pm);
			final Drawable icon = info.loadIcon(pm);
			loaded.add(new AppItem(packageName, label, icon, pinyin.buildSearchKey(label.toString(), packageName)));
		}

		final Collator collator = Collator.getInstance(Locale.getDefault());
		Collections.sort(loaded, (a, b) -> collator.compare(a.label.toString(), b.label.toString()));

		new Handler(Looper.getMainLooper()).post(() -> {
			if (destroyed) {
				return;
			}
			allApps.clear();
			allApps.addAll(loaded);
			emptyText.setText(R.string.pref_back_key_apps_none);
			applyFilter("");
		});
	}


	private void applyFilter(@NonNull String query) {
		final String q = query.trim().toLowerCase(Locale.ROOT);
		final String compact = q.replace(" ", "");

		shownApps.clear();
		for (AppItem app : allApps) {
			if (q.isEmpty() || app.searchKey.contains(q) || app.searchKey.contains(compact)) {
				shownApps.add(app);
			}
		}

		adapter.notifyDataSetChanged();
		emptyText.setVisibility(shownApps.isEmpty() ? View.VISIBLE : View.GONE);
	}


	/**
	 * Builds the text we search in: name, pinyin, pinyin without spaces, the initials of the pinyin and the package name.
	 * The pinyin comes from the system (ICU). If it is not available, only the name and the package are searched.
	 */
	private static class Transliterator {
		private android.icu.text.Transliterator han;

		Transliterator() {
			try {
				han = android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII");
			} catch (Throwable ignored) {
				han = null;
			}
		}

		String buildSearchKey(@NonNull String label, @NonNull String packageName) {
			final StringBuilder key = new StringBuilder(label.toLowerCase(Locale.ROOT));

			if (han != null && containsHan(label)) {
				try {
					final String spaced = han.transliterate(label).toLowerCase(Locale.ROOT).trim();
					final StringBuilder initials = new StringBuilder();
					for (String syllable : spaced.split("\\s+")) {
						if (!syllable.isEmpty()) {
							initials.append(syllable.charAt(0));
						}
					}
					key.append(' ').append(spaced).append(' ').append(spaced.replace(" ", "")).append(' ').append(initials);
				} catch (Throwable ignored) {}
			}

			return key.append(' ').append(packageName.toLowerCase(Locale.ROOT)).toString();
		}

		private boolean containsHan(@NonNull String text) {
			for (int i = 0; i < text.length(); i++) {
				if (Character.UnicodeScript.of(text.charAt(i)) == Character.UnicodeScript.HAN) {
					return true;
				}
			}
			return false;
		}
	}


	private class AppsAdapter extends BaseAdapter {
		@Override public int getCount() { return shownApps.size(); }
		@Override public Object getItem(int position) { return shownApps.get(position); }
		@Override public long getItemId(int position) { return position; }

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			final LinearLayout row;
			final ImageView icon;
			final TextView name;
			final TextView pkg;

			if (convertView instanceof LinearLayout) {
				row = (LinearLayout) convertView;
				icon = (ImageView) row.getChildAt(0);
				final LinearLayout texts = (LinearLayout) row.getChildAt(1);
				name = (TextView) texts.getChildAt(0);
				pkg = (TextView) texts.getChildAt(1);
			} else {
				row = new LinearLayout(BackKeyAppPickerActivity.this);
				row.setOrientation(LinearLayout.HORIZONTAL);
				row.setGravity(Gravity.CENTER_VERTICAL);
				row.setPadding(dp(12), dp(6), dp(12), dp(6));

				icon = new ImageView(BackKeyAppPickerActivity.this);
				row.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));

				final LinearLayout texts = new LinearLayout(BackKeyAppPickerActivity.this);
				texts.setOrientation(LinearLayout.VERTICAL);
				final LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
				textsParams.leftMargin = dp(12);
				row.addView(texts, textsParams);

				name = new TextView(BackKeyAppPickerActivity.this);
				name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
				name.setSingleLine(true);
				texts.addView(name);

				pkg = new TextView(BackKeyAppPickerActivity.this);
				pkg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
				pkg.setAlpha(0.6f);
				pkg.setSingleLine(true);
				texts.addView(pkg);
			}

			final AppItem app = shownApps.get(position);
			icon.setImageDrawable(app.icon);
			name.setText(app.label);
			pkg.setText(app.packageName);
			return row;
		}
	}
}
