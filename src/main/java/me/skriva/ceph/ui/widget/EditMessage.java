package me.skriva.ceph.ui.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Spanned;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import androidx.core.view.inputmethod.EditorInfoCompat;
import androidx.core.view.inputmethod.InputConnectionCompat;
import androidx.core.view.inputmethod.InputContentInfoCompat;

import com.vanniktech.emoji.EmojiEditTextInterface;
import com.vanniktech.emoji.emoji.Emoji;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.skriva.ceph.Config;
import me.skriva.ceph.R;

public class EditMessage extends EmojiWrapperEditText implements EmojiEditTextInterface {

	private float emojiSize = 48;
	private static final InputFilter SPAN_FILTER = (source, start, end, dest, dstart, dend) -> source instanceof Spanned ? source.toString() : source;
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final Handler mTypingHandler = new Handler();
	private KeyboardListener keyboardListener;
	private OnCommitContentListener mCommitContentListener = null;
	private String[] mimeTypes = null;
	private boolean isUserTyping = false;
	private final Runnable mTypingTimeout = new Runnable() {
		@Override
		public void run() {
			if (isUserTyping && keyboardListener != null) {
				keyboardListener.onTypingStopped();
				isUserTyping = false;
			}
		}
	};
	private boolean lastInputWasTab = false;

	public EditMessage(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public EditMessage(Context context) {
		super(context);
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent e) {
		if (keyCode == KeyEvent.KEYCODE_ENTER && !e.isShiftPressed()) {
			lastInputWasTab = false;
			if (keyboardListener != null && keyboardListener.onEnterPressed()) {
				return true;
			}
		} else if (keyCode == KeyEvent.KEYCODE_TAB && !e.isAltPressed() && !e.isCtrlPressed()) {
			if (keyboardListener != null && keyboardListener.onTabPressed(this.lastInputWasTab)) {
				lastInputWasTab = true;
				return true;
			}
		} else {
			lastInputWasTab = false;
		}
		return super.onKeyDown(keyCode, e);
	}

	@Override
	public int getAutofillType() {
		return AUTOFILL_TYPE_NONE;
	}


	@Override
	public void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
		super.onTextChanged(text, start, lengthBefore, lengthAfter);
		lastInputWasTab = false;
		if (this.mTypingHandler != null && this.keyboardListener != null) {
			executor.execute(() -> triggerKeyboardEvents(text.length()));
		}
	}

	private void triggerKeyboardEvents(final int length) {
		final KeyboardListener listener = this.keyboardListener;
		if (listener == null) {
			return;
		}
		this.mTypingHandler.removeCallbacks(mTypingTimeout);
		this.mTypingHandler.postDelayed(mTypingTimeout, Config.TYPING_TIMEOUT * 1000);
		if (!isUserTyping && length > 0) {
			this.isUserTyping = true;
			listener.onTypingStarted();
		} else if (length == 0) {
			this.isUserTyping = false;
			listener.onTextDeleted();
		}
		listener.onTextChanged();
	}

	public void setKeyboardListener(KeyboardListener listener) {
		this.keyboardListener = listener;
		if (listener != null) {
			this.isUserTyping = false;
		}
	}

	@Override
	public boolean onTextContextMenuItem(int id) {
		if (id == android.R.id.paste) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				return super.onTextContextMenuItem(android.R.id.pasteAsPlainText);
			} else {
				Editable editable = getEditableText();
				InputFilter[] filters = editable.getFilters();
				InputFilter[] tempFilters = new InputFilter[filters != null ? filters.length + 1 : 1];
				if (filters != null) {
					System.arraycopy(filters, 0, tempFilters, 1, filters.length);
				}
				tempFilters[0] = SPAN_FILTER;
				editable.setFilters(tempFilters);
				try {
					return super.onTextContextMenuItem(id);
				} finally {
					editable.setFilters(filters);
				}
			}
		} else {
			return super.onTextContextMenuItem(id);
		}
	}

	public void setRichContentListener(String[] mimeTypes, OnCommitContentListener listener) {
		this.mimeTypes = mimeTypes;
		this.mCommitContentListener = listener;
	}

	@Override
	public InputConnection onCreateInputConnection(EditorInfo editorInfo) {
		final InputConnection ic = super.onCreateInputConnection(editorInfo);

		if (mimeTypes != null && mCommitContentListener != null && ic != null) {
			EditorInfoCompat.setContentMimeTypes(editorInfo, mimeTypes);
			return InputConnectionCompat.createWrapper(ic, editorInfo, (inputContentInfo, flags, opts) -> EditMessage.this.mCommitContentListener.onCommitContent(inputContentInfo, flags, opts, mimeTypes));
		} else {
			return ic;
		}
	}

	public void refreshIme() {
		SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(getContext());
		final boolean usingEnterKey = p.getBoolean("display_enter_key", getResources().getBoolean(R.bool.display_enter_key));
		final boolean enterIsSend = p.getBoolean("enter_is_send", getResources().getBoolean(R.bool.enter_is_send));

		if (usingEnterKey && enterIsSend) {
			setInputType(getInputType() & (~InputType.TYPE_TEXT_FLAG_MULTI_LINE));
			setInputType(getInputType() & (~InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE));
		} else if (usingEnterKey) {
			setInputType(getInputType() | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
			setInputType(getInputType() & (~InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE));
		} else {
			setInputType(getInputType() | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
			setInputType(getInputType() | InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE);
		}
	}

	@Override
	public void backspace() {
		final KeyEvent event = new KeyEvent(0, 0, 0, KeyEvent.KEYCODE_DEL, 0, 0, 0, 0, KeyEvent.KEYCODE_ENDCALL);
		dispatchKeyEvent(event);
	}

	@Override
	public void input(Emoji emoji) {
		if (emoji != null) {
			final int start = getSelectionStart();
			final int end = getSelectionEnd();

			if (start < 0) {
				append(emoji.getUnicode());
			} else {
				getText().replace(Math.min(start, end), Math.max(start, end), emoji.getUnicode(), 0, emoji.getUnicode().length());
			}
		}
	}

	@Override
	public float getEmojiSize() {
		return emojiSize;
	}

	@Override
	public void setEmojiSize(int pixels) {
		setEmojiSize(pixels, true);
	}

	@Override
	public void setEmojiSize(int pixels, boolean shouldInvalidate) {
		emojiSize = pixels;

		if (shouldInvalidate) {
			setText(getText());
		}
	}

	@Override
	public void setEmojiSizeRes(int res) {
		setEmojiSizeRes(res, true);
	}

	@Override
	public void setEmojiSizeRes(int res, boolean shouldInvalidate) {
		setEmojiSize(getResources().getDimensionPixelSize(res), shouldInvalidate);
	}

	public interface OnCommitContentListener {
		boolean onCommitContent(InputContentInfoCompat inputContentInfo, int flags, Bundle opts, String[] mimeTypes);
	}

	public interface KeyboardListener {
		boolean onEnterPressed();

		void onTypingStarted();

		void onTypingStopped();

		void onTextDeleted();

		void onTextChanged();

		boolean onTabPressed(boolean repeated);
	}


}
