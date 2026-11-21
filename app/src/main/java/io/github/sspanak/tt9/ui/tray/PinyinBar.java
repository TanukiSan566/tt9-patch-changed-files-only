package io.github.sspanak.tt9.ui.tray;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.graphics.Paint;
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
	@Nullable private final RecyclerView wordList;
	private boolean arrowsVisible = false;


	public PinyinBar(@NonNull SettingsStore settings, @NonNull View root) {
		this.settings = settings;
		view = root.findViewById(R.id.pinyin_bar);
		arrowLeft = root.findViewById(R.id.suggestions_arrow_left);
		arrowRight = root.findViewById(R.id.suggestions_arrow_right);
		wordList = root.findViewById(R.id.suggestions_bar);

		if (view != null) {
			view.setLayoutManager(new LinearLayoutManager(view.getContext(), RecyclerView.HORIZONTAL, false));
			view.setAdapter(adapter);
			view.setItemAnimator(null);
			view.setVisibility(View.GONE); // the container is not resized here, because it has not been sized yet
		}
	}


	/**
	 * The height of the Pinyin row. The keyboard container has a fixed height (see
	 * BaseMainLayout.setKeyboardHeight()), that does not account for this row. Without making room
	 * for it, the row pushes the soft keys below the container edge and they get cut off.
	 */
	public static int getRowHeight(@NonNull View bar) {
		final float density = bar.getResources().getDisplayMetrics().density;
		final Paint paint = new Paint();
		paint.setTextSize(bar.getResources().getDimension(R.dimen.status_bar_text_size));
		final Paint.FontMetrics metrics = paint.getFontMetrics();
		// the text line + the 3dp top and bottom padding of pinyin_list_item.xml + a little safety margin
		return Math.round(metrics.bottom - metrics.top + 6 * density + 2 * density);
	}


	/**
	 * Shows or hides the row and grows or shrinks the fixed-height keyboard container accordingly.
	 */
	private void setRowVisible(boolean visible) {
		if (view == null) {
			return;
		}

		final boolean wasVisible = view.getVisibility() == View.VISIBLE;
		view.setVisibility(visible ? View.VISIBLE : View.GONE);

		if (wasVisible == visible || !(view.getParent() instanceof View)) {
			return;
		}

		final View container = (View) view.getParent();
		final ViewGroup.LayoutParams params = container.getLayoutParams();
		if (params != null && params.height > 0) {
			final int rowHeight = getRowHeight(view);
			params.height = Math.max(0, params.height + (visible ? rowHeight : -rowHeight));
			container.setLayoutParams(params);
		}
	}


	public boolean isAvailable() {
		return view != null;
	}


	/**
	 * The arrows take their own space, so the words are always fully visible between them.
	 */
	private void showArrows(boolean visible) {
		arrowsVisible = visible;

		if (wordList != null) {
			final int side = visible ? Math.round(28 * wordList.getResources().getDisplayMetrics().density) : 0;
			wordList.setClipToPadding(true);
			wordList.setPadding(side, wordList.getPaddingTop(), side, wordList.getPaddingBottom());
		}

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
			setRowVisible(false);
			showArrows(false);
			return;
		}

		items.addAll(rows);
		selected = newSelected;
		adapter.notifyDataSetChanged();
		setRowVisible(true);
		showArrows(true);

		if (selected >= 0 && selected < items.size()) {
			view.scrollToPosition(selected);
		}
	}


	/**
	 * Lights up the arrow of the direction we have just scrolled to: white background, dark icon.
	 */
	public void flashArrow(boolean left) {
		final TextView arrow = left ? arrowLeft : arrowRight;
		if (arrow == null || !arrowsVisible) {
			return;
		}

		arrow.setBackgroundColor(settings.getKeyboardTextColor());
		arrow.setTextColor(settings.getKeyboardBackground());
		arrow.postDelayed(() -> {
			arrow.setBackgroundColor(settings.getKeyboardBackground());
			arrow.setTextColor(settings.getKeyboardTextColor());
		}, 180);
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
