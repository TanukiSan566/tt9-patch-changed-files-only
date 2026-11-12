package io.github.sspanak.tt9.ui.tray;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.preferences.settings.SettingsStore;

/**
 * Customized build: the row with the Pinyin sounds, displayed above the words, for example:
 * "bian  biao  chao  chan". The selected sound is highlighted. It exists only in the small layout.
 */
public class PinyinBar {
	@Nullable private final RecyclerView view;
	@NonNull private final SettingsStore settings;
	@NonNull private final ArrayList<String> items = new ArrayList<>();
	private int selected = -1;
	private final SoundsAdapter adapter = new SoundsAdapter();
	@Nullable private final TextView arrowLeft;
	@Nullable private final TextView arrowRight;


	public PinyinBar(@NonNull SettingsStore settings, @NonNull View root) {
		this.settings = settings;
		view = root.findViewById(R.id.pinyin_bar);
		arrowLeft = root.findViewById(R.id.suggestions_arrow_left);
		arrowRight = root.findViewById(R.id.suggestions_arrow_right);

		if (view != null) {
			view.setLayoutManager(new LinearLayoutManager(view.getContext(), RecyclerView.HORIZONTAL, false));
			view.setAdapter(adapter);
			view.setItemAnimator(null);
			view.setVisibility(View.GONE);
		}
	}


	public boolean isAvailable() {
		return view != null;
	}


	private void showArrows(boolean visible) {
		for (TextView arrow : new TextView[] { arrowLeft, arrowRight }) {
			if (arrow != null) {
				// always black background and white icon, the same as the unselected words
				arrow.setBackgroundColor(settings.getKeyboardBackground());
				arrow.setTextColor(settings.getKeyboardTextColor());
				arrow.setVisibility(visible ? View.VISIBLE : View.GONE);
			}
		}
	}


	@SuppressLint("NotifyDataSetChanged")
	public void set(@Nullable List<String> rows, int newSelected) {
		if (view == null) {
			return;
		}

		items.clear();

		if (rows == null || rows.isEmpty()) {
			selected = -1;
			adapter.notifyDataSetChanged();
			view.setVisibility(View.GONE);
			showArrows(false);
			return;
		}

		items.addAll(rows);
		selected = newSelected;
		adapter.notifyDataSetChanged();
		view.setVisibility(View.VISIBLE);
		showArrows(true);

		if (selected >= 0 && selected < items.size()) {
			view.scrollToPosition(selected);
		}
	}


	private static class SoundHolder extends RecyclerView.ViewHolder {
		final TextView text;

		SoundHolder(@NonNull View itemView) {
			super(itemView);
			text = (TextView) itemView;
		}
	}


	private class SoundsAdapter extends RecyclerView.Adapter<SoundHolder> {
		@NonNull
		@Override
		public SoundHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			return new SoundHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.pinyin_list_item, parent, false));
		}

		@Override
		public void onBindViewHolder(@NonNull SoundHolder holder, int position) {
			final boolean isSelected = position == selected;
			holder.text.setText(items.get(position));
			holder.text.setTextColor(isSelected ? settings.getSuggestionSelectedColor() : settings.getKeyboardTextColor());
			holder.text.setBackgroundColor(isSelected ? settings.getSuggestionSelectedBackground() : Color.TRANSPARENT);
		}

		@Override
		public int getItemCount() {
			return items.size();
		}
	}
}
