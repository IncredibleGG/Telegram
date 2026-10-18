package org.telegram.ui;

import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LuminaConfig;
import org.telegram.messenger.LuminaLocale;
import org.telegram.messenger.LuminaTranslator;
import org.telegram.messenger.LuminaTranslators;
import org.telegram.messenger.R;
import org.telegram.messenger.TranslateController;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.TranslateAlert2;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

/**
 * LuminaGram — auto-translate settings.
 * Organised as a clear two-direction model:
 *   1) 發送翻譯 (outgoing): translate-before-send + outgoing language.
 *   2) 收訊翻譯 (incoming): auto-translate incoming (bilingual) + reading language.
 *   3) 適用範圍 (scope): apply to private chats / groups.
 *   4) 翻譯服務商 (provider): bring-your-own-key provider settings.
 */
public class LuminaTranslateActivity extends BaseFragment {

    // Config keys owned by this screen (consumed by the translate pipeline elsewhere).
    private static final String KEY_SEND_LANG = "trSendLang";         // default "auto" (recipient's language)
    private static final String KEY_READ_LANG = "trReadLang";         // default ""     (follow app language)
    private static final String KEY_SCOPE_PRIVATE = "trScopePrivate"; // default true
    private static final String KEY_SCOPE_GROUP = "trScopeGroup";     // default true
    private static final String KEY_TR_MODE = "trMode";              // default "manual"
    private static final String TR_MODE_ALL = "all";
    private static final String TR_MODE_MANUAL = "manual";

    private static final int ITEM_TRANSLATE_BEFORE_SEND = 3;
    private static final int ITEM_TRANSLATE_BEFORE_SEND_CONFIRM = 4;
    private static final int ITEM_PROVIDER = 5;
    private static final int ITEM_API_KEY = 6;
    private static final int ITEM_BASE_URL = 7;
    private static final int ITEM_MODEL = 8;
    private static final int ITEM_SYSTEM_PROMPT = 9;
    private static final int ITEM_TEST = 10;
    private static final int ITEM_DUAL_LANGUAGE = 11;
    private static final int ITEM_SEND_LANG = 12;
    private static final int ITEM_READ_LANG = 13;
    private static final int ITEM_SCOPE_PRIVATE = 14;
    private static final int ITEM_SCOPE_GROUP = 15;
    private static final int ITEM_MODE_ALL = 16;
    private static final int ITEM_MODE_MANUAL = 17;
    private static final int ITEM_FOLD_ORIGINAL = 18;
    private static final int ITEM_OCR_TRANSLATE = 19;
    private static final int ITEM_EXPLAIN = 20;
    private static final int ITEM_GROUP_SKIP = 21;
    private static final int ITEM_REVERSE_VOICE = 22;

    private UniversalRecyclerView listView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LuminaLocale.getString(R.string.LuminaTranslateTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        return fragmentView;
    }

    // Display name for a stored code, falling back to the raw code when unknown.
    private CharSequence languageDisplayName(String code) {
        if (code == null || code.length() == 0) {
            return code;
        }
        String name = TranslateAlert2.capitalFirst(TranslateAlert2.languageName(code));
        return name != null ? name : code;
    }

    // Current value shown on the 送出語言 row. TextCell hard-ellipsizes its value at 40%
    // of the screen width, so the row gets the short form and the picker keeps the long one.
    private CharSequence currentSendLanguageName() {
        String code = LuminaConfig.getString(KEY_SEND_LANG, "auto");
        if (code == null || code.length() == 0 || "auto".equals(code)) {
            return LuminaLocale.getString(R.string.LuminaTranslateSendLangAutoShort);
        }
        return languageDisplayName(code);
    }

