package io.github.sspanak.tt9.ime;

import android.os.SystemClock;
import android.view.KeyEvent;

import io.github.sspanak.tt9.ime.helpers.Key;
import io.github.sspanak.tt9.preferences.screens.debug.DropDownInputHandlingMode;
import io.github.sspanak.tt9.preferences.settings.SettingsStore;
import io.github.sspanak.tt9.util.Logger;
import io.github.sspanak.tt9.util.Timer;


abstract class KeyPadHandler extends UiHandler {
	// debounce handling
	private final static String DEBOUNCE_TIMER = "debounce_";

	// temporal key handling
	private int ignoreNextKeyUp = 0;

	private int lastKeyCode = 0;
	private int keyRepeatCounter = 0;

	private int lastNumKeyCode = 0;
	private int numKeyRepeatCounter = 0;

	// Customized build: when "Back" is also the "Backspace" key and the text field is empty, the next
	// "Back" press does its original job (go back / leave the app). To avoid leaving the app by
	// accident after quickly deleting the last characters, this must happen at least this long after
	// the last real deletion.
	private static final long BACK_AFTER_DELETE_COOLDOWN_MS = 800;
	private long lastBackspaceDeletionTime = 0;

	// Customized build: the synthetic "Back" for the application is sent when the key is released, not when
	// it is pressed. Otherwise, the keyboard hides while the physical key is still down, and the application
	// receives the rest of the hold as a long "Back" (Chrome shows its tab history menu).
	private long pendingBackSince = 0;
	private boolean isBackPending() {
		return pendingBackSince > 0 && SystemClock.uptimeMillis() - pendingBackSince < 5000;
	}

	// Customized build: true only after the user collapsed the keyboard on purpose with the "Hide keyboard" command
	// (hold "*", then "0"). Until the user types again or a new input starts, "Back" belongs to the application.
	private boolean keyboardCollapsedByUser = false;

	public void setKeyboardCollapsedByUser(boolean collapsed) {
		keyboardCollapsedByUser = collapsed;
		if (collapsed) {
			pendingBackSince = 0;
		}
	}

	// Customized build: "Back" was taken as "Backspace" on key down, so the matching key up must be swallowed too.
	private boolean forcedBackDown = false;

	/**
	 * Customized build: in Bilibili and the apps from the "Back key apps" list, "Back" is ALWAYS "Backspace",
	 * no matter what the "Backspace" hotkey setting is. The only exception is a manually collapsed keyboard.
	 */
	private boolean isForcedBackspaceBack(int keyCode) {
		return Key.isBack(keyCode) && !keyboardCollapsedByUser && !isFnPanelVisible() && isBackKeyApp();
	}

	/**
	 * Customized build: in Bilibili and the apps from the "Back key apps" list, "Back" is always "Backspace", except
	 * when the user has collapsed the keyboard manually. Applications that are not in the list keep the default behavior.
	 */
	private boolean isBackKeyAppKeyboardCollapsed(int keyCode) {
		return Key.isBack(keyCode)
			&& keyboardCollapsedByUser
			&& !isFnPanelVisible()
			&& isBackKeyApp();
	}


	/**
	 * Main initialization of the input method component. Be sure to call to
	 * super class.
	 */
	@Override
	public void onCreate() {
		super.onCreate();
		settings = new SettingsStore(getApplicationContext());

		onInit();
	}


	/**
	 * Use this to monitor key events being delivered to the application. We get
	 * first crack at them, and can either resume them or let them continue to
	 * the app.
	 */
	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (Key.isBack(keyCode)) {
			Logger.i("KeyPadHandler", "Back DOWN reached the IME. repeat: " + event.getRepeatCount() + " shouldBeOff: " + shouldBeOff() + " listedApp: " + isBackKeyApp() + " collapsedByUser: " + keyboardCollapsedByUser + " fnPanel: " + isFnPanelVisible() + " isBackspaceHotkey: " + Key.isBackspace(settings, keyCode) + " inputMode: " + settings.getInputHandlingMode());
		}

		if (debounceKey(keyCode, event)) {
			return true;
		}

		if (settings.getInputHandlingMode() == DropDownInputHandlingMode.RETURN_FALSE) {
			return false;
		} else if (settings.getInputHandlingMode() == DropDownInputHandlingMode.CALL_SUPER) {
			return super.onKeyDown(keyCode, event);
		}

		if (shouldBeOff()) {
			return false;
		}

		if (!Key.isBack(keyCode) && keyboardCollapsedByUser && !isFnPanelVisible()) {
			keyboardCollapsedByUser = false;
		}

