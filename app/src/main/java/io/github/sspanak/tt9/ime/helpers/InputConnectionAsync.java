package io.github.sspanak.tt9.ime.helpers;

import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import androidx.annotation.Nullable;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.sspanak.tt9.preferences.settings.SettingsStore;
import io.github.sspanak.tt9.util.Logger;

public class InputConnectionAsync {
	private static final String LOG_TAG = InputConnectionAsync.class.getSimpleName();
	public static final String TIMEOUT_SENTINEL = "\u001F";

	@Nullable private static ExecutorService executor;

	// Customized build: some applications (e.g. the Chrome address bar) answer very slowly. A timed out call
	// cannot really be stopped, it keeps running in the application. With a single worker thread, every new
	// request then waits behind it, times out too, and each one blocks the keyboard for the whole timeout
	// on every key press. So, while a previous request is still unanswered, we do not queue new ones.
	private static final AtomicBoolean busy = new AtomicBoolean(false);
	private static volatile long busySince = 0;
	private static final long BUSY_MAX_MS = 3000; // safety net, in case a request is lost
	private enum TaskType { GET_TEXT_BEFORE_CURSOR, GET_TEXT_AFTER_CURSOR, GET_EXTRACTED_TEXT }

	private InputConnectionAsync() {} // avoid instantiation to prevent executor memory leaks

	public static void destroy() {
		if (executor != null) {
			executor.shutdownNow();
		}
		busy.set(false);
	}

	public static CharSequence getTextBeforeCursor(boolean isUs, InputConnection connection, int length, int flags) {
		return connection != null ? run(isUs, () -> connection.getTextBeforeCursor(length, flags), TaskType.GET_TEXT_BEFORE_CURSOR) : null;
	}

	public static CharSequence getTextAfterCursor(boolean isUs, InputConnection connection, int length, int flags) {
		return connection != null ? run(isUs, () -> connection.getTextAfterCursor(length, flags), TaskType.GET_TEXT_AFTER_CURSOR) : null;
	}

	public static ExtractedText getExtractedText(boolean isUs, InputConnection connection, ExtractedTextRequest request, int flags) {
		return connection != null ? run(isUs, () -> connection.getExtractedText(request, flags), TaskType.GET_EXTRACTED_TEXT) : null;
	}

	private static <T> T getTimeout(TaskType taskType) {
		if (taskType == TaskType.GET_EXTRACTED_TEXT) {
			ExtractedText et = new ExtractedText();
			et.text = TIMEOUT_SENTINEL;
			return (T) et;
		} else if (taskType == TaskType.GET_TEXT_BEFORE_CURSOR || taskType == TaskType.GET_TEXT_AFTER_CURSOR) {
			return (T) TIMEOUT_SENTINEL;
		} else {
			return null;
		}
	}

	private static ExecutorService getExecutor() {
		if (executor == null || executor.isShutdown() || executor.isTerminated()) {
			executor = Executors.newSingleThreadExecutor();
		}

		return executor;
	}


	private static <T> T run(boolean isUs, Callable<T> task, TaskType type) {
		return isUs ? runSync(task) : runAsync(task, type);
	}


	private static <T> T runAsync(Callable<T> task, TaskType type) {
		final long now = System.currentTimeMillis();
		if (busy.get() && now - busySince > BUSY_MAX_MS) {
			busy.set(false);
		}
		if (!busy.compareAndSet(false, true)) {
			return getTimeout(type); // the application is still busy with the previous request
		}
		busySince = now;

		final AtomicBoolean started = new AtomicBoolean(false);
		final Callable<T> wrapped = () -> {
			started.set(true);
			try {
				return task.call();
			} finally {
				busy.set(false);
			}
		};

		Future<T> future;
		try {
			future = getExecutor().submit(wrapped);
		} catch (Exception e) {
			busy.set(false);
			Logger.w(LOG_TAG, type.name() + " could not be started. " + e);
			return null;
		}

		try {
			return future.get(SettingsStore.INPUT_CONNECTION_MAX_WAIT, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			future.cancel(true);
			if (!started.get()) {
				busy.set(false); // it was cancelled before it ever ran
			}
			Logger.w(LOG_TAG, type.name() + " timed out");
			return getTimeout(type);
		} catch (Exception e) {
			busy.set(false);
			Logger.w(LOG_TAG, type.name() + " failed. " + e);
			return null;
		}
	}


	private static <T> T runSync(Callable<T> task) {
		try {
			return task.call();
		} catch (Exception e) {
			Logger.w(LOG_TAG, "Task failed. " + e);
			return null;
		}
	}
}