    // Current value shown on the 閱讀語言 row.
    private CharSequence currentReadLanguageName() {
        String code = LuminaConfig.getString(KEY_READ_LANG, "");
        if (code == null || code.length() == 0) {
            return LuminaLocale.getString(R.string.LuminaTranslateReadLangFollow);
        }
        return languageDisplayName(code);
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        // ---- 1) Sending (outgoing) ----
        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaTranslateSendHeader)));
        items.add(UItem.asSwitch(ITEM_TRANSLATE_BEFORE_SEND, LuminaLocale.getString(R.string.LuminaTranslateBeforeSend))
                .setChecked(LuminaConfig.translateBeforeSend));
        // The master toggle only ENABLES the feature; it translates nothing on its own. Each chat is
        // turned on separately (long-press Send, or the chat's translate menu). Spelled out here.
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaTranslateBeforeSendInfo)));
        items.add(UItem.asButton(ITEM_SEND_LANG, LuminaLocale.getString(R.string.LuminaTranslateSendLang), currentSendLanguageName()));
        items.add(UItem.asSwitch(ITEM_TRANSLATE_BEFORE_SEND_CONFIRM, LuminaLocale.getString(R.string.LuminaTranslateBeforeSendConfirm))
                .setChecked(LuminaConfig.translateBeforeSendConfirm));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaTranslateBeforeSendConfirmInfo)));

        // ---- Translate mode: auto-translate all chats vs only chats I turn on ----
        final String trMode = LuminaConfig.getString(KEY_TR_MODE, TR_MODE_MANUAL);
        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaTranslateModeHeader)));
        items.add(UItem.asRadio(ITEM_MODE_ALL, LuminaLocale.getString(R.string.LuminaTranslateModeAll))
                .setChecked(TR_MODE_ALL.equals(trMode)));
        items.add(UItem.asRadio(ITEM_MODE_MANUAL, LuminaLocale.getString(R.string.LuminaTranslateModeManual))
                .setChecked(!TR_MODE_ALL.equals(trMode)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaTranslateModeInfo)));

        // ---- 2) Receiving (incoming) ----
        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaTranslateReceiveHeader)));
        items.add(UItem.asSwitch(ITEM_DUAL_LANGUAGE, LuminaLocale.getString(R.string.LuminaDualLanguageDisplay))
                .setChecked(LuminaConfig.isDualLanguageDisplayEnabled()));
        items.add(UItem.asButton(ITEM_READ_LANG, LuminaLocale.getString(R.string.LuminaTranslateReadLang), currentReadLanguageName()));
        // Long bilingual messages: fold the ORIGINAL to one line so it doesn't flood the chat; the
        // translation is always shown in full. Defaults ON. Tapping a folded original expands it
        // (transient — reset on app restart).
        items.add(UItem.asSwitch(ITEM_FOLD_ORIGINAL, LuminaLocale.getString(R.string.LuminaFoldOriginalLongMessages))
                .setChecked(LuminaConfig.getBoolean("foldOriginalLongMessages", true)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaFoldOriginalLongMessagesInfo)));

        // ---- 3) Scope ----
        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaTranslateScopeHeader)));
        items.add(UItem.asSwitch(ITEM_SCOPE_PRIVATE, LuminaLocale.getString(R.string.LuminaTranslateScopePrivate))
                .setChecked(LuminaConfig.getBoolean(KEY_SCOPE_PRIVATE, true)));
        items.add(UItem.asSwitch(ITEM_SCOPE_GROUP, LuminaLocale.getString(R.string.LuminaTranslateScopeGroup))
                .setChecked(LuminaConfig.getBoolean(KEY_SCOPE_GROUP, true)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaTranslateScopeInfo)));

        // ---- 4) Multi-provider translation (bring-your-own key) ----
        final LuminaTranslator provider = LuminaTranslators.current();
        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaTranslateMoreHeader)));
        items.add(UItem.asSwitch(ITEM_OCR_TRANSLATE, LuminaLocale.getString(R.string.LuminaOcrTranslateSetting))
                .setChecked(LuminaConfig.ocrTranslate));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaOcrTranslateInfo)));
        items.add(UItem.asSwitch(ITEM_EXPLAIN, LuminaLocale.getString(R.string.LuminaExplainSetting))
                .setChecked(LuminaConfig.explainMessage));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaExplainSettingInfo)));
        items.add(UItem.asSwitch(ITEM_GROUP_SKIP, LuminaLocale.getString(R.string.LuminaGroupSkipSetting))
                .setChecked(LuminaConfig.isGroupSkipMyLanguagesEnabled()));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaGroupSkipSettingInfo)));
        items.add(UItem.asSwitch(ITEM_REVERSE_VOICE, LuminaLocale.getString(R.string.LuminaReverseVoiceSetting))
                .setChecked(LuminaConfig.reverseVoice));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaReverseVoiceSettingInfo)));
        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaTranslateProviderHeader)));
        items.add(UItem.asButton(ITEM_PROVIDER, LuminaLocale.getString(R.string.LuminaTranslateProvider), provider.displayName()));
        if (provider.needsKey()) {
            items.add(UItem.asButton(ITEM_API_KEY, LuminaLocale.getString(R.string.LuminaTranslateApiKey), maskKey(currentKey(provider))));
        }
        if (provider.needsBaseUrl()) {
            items.add(UItem.asButton(ITEM_BASE_URL, LuminaLocale.getString(R.string.LuminaTranslateBaseUrl),
                    LuminaConfig.getString("translateBaseUrl", LuminaTranslators.LLM_DEFAULT_BASE_URL)));
        }
        if (provider.needsModel()) {
            items.add(UItem.asButton(ITEM_MODEL, LuminaLocale.getString(R.string.LuminaTranslateModel),
                    LuminaConfig.getString("translateModel", LuminaTranslators.LLM_DEFAULT_MODEL)));
        }
        if ("llm".equals(provider.id())) {
            items.add(UItem.asButton(ITEM_SYSTEM_PROMPT, LuminaLocale.getString(R.string.LuminaTranslateSystemPrompt)));
        }
        items.add(UItem.asButton(ITEM_TEST, LuminaLocale.getString(R.string.LuminaTranslateTest)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaTranslateProviderSecurityInfo)));
    }

    private String currentKey(LuminaTranslator provider) {
        return LuminaConfig.getString("translateKey_" + provider.id(), "");
    }

    private CharSequence maskKey(String key) {
        if (key == null || key.length() == 0) {
            return LuminaLocale.getString(R.string.LuminaTranslateApiKeyNotSet);
        }
        final int keep = Math.min(4, key.length());
        final StringBuilder sb = new StringBuilder();
        final int dots = Math.min(6, Math.max(0, key.length() - keep));
        for (int i = 0; i < dots; i++) {
            sb.append('•');
        }
        sb.append(key.substring(key.length() - keep));
        return sb.toString();
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ITEM_TRANSLATE_BEFORE_SEND:
                LuminaConfig.toggleTranslateBeforeSend();
                update();
                break;
            case ITEM_SEND_LANG:
                showLanguagePicker(KEY_SEND_LANG, LuminaLocale.getString(R.string.LuminaTranslateSendLang),
                        LuminaLocale.getString(R.string.LuminaTranslateSendLangAuto), "auto");
                break;
            case ITEM_TRANSLATE_BEFORE_SEND_CONFIRM:
                LuminaConfig.toggleTranslateBeforeSendConfirm();
                update();
                break;
            case ITEM_DUAL_LANGUAGE:
                LuminaConfig.putBoolean(LuminaConfig.KEY_DUAL_LANGUAGE_DISPLAY, !LuminaConfig.isDualLanguageDisplayEnabled());
                update();
                break;
            case ITEM_FOLD_ORIGINAL:
                LuminaConfig.putBoolean("foldOriginalLongMessages", !LuminaConfig.getBoolean("foldOriginalLongMessages", true));
                update();
                break;
            case ITEM_READ_LANG:
                showLanguagePicker(KEY_READ_LANG, LuminaLocale.getString(R.string.LuminaTranslateReadLang),
                        LuminaLocale.getString(R.string.LuminaTranslateReadLangFollow), "");
                break;
            case ITEM_SCOPE_PRIVATE:
                LuminaConfig.putBoolean(KEY_SCOPE_PRIVATE, !LuminaConfig.getBoolean(KEY_SCOPE_PRIVATE, true));
                update();
                break;
            case ITEM_SCOPE_GROUP:
                LuminaConfig.putBoolean(KEY_SCOPE_GROUP, !LuminaConfig.getBoolean(KEY_SCOPE_GROUP, true));
                update();
                break;
            case ITEM_MODE_ALL:
                LuminaConfig.putString(KEY_TR_MODE, TR_MODE_ALL);
                update();
                break;
            case ITEM_MODE_MANUAL:
                LuminaConfig.putString(KEY_TR_MODE, TR_MODE_MANUAL);
                update();
                break;
            case ITEM_PROVIDER:
                showProviderPicker();
                break;
            case ITEM_API_KEY:
                showTextInputDialog("translateKey_" + LuminaTranslators.current().id(),
                        LuminaLocale.getString(R.string.LuminaTranslateApiKey), "", true, false);
                break;
            case ITEM_BASE_URL:
                showTextInputDialog("translateBaseUrl", LuminaLocale.getString(R.string.LuminaTranslateBaseUrl),
                        LuminaTranslators.LLM_DEFAULT_BASE_URL, false, false);
                break;
            case ITEM_MODEL:
                showTextInputDialog("translateModel", LuminaLocale.getString(R.string.LuminaTranslateModel),
                        LuminaTranslators.LLM_DEFAULT_MODEL, false, false);
                break;
            case ITEM_SYSTEM_PROMPT:
                showTextInputDialog("translatePrompt", LuminaLocale.getString(R.string.LuminaTranslateSystemPrompt),
                        LuminaTranslators.LLM_DEFAULT_PROMPT, false, true);
                break;
            case ITEM_TEST:
                runProviderTest();
                break;
            case ITEM_OCR_TRANSLATE:
                LuminaConfig.toggleOcrTranslate();
                update();
                break;
            case ITEM_EXPLAIN:
                LuminaConfig.toggleExplainMessage();
                update();
                break;
            case ITEM_GROUP_SKIP:
                LuminaConfig.toggleGroupSkipMyLanguages();
                update();
                break;
            case ITEM_REVERSE_VOICE:
                LuminaConfig.reverseVoice = !LuminaConfig.reverseVoice;
                LuminaConfig.putBoolean("reverseVoice", LuminaConfig.reverseVoice);
                update();
                break;
        }
    }

    private void showProviderPicker() {
        if (getParentActivity() == null) {
            return;
        }
        final ArrayList<LuminaTranslator> providers = new ArrayList<>(LuminaTranslators.all());
        final CharSequence[] names = new CharSequence[providers.size()];
        for (int i = 0; i < providers.size(); ++i) {
            names[i] = providers.get(i).displayName();
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(LuminaLocale.getString(R.string.LuminaTranslateProvider));
        builder.setItems(names, (dialog, which) -> {
            if (which >= 0 && which < providers.size()) {
                LuminaConfig.putString("translateProvider", providers.get(which).id());
                update();
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    // Reusable single-field input dialog (API key / base URL / model / system prompt).
    private void showTextInputDialog(final String prefKey, final CharSequence title, final String def,
                                     final boolean password, final boolean multiline) {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        final EditTextBoldCursor edit = new EditTextBoldCursor(context);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        edit.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        edit.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        edit.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        edit.setCursorSize(AndroidUtilities.dp(20));
        edit.setCursorWidth(1.5f);
        edit.setBackgroundDrawable(null);
        int inputType = InputType.TYPE_CLASS_TEXT;
        if (password) {
            inputType |= InputType.TYPE_TEXT_VARIATION_PASSWORD;
            edit.setSingleLine(true);
        } else if (multiline) {
            inputType |= InputType.TYPE_TEXT_FLAG_MULTI_LINE;
            edit.setSingleLine(false);
            edit.setMaxLines(6);
        } else {
            edit.setSingleLine(true);
        }
        edit.setInputType(inputType);
        // LuminaGram: an API key is never put back into the field as text. The stored key only
        // shows as a masked hint ("••••••abcd"); typing replaces it, Save with an empty field
        // keeps it, and Delete clears it.
        final String storedSecret = password ? LuminaConfig.getString(prefKey, "") : null;
        if (password) {
            if (storedSecret != null && storedSecret.length() > 0) {
                edit.setHintText(maskKey(storedSecret));
            }
        } else {
            edit.setText(LuminaConfig.getString(prefKey, def));
            edit.setSelection(edit.length());
        }

        final FrameLayout container = new FrameLayout(context);
        container.addView(edit, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL, 24, 6, 24, 0));

        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(title);
        builder.setView(container);
        builder.setPositiveButton(LocaleController.getString(R.string.Save), (dialog, which) -> {
            final String value = edit.getText().toString().trim();
            if (!password || value.length() > 0) {
                LuminaConfig.putString(prefKey, value);
            }
            update();
            AndroidUtilities.hideKeyboard(edit);
        });
        if (password && storedSecret != null && storedSecret.length() > 0) {
            builder.setNeutralButton(LocaleController.getString(R.string.Delete), (dialog, which) -> {
                LuminaConfig.putString(prefKey, "");
                update();
                AndroidUtilities.hideKeyboard(edit);
            });
        }
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    // Translate a sample string through the current provider and report via a bulletin.
    private void runProviderTest() {
        if (getParentActivity() == null) {
            return;
        }
        final String sample = "Hello, world!";
        final String toLang = TranslateAlert2.getToLanguage();
        LuminaTranslators.current().translate(sample, toLang, new LuminaTranslator.Callback() {
            @Override
            public void onResult(String translated, String detectedSourceLang) {
                if (getParentActivity() == null) {
                    return;
                }
                BulletinFactory.of(LuminaTranslateActivity.this)
                        .createSimpleBulletin(R.raw.done, translated).show();
            }

            @Override
            public void onError(boolean rateLimited, String message) {
                if (getParentActivity() == null) {
                    return;
                }
                final String prefix = LuminaLocale.getString(R.string.LuminaTranslateTestFailed);
                final CharSequence text = (message == null || message.length() == 0) ? prefix : (prefix + ": " + message);
                BulletinFactory.of(LuminaTranslateActivity.this).createErrorBulletin(text).show();
            }
        });
    }

    /**
     * Reusable language picker. Presents a special first option (its label + the code to store)
     * followed by the full translatable-language list. The chosen code is written to {@code prefKey}.
     */
    private void showLanguagePicker(final String prefKey, final CharSequence dialogTitle,
                                    final CharSequence firstOptionLabel, final String firstOptionValue) {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        final ArrayList<TranslateController.Language> languages = TranslateController.getLanguages();
        final CharSequence[] names = new CharSequence[languages.size() + 1];
        names[0] = firstOptionLabel;
        for (int i = 0; i < languages.size(); ++i) {
            names[i + 1] = languages.get(i).displayName;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(dialogTitle);
        builder.setItems(names, (dialog, which) -> {
            if (which == 0) {
                LuminaConfig.putString(prefKey, firstOptionValue);
            } else if (which - 1 >= 0 && which - 1 < languages.size()) {
                LuminaConfig.putString(prefKey, languages.get(which - 1).code);
            }
            update();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void update() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }
}