		// Customized build: the keyboard is collapsed in a listed application. Give "Back" to the application untouched.
		if (isBackKeyAppKeyboardCollapsed(keyCode)) {
			pendingBackSince = 0;
			Key.setHandled(KeyEvent.KEYCODE_DEL, false);
			Key.setHandled(KeyEvent.KEYCODE_BACK, false);
			return false;
		}

		// Customized build: forced "Back" = "Backspace" in listed applications, regardless of the hotkey settings.
		if (isForcedBackspaceBack(keyCode)) {
			forcedBackDown = true;
			pendingBackSince = 0;
			if (!onBackspace(event.getRepeatCount())) {
				sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
			}
			return true;
		}

//		Logger.d("onKeyDown", "Key: " + event + " repeat?: " + event.getRepeatCount() + " long-time: " + event.isLongPress());

		// Customized build: when the command popup is open, "Back" closes it, instead of deleting text.
		final boolean backClosesPanel = Key.isBack(keyCode) && isFnPanelVisible();
		if (backClosesPanel) {
			Key.setHandled(KeyEvent.KEYCODE_DEL, false);
		}

		if (Key.isBack(keyCode) && Key.isBackspace(settings, keyCode) && isBackPending()) {
			return true; // the key is being held, after we have decided to send "Back" to the application
		}

		// "backspace" key must repeat its function when held down, so we handle it in a special way
		if (Key.isBackspace(settings, keyCode) && !backClosesPanel) {
			boolean nothingToDelete = false;
			if (Key.isBack(keyCode) && event.getRepeatCount() == 0) {
				nothingToDelete = isNothingToDelete();
				if (!nothingToDelete) {
					lastBackspaceDeletionTime = SystemClock.uptimeMillis();
				}
			}

			if (nothingToDelete && SystemClock.uptimeMillis() - lastBackspaceDeletionTime > BACK_AFTER_DELETE_COOLDOWN_MS) {
				// Nothing to delete: send "Back" straight to the application. The event goes to the
				// application's input connection, not back to us, so there is no loop. Without this, the
				// default handling would only hide the keyboard first.
				pendingBackSince = SystemClock.uptimeMillis(); // sent in onKeyUp()
				return Key.setHandled(KeyEvent.KEYCODE_DEL, true); // also makes onKeyUp() swallow the release
			} else if (onBackspace(event.getRepeatCount())) {
				return Key.setHandled(KeyEvent.KEYCODE_DEL, true);
			} else if (Key.isBack(keyCode) && !nothingToDelete && isBackKeyApp()) {
				// Customized build: Bilibili and the apps from the "Back key apps" list do not tell us what is in
				// their text field, so onBackspace() sees it as empty and does nothing. Then "Back" would go straight
				// to the application and leave the screen. The on-screen Backspace does the same fallback (CmdBackspace):
				// it sends a real DEL key. Here we do it for the hardware "Back", and the application never sees "Back".
				sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
				return Key.setHandled(KeyEvent.KEYCODE_DEL, true);
			} else {
				Key.setHandled(KeyEvent.KEYCODE_DEL, false);
			}
		}

		// start tracking key hold
		if (Key.isNumber(keyCode)) {
			event.startTracking();
			return true;
		}
		else if (getFinalContext().isHoldHotkey(-keyCode)) {
			event.startTracking();
		}

		// on many devices there is a default back handler, so we must fall back to it when we don't
		// perform any operation
		if (Key.isBack(keyCode)) {
			Key.setHandled(keyCode, onBack());
			return Key.isHandledInSuper(keyCode) ? super.onKeyDown(keyCode, event) : Key.isHandled(keyCode);
		} else {
			Key.setHandled(KeyEvent.KEYCODE_BACK, false);
		}

