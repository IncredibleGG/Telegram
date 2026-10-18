package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LuminaConfig;
import org.telegram.messenger.LuminaLocale;
import org.telegram.messenger.LuminaRegister;
import org.telegram.messenger.R;
import org.telegram.messenger.TranslateController;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.TranslateAlert2;

import java.util.ArrayList;

/**
 * LuminaGram: the three-row menu behind the translate icon in a chat's title bar, ported from
 * the desktop client (lumina/lumina_chat_language_menu.cpp) so both platforms behave alike.
 *
 * One row per direction — what arrives, what is sent — each naming its own current state and
 * opening the language list when pressed, and below them a third row for the chat's register:
 * who this person is to you, and therefore how a translation of the chat should sound. The rows
 * are built fresh on every open, so they never carry stale text; nothing here is retained
 * between openings.
 */
public final class LuminaChatLanguageMenu {

    // The code the "not translated" entry stores. It has to be one no language owns, and it
    // has to differ from the empty string, which already means "follow the interface
    // language" everywhere the read language is read.
    private static final String OFF_CODE = "off";

    private static final String KEY_SEND_LANG = "trSendLang";
    private static final String AUTO = "auto";

    private LuminaChatLanguageMenu() {}

    /**
     * Open the menu under {@code anchor} (the header translate icon).
     *
     * @param onChanged run on the UI thread after either direction was edited, so the caller
     *                  can refresh the icon tint and the translate top panel. May be null.
     */
    public static void show(BaseFragment fragment, View anchor, long dialogId, Runnable onChanged) {
        if (fragment == null || anchor == null) {
            return;
        }
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final TranslateController controller = translateController(fragment);
        if (controller == null) {
            return;
        }
        try {
            final Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();
            final ActionBarPopupWindow.ActionBarPopupWindowLayout layout =
                    new ActionBarPopupWindow.ActionBarPopupWindowLayout(context, R.drawable.popup_fixed_alert2, resourcesProvider);
            // The window is created below, after its content exists, so the row callbacks reach
            // it through this holder rather than through a field that would outlive the menu.
            final ActionBarPopupWindow[] window = new ActionBarPopupWindow[1];

            final ActionBarMenuSubItem incoming = new ActionBarMenuSubItem(context, true, false, resourcesProvider);
            incoming.setTextAndIcon(incomingRowText(controller, dialogId), R.drawable.msg_download);
            // Rows wide enough to say "Them, translated into Traditional Chinese" without
            // eliding: the language at the end of the row is the whole point of the row.
            incoming.setMultiline(false);
            incoming.setMinimumWidth(dp(220));
            incoming.setOnClickListener(v -> {
                dismiss(window);
                showIncomingPicker(fragment, controller, dialogId, onChanged);
            });
            layout.addView(incoming, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            final ActionBarMenuSubItem outgoing = new ActionBarMenuSubItem(context, false, false, resourcesProvider);
            outgoing.setTextAndIcon(outgoingRowText(dialogId), R.drawable.msg_send);
            outgoing.setMultiline(false);
            outgoing.setMinimumWidth(dp(220));
            outgoing.setOnClickListener(v -> {
                dismiss(window);
                showOutgoingPicker(fragment, dialogId, onChanged);
            });
            layout.addView(outgoing, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            final ActionBarMenuSubItem register = new ActionBarMenuSubItem(context, false, true, resourcesProvider);
            register.setTextAndIcon(registerRowText(dialogId), R.drawable.msg_customize);
            register.setMultiline(false);
            register.setMinimumWidth(dp(220));
            // The caveat, when there is one, sits under the row in grey. An engine that cannot
            // carry tone has to say so here: a register the user set and the engine silently drops
            // is worse than no register at all, because it reads as having worked.
            final CharSequence caveat = registerRowCaveat(dialogId);
            if (caveat != null) {
                register.setSubtext(caveat);
                register.setSubtextColor(Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider));
            }
            register.setOnClickListener(v -> {
                dismiss(window);
                showRegisterPicker(fragment, dialogId, onChanged);
            });
            layout.addView(register, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            layout.setupRadialSelectors(Theme.getColor(Theme.key_dialogButtonSelector, resourcesProvider));

            window[0] = new ActionBarPopupWindow(layout, LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT);
            window[0].setPauseNotifications(true);
            window[0].setDismissAnimationDuration(220);
            window[0].setOutsideTouchable(true);
            window[0].setClippingEnabled(true);
            window[0].setAnimationStyle(R.style.PopupContextAnimation);
            window[0].setFocusable(true);
            window[0].setInputMethodMode(ActionBarPopupWindow.INPUT_METHOD_NOT_NEEDED);

            layout.measure(
                    View.MeasureSpec.makeMeasureSpec(dp(1000), View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(dp(1000), View.MeasureSpec.AT_MOST));
            // Hang the menu off the icon's right edge, then pull it back on screen if that
            // would push it past the left one (narrow screens, RTL layouts).
            final int[] location = new int[2];
            anchor.getLocationInWindow(location);
            int offsetX = anchor.getWidth() - layout.getMeasuredWidth() + dp(8);
            if (location[0] + offsetX < dp(8)) {
                offsetX = dp(8) - location[0];
            }
            window[0].showAsDropDown(anchor, offsetX, -dp(8));
        } catch (Exception e) {
            // A menu that cannot be shown is a menu that is not shown; it is never a reason to
            // take the chat down with it.
            FileLog.e(e);
        }
    }

    private static TranslateController translateController(BaseFragment fragment) {
        try {
            return fragment.getMessagesController() == null
                    ? null
                    : fragment.getMessagesController().getTranslateController();
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static void dismiss(ActionBarPopupWindow[] window) {
        if (window[0] != null && window[0].isShowing()) {
            window[0].dismiss();
        }
    }

    // Display name for a stored code, falling back to the raw code when unknown.
    private static String languageName(String code) {
        if (code == null || code.length() == 0) {
            return "";
        }
        final String name = TranslateAlert2.capitalFirst(TranslateAlert2.languageName(code));
        return name != null ? name : code;
    }

    // ---- incoming ------------------------------------------------------------------------

    // What the incoming half reads, as a whole row.
    //
    // Off is its own string rather than the word "off" substituted into the "into {1}" one,
    // which would say "translated into not translated".
    //
    // The stored read language is empty until it has been chosen at least once, and the row
    // still has to name one: empty means whatever the chat would actually be translated into,
    // which is what getDialogTranslateTo() resolves.
    private static String incomingRowText(TranslateController controller, long dialogId) {
        if (controller.isTranslateDialogHidden(dialogId) || !controller.isTranslatingDialog(dialogId)) {
            return LuminaLocale.getString(R.string.LuminaChatLangThemOff);
        }
        final String code = controller.getDialogTranslateTo(dialogId);
        return String.format(LuminaLocale.getString(R.string.LuminaChatLangThem), languageName(code));
    }

    private static void showIncomingPicker(BaseFragment fragment, TranslateController controller, long dialogId, Runnable onChanged) {
        // LuminaGram: Saved Messages, 777000 (login codes) and other Telegram official / support
        // chats are never translated as a whole (LuminaPrivacyGate). Say so instead of offering a
        // language list that could not take effect; single messages can still be translated by hand.
        if (controller.isLuminaProtectedDialog(dialogId)) {
            try {
                BulletinFactory.of(fragment)
                        .createSimpleBulletin(R.raw.msg_translate, LuminaLocale.getString(R.string.LuminaTrProtectedChat))
                        .show();
            } catch (Exception e) {
                FileLog.e(e);
            }
            return;
        }
        showLanguagePicker(
                fragment,
                LuminaLocale.getString(R.string.LuminaChatLangThemTitle),
                code -> {
                    if (OFF_CODE.equals(code)) {
                        // Excluding rather than merely stopping. Stopping lasts until the next
                        // message this chat is recognised from and the offer comes straight
                        // back, which reads as the choice not having been taken.
                        controller.toggleTranslatingDialog(dialogId, false);
                        controller.setHideTranslateDialog(dialogId, true);
                    } else {
                        // Read before writing: the row was already translating into something,
                        // and only a real change needs the pipeline restarted below.
                        final String previous = controller.getDialogTranslateTo(dialogId);
                        final boolean wasTranslating = controller.isTranslatingDialog(dialogId)
                                && !controller.isTranslateDialogHidden(dialogId);

                        if (controller.isTranslateDialogHidden(dialogId)) {
                            // Naming a language also takes the chat back off the excluded list.
                            controller.setHideTranslateDialog(dialogId, false, true);
                        }
                        // LuminaGram: per-chat target ONLY -- store it for THIS dialog and
                        // never a global key, so the choice cannot leak to other chats
                        // (getDialogTranslateTo resolves this per-dialog value in every mode).
                        controller.setDialogTranslateTo(dialogId, code);
                        if (wasTranslating && !TextUtils.equals(previous, code)) {
                            // Already translating into another language: stop first, so the
                            // messages already on screen are re-run into the new target rather
                            // than left sitting in the old one.
                            controller.toggleTranslatingDialog(dialogId, false);
                        }
                        controller.toggleTranslatingDialog(dialogId, true);
                    }
                    if (onChanged != null) {
                        onChanged.run();
                    }
                });
    }

    // ---- outgoing ------------------------------------------------------------------------

    // The language outgoing messages in this chat are actually translated into, resolved the
    // same way the send path resolves it (ChatActivityEnterView#luminaResolveSendLang): a
    // fixed global language wins, then this chat's locked one. Null means neither is set, so
    // the send flow asks once - "the recipient's language" is an answer, not a missing one.
    private static String effectiveSendLanguage(long dialogId) {
        final String global = LuminaConfig.getString(KEY_SEND_LANG, AUTO);
        if (global != null && global.length() > 0 && !AUTO.equals(global)) {
            return global;
        }
        final String locked = LuminaConfig.getDialogSendLang(dialogId);
        if (locked != null && locked.length() > 0) {
            return locked;
        }
        return null;
    }

    private static String outgoingRowText(long dialogId) {
        // Off unless the global capability is on AND this chat is turned on (per-dialog switch,
        // default off). translateBeforeSend alone no longer translates anything.
        if (!LuminaConfig.translateBeforeSend || !LuminaConfig.getDialogSendEnabled(dialogId)) {
            return LuminaLocale.getString(R.string.LuminaChatLangMeOff);
        }
        final String code = effectiveSendLanguage(dialogId);
        final String name = (code == null)
                ? LuminaLocale.getString(R.string.LuminaTranslateSendLangAuto)
                : languageName(code);
        return String.format(LuminaLocale.getString(R.string.LuminaChatLangMe), name);
    }

    private static void showOutgoingPicker(BaseFragment fragment, long dialogId, Runnable onChanged) {
        showLanguagePicker(
                fragment,
                LuminaLocale.getString(R.string.LuminaChatLangMeTitle),
                code -> {
                    if (OFF_CODE.equals(code)) {
                        // Turn translate-before-send OFF for THIS chat only. The global capability
                        // and every other chat are left untouched (per-dialog switch, default off).
                        LuminaConfig.setDialogSendEnabled(dialogId, false);
                    } else {
                        LuminaConfig.setDialogSendLang(dialogId, code);
                        // Choosing a language turns this chat ON (per-dialog switch, default off).
                        LuminaConfig.setDialogSendEnabled(dialogId, true);
                        // The send path prefers a fixed global send language over this chat's
                        // locked one, so when the global is what governs this chat, the global
                        // is the knob this row edits - otherwise the language just chosen
                        // would be silently overridden on the next send.
                        final String global = LuminaConfig.getString(KEY_SEND_LANG, AUTO);
                        if (global != null && global.length() > 0 && !AUTO.equals(global)) {
                            LuminaConfig.putString(KEY_SEND_LANG, code);
                        }
                        // Flip the global capability on if it was off, so the chosen language
                        // actually takes effect (the capability gates every per-dialog switch).
                        if (!LuminaConfig.translateBeforeSend) {
                            LuminaConfig.toggleTranslateBeforeSend();
                        }
                    }
                    if (onChanged != null) {
                        onChanged.run();
                    }
                });
    }

    // ---- register ------------------------------------------------------------------------

    // The third row names the relationship this chat is in, because that is what decides how
    // both halves above should sound. Unset is its own string: "Tone: not set" is a state, and
    // substituting a word for "none" into "Tone: {1}" would read like a choice was made.
    private static String registerRowText(long dialogId) {
        final String stored = LuminaRegister.get(dialogId);
        if (stored == null || stored.length() == 0) {
            return LuminaLocale.getString(R.string.LuminaChatRegisterOff);
        }
        return String.format(LuminaLocale.getString(R.string.LuminaChatRegister), LuminaRegister.displayName(stored));
    }

    // What the currently selected engine can actually do with a register, said plainly, or null
    // when there is nothing to warn about (no register set, or an engine that honours it fully).
    private static CharSequence registerRowCaveat(long dialogId) {
        final String stored = LuminaRegister.get(dialogId);
        if (stored == null || stored.length() == 0) {
            return null;
        }
        if (LuminaRegister.engineIgnoresRegister()) {
            return LuminaLocale.getString(R.string.LuminaChatRegisterUnsupported);
        }
        if (LuminaRegister.engineIsDeepL()) {
            return LuminaLocale.getString(R.string.LuminaChatRegisterDeepL);
        }
        return null;
    }

    /**
     * The register list: "Not set" first, then the presets, then "Custom…". Each entry carries a
     * sentence of its own, because "Client" and "Colleague" only differ in what they do to the
     * translation — the name alone does not tell anyone which one they want.
     *
     * Built by hand rather than through {@code AlertDialog.setItems}, whose rows are a single
     * fixed-height line with no room for the explanation.
     */
    private static void showRegisterPicker(BaseFragment fragment, long dialogId, Runnable onChanged) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        try {
            final String current = LuminaRegister.get(dialogId);

            final ArrayList<String> codes = new ArrayList<>();
            codes.add(LuminaRegister.NONE);
            for (int i = 0; i < LuminaRegister.CODES.length; ++i) {
                codes.add(LuminaRegister.CODES[i]);
            }
            codes.add(LuminaRegister.CUSTOM);

            final LinearLayout list = new LinearLayout(context);
            list.setOrientation(LinearLayout.VERTICAL);

            final AlertDialog.Builder builder = new AlertDialog.Builder(context, fragment.getResourceProvider());
            builder.setTitle(LuminaLocale.getString(R.string.LuminaChatRegisterTitle));
            builder.setView(list);
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            final AlertDialog dialog = builder.create();

            for (int i = 0; i < codes.size(); ++i) {
                final String code = codes.get(i);
                final boolean selected = LuminaRegister.CUSTOM.equals(code)
                        ? LuminaRegister.isCustom(current)
                        : code.equals(current == null ? LuminaRegister.NONE : current);
                final View row = registerRow(context, registerOptionName(code), registerOptionInfo(code), selected);
                row.setOnClickListener(v -> {
                    dialog.dismiss();
                    if (LuminaRegister.CUSTOM.equals(code)) {
                        showCustomRegisterInput(fragment, dialogId, onChanged);
                    } else {
                        LuminaRegister.set(dialogId, code);
                        if (onChanged != null) {
                            onChanged.run();
                        }
                    }
                });
                list.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }

            fragment.showDialog(dialog);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static CharSequence registerOptionName(String code) {
        final int res = LuminaRegister.nameRes(code);
        return res == 0 ? LuminaLocale.getString(R.string.LuminaChatRegisterNone) : LuminaLocale.getString(res);
    }

    private static CharSequence registerOptionInfo(String code) {
        final int res = LuminaRegister.infoRes(code);
        return res == 0 ? LuminaLocale.getString(R.string.LuminaChatRegisterNoneInfo) : LuminaLocale.getString(res);
    }

    // One picker row: the name, and under it the sentence that says what choosing it does. The
    // current choice is drawn in the accent colour rather than with a checkmark, so the row keeps
    // its full width for the explanation.
    private static View registerRow(Context context, CharSequence title, CharSequence info, boolean selected) {
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_dialogButtonSelector), 2));
        row.setPadding(dp(22), dp(10), dp(22), dp(10));

        final TextView name = new TextView(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        name.setTextColor(Theme.getColor(selected ? Theme.key_dialogTextBlue2 : Theme.key_dialogTextBlack));
        name.setText(title);
        row.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextView detail = new TextView(context);
        detail.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        detail.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        detail.setText(info);
        row.addView(detail, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        return row;
    }

    // "Custom" is the escape hatch for every relationship the six presets do not name — a thesis
    // advisor, a landlord, an ex. The sentence the user writes is handed to the model as-is.
    private static void showCustomRegisterInput(BaseFragment fragment, long dialogId, Runnable onChanged) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        try {
            final EditTextBoldCursor edit = new EditTextBoldCursor(context);
            edit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            edit.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            edit.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
            edit.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
            edit.setCursorSize(dp(20));
            edit.setCursorWidth(1.5f);
            edit.setBackgroundDrawable(null);
            edit.setSingleLine(false);
            edit.setMaxLines(4);
            edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            edit.setHint(LuminaLocale.getString(R.string.LuminaChatRegisterCustomHint));
            edit.setText(LuminaRegister.customText(LuminaRegister.get(dialogId)));
            edit.setSelection(edit.length());

            final FrameLayout container = new FrameLayout(context);
            container.addView(edit, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.CENTER_VERTICAL, 24, 6, 24, 0));

            final AlertDialog.Builder builder = new AlertDialog.Builder(context, fragment.getResourceProvider());
            builder.setTitle(LuminaLocale.getString(R.string.LuminaChatRegisterCustomTitle));
            builder.setView(container);
            builder.setPositiveButton(LocaleController.getString(R.string.Save), (d, which) -> {
                // An empty description is not a register: it clears the chat back to unset rather
                // than storing a "custom" that says nothing.
                LuminaRegister.set(dialogId, LuminaRegister.custom(edit.getText().toString()));
                AndroidUtilities.hideKeyboard(edit);
                if (onChanged != null) {
                    onChanged.run();
                }
            });
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            fragment.showDialog(builder.create());
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // ---- shared picker -------------------------------------------------------------------

    // The same list the translation settings page opens, with "Not translated" sitting on top
    // as the entry that turns this direction off. A second list built somewhere else is a
    // second set of languages to keep in step.
    private static void showLanguagePicker(BaseFragment fragment, CharSequence title, Utilities.Callback<String> save) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        try {
            final ArrayList<TranslateController.Language> languages = TranslateController.getLanguages();
            final CharSequence[] names = new CharSequence[languages.size() + 1];
            names[0] = LuminaLocale.getString(R.string.LuminaChatLangNone);
            for (int i = 0; i < languages.size(); ++i) {
                names[i + 1] = languages.get(i).displayName;
            }
            final AlertDialog.Builder builder = new AlertDialog.Builder(context, fragment.getResourceProvider());
            builder.setTitle(title);
            builder.setItems(names, (dialog, which) -> {
                try {
                    if (which == 0) {
                        save.run(OFF_CODE);
                    } else if (which - 1 >= 0 && which - 1 < languages.size()) {
                        save.run(languages.get(which - 1).code);
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
            });
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            fragment.showDialog(builder.create());
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