		return
			Key.setHandled(KeyEvent.KEYCODE_ENTER, Key.isOK(keyCode) && onOK())
			|| handleHotkey(keyCode, true, false, true) // hold a hotkey, handled in onKeyLongPress())
			|| handleHotkey(keyCode, false, keyRepeatCounter + 1 > 0, true) // press a hotkey, handled in onKeyUp()
			|| Key.isPoundOrStar(keyCode) && onText(String.valueOf((char) event.getUnicodeChar()), true)
			|| super.onKeyDown(keyCode, event); // let the system handle the keys we don't care about (usually, the touch "buttons")
	}


	@Override
	public boolean onKeyLongPress(int keyCode, KeyEvent event) {
		if (settings.getInputHandlingMode() == DropDownInputHandlingMode.RETURN_FALSE) {
			return false;
		} else if (settings.getInputHandlingMode() == DropDownInputHandlingMode.CALL_SUPER) {
			return super.onKeyLongPress(keyCode, event);
		}

		if (shouldBeOff()) {
			return false;
		}

//		Logger.d("onLongPress", "LONG PRESS: " + keyCode);

		if (event.getRepeatCount() > 1) {
			return true;
		}

		ignoreNextKeyUp = keyCode;
		if (Key.isNumber(keyCode)) {
			numKeyRepeatCounter = 0;
			lastNumKeyCode = 0;
			return onNumber(Key.codeToNumber(settings, keyCode), true, 0);
		} else {
			keyRepeatCounter = 0;
			lastKeyCode = 0;
		}

		if (handleHotkey(keyCode, true, false, false)) {
			return true;
		}

		ignoreNextKeyUp = 0;
		return false;
	}


	/**
	 * Use this to monitor key events being delivered to the application. We get
	 * first crack at them, and can either resume them or let them continue to
	 * the app.
	 */
	@Override
	public boolean onKeyUp(int keyCode, KeyEvent event) {
		if (Key.isBack(keyCode)) {
			Logger.i("KeyPadHandler", "Back UP. forcedDown: " + forcedBackDown + " listedApp: " + isBackKeyApp() + " collapsedByUser: " + keyboardCollapsedByUser);
		}

		if (debounceKey(keyCode, event)) {
			return true;
		}

		if (settings.getInputHandlingMode() == DropDownInputHandlingMode.RETURN_FALSE) {
			return false;
		} else if (settings.getInputHandlingMode() == DropDownInputHandlingMode.CALL_SUPER) {
			return super.onKeyUp(keyCode, event);
		}

		if (shouldBeOff()) {
			return false;
		}

//		Logger.d("onKeyUp", "Key: " + keyCode + " repeat?: " + event.getRepeatCount());

		if (Key.isBack(keyCode) && forcedBackDown) {
			forcedBackDown = false;
			return true;
		}

		if (keyCode == ignoreNextKeyUp) {
//			Logger.d("onKeyUp", "Ignored: " + keyCode);
			ignoreNextKeyUp = 0;
			return true;
		}

		if (isBackKeyAppKeyboardCollapsed(keyCode)) {
			return false; // the application handles "Back" itself
		}

		if (Key.isBackspace(settings, keyCode) && Key.isHandled(KeyEvent.KEYCODE_DEL)) {
			if (Key.isBack(keyCode) && isBackPending()) {
				pendingBackSince = 0;
				// Hide the keyboard, like the system does. The next "Back" goes to the application as a real key.
				requestHideSelf(0);
			}
			return true;
		}

		keyRepeatCounter = (lastKeyCode == keyCode) ? keyRepeatCounter + 1 : 0;
		lastKeyCode = keyCode;

		if (Key.isNumber(keyCode)) {
			numKeyRepeatCounter = (lastNumKeyCode == keyCode) ? numKeyRepeatCounter + 1 : 0;
			lastNumKeyCode = keyCode;
			return onNumber(Key.codeToNumber(settings, keyCode), false, numKeyRepeatCounter);
		}

		if (Key.isBack(keyCode)) {
			return Key.isHandledInSuper(keyCode) ? super.onKeyUp(keyCode, event) : Key.isHandled(keyCode);
		}

		return
			(Key.isOK(keyCode) && Key.isHandled(KeyEvent.KEYCODE_ENTER))
			|| handleHotkey(keyCode, false, keyRepeatCounter > 0, false)
			|| Key.isPoundOrStar(keyCode) && onText(String.valueOf((char) event.getUnicodeChar()), false)
			|| super.onKeyUp(keyCode, event); // let the system handle the keys we don't care about (usually, the touch "buttons")
	}


	/**
	 * Customized build: true when pressing "Backspace" would do nothing at all. Overridden in TypingHandler.
	 */
	protected boolean isNothingToDelete() {
		return false;
	}


	/**
	 * Customized build: true when the current application is Bilibili or is in the "Back key apps" list. In such an
	 * application, "Back" must always work as "Backspace". Overridden in TypingHandler.
	 */
	protected boolean isBackKeyApp() {
		return false;
	}


	private boolean handleHotkey(int keyCode, boolean hold, boolean repeat, boolean validateOnly) {
		return onHotkey(keyCode * (hold ? -1 : 1), repeat, validateOnly);
	}


	public void resetKeyRepeat() {
		numKeyRepeatCounter = 0;
		keyRepeatCounter = 0;
		lastNumKeyCode = 0;
		lastKeyCode = 0;
	}


	private boolean debounceKey(int keyCode, KeyEvent event) {
		if (settings.getKeyPadDebounceTime() <= 0 || event.isLongPress()) {
			return false;
		}

		String keyTimer = DEBOUNCE_TIMER + keyCode;

		if (Timer.get(keyTimer) > 0 && Timer.get(keyTimer) < settings.getKeyPadDebounceTime()) {
			return true;
		}

		if (event.getAction() == KeyEvent.ACTION_UP) {
			Timer.start(keyTimer);
		}

		return false;
	}
}
