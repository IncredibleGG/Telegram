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
import org.telegram.messenger.LuminaVoiceToText;
import org.telegram.messenger.LuminaVoskModelManager;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

/**
 * LuminaGram — voice-to-text (speech transcription) settings.
 * Mirrors {@link LuminaTranslateActivity}'s idiom: a UniversalRecyclerView of UItem rows,
 * a provider/engine picker, a masked bring-your-own-key row and reusable text-input dialog.
 *
 * Engines:
 *   - vosk  : offline, free — needs a one-time voice-model download, chosen and managed in
 *             {@link LuminaVoskModelsActivity}.
 *   - whisper / google : cloud, more accurate, use the user's own API key + quota.
 * The chat-side transcription trigger and the engines/decoders themselves live elsewhere;
 * this screen only owns the settings and their config keys.
 */
public class LuminaVoiceToTextActivity extends BaseFragment {

    // Config keys owned by this screen (consumed by the transcription pipeline elsewhere).
    private static final String KEY_ENABLED = "voiceToTextEnabled";   // default true
    private static final String KEY_ENGINE = "sttEngine";             // "vosk" | "whisper" | "google", default "vosk"
    private static final String KEY_CLOUD_BASE_URL = "sttCloudBaseUrl"; // whisper only
    private static final String KEY_MODEL = "sttModel";               // whisper only
    // The chosen offline model language lives in LuminaVoskModelManager.CONFIG_KEY_LANG.
    // Carry-on-into-translation switches, consumed by LuminaVoiceToText.
    private static final String KEY_AUTO_TRANSLATE = "sttAutoTranslate"; // default false
    private static final String KEY_AUTO_PIPELINE = "sttAutoPipeline";   // default false (costs quota)

    private static final String ENGINE_VOSK = "vosk";
    private static final String ENGINE_WHISPER = "whisper";
    private static final String ENGINE_GOOGLE = "google";

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    private static final String DEFAULT_MODEL = "whisper-1";

    private static final int ITEM_ENABLE = 1;
    private static final int ITEM_ENGINE = 2;
    private static final int ITEM_API_KEY = 3;
    private static final int ITEM_BASE_URL = 4;
    private static final int ITEM_MODEL = 5;
    private static final int ITEM_MANAGE_MODELS = 6;
    private static final int ITEM_AUTO_TRANSLATE = 7;
    private static final int ITEM_AUTO_PIPELINE = 8;

    private UniversalRecyclerView listView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LuminaLocale.getString(R.string.LuminaVoiceToTextTitle));
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

        // Arm the receive-side auto pipeline if the user left it on (no-op while it is off).
        LuminaVoiceToText.ensureAutoPipelineInstalled();

        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        return fragmentView;
    }

    private String currentEngine() {
        return LuminaConfig.getString(KEY_ENGINE, ENGINE_VOSK);
    }

    // The label shown for a given engine id (also used as the engine-picker options).
    private CharSequence engineLabel(String engine) {
        if (ENGINE_WHISPER.equals(engine)) {
            return LuminaLocale.getString(R.string.LuminaSttEngineWhisper);
        }
        if (ENGINE_GOOGLE.equals(engine)) {
            return LuminaLocale.getString(R.string.LuminaSttEngineGoogle);
        }
        return LuminaLocale.getString(R.string.LuminaSttEngineVosk);
    }

    // Short form for the settings row. TextCell hard-ellipsizes its value at 40% of the
    // screen width, which truncated the descriptive labels above in several languages, so
    // the row shows the bare engine name and the picker keeps the full description.
    private CharSequence engineLabelShort(String engine) {
        if (ENGINE_WHISPER.equals(engine)) {
            return LuminaLocale.getString(R.string.LuminaSttEngineWhisperShort);
        }
        if (ENGINE_GOOGLE.equals(engine)) {
            return LuminaLocale.getString(R.string.LuminaSttEngineGoogleShort);
        }
        return LuminaLocale.getString(R.string.LuminaSttEngineVoskShort);
    }

    // Value shown on the "manage voice models" row: the chosen offline language, or none yet.
    // Anything the user picked before the catalogue existed (a translate-only language such as
    // Zulu, for which Vosk has no model) no longer resolves and reads as "None".
    private CharSequence currentVoskLangName() {
        final String lang = LuminaVoskModelManager.selectedLang();
        final LuminaVoskModelManager.VoskModel model = LuminaVoskModelManager.modelFor(lang);
        if (model == null) {
            return LuminaLocale.getString(R.string.LuminaSttModelsNone);
        }
        return LuminaVoskModelsActivity.displayName(model);
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        // ---- Enable ----
        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaVoiceToTextTitle)));
        items.add(UItem.asSwitch(ITEM_ENABLE, LuminaLocale.getString(R.string.LuminaSttEnable))
                .setChecked(LuminaConfig.getBoolean(KEY_ENABLED, true)));
        items.add(UItem.asShadow(null));

        // ---- Engine ----
        final String engine = currentEngine();
        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaSttEngine)));
        items.add(UItem.asButton(ITEM_ENGINE, LuminaLocale.getString(R.string.LuminaSttEngine), engineLabelShort(engine)));

        if (ENGINE_VOSK.equals(engine)) {
            // Offline engine: pick + download a voice model.
            items.add(UItem.asButton(ITEM_MANAGE_MODELS, LuminaLocale.getString(R.string.LuminaSttManageModels), currentVoskLangName()));
        } else {
            // Cloud engine (whisper / google): bring your own key.
            items.add(UItem.asButton(ITEM_API_KEY, LuminaLocale.getString(R.string.LuminaSttKey), maskKey(currentKey())));
            if (ENGINE_WHISPER.equals(engine)) {
                items.add(UItem.asButton(ITEM_BASE_URL, LuminaLocale.getString(R.string.LuminaSttBaseUrl),
                        LuminaConfig.getString(KEY_CLOUD_BASE_URL, DEFAULT_BASE_URL)));
                items.add(UItem.asButton(ITEM_MODEL, LuminaLocale.getString(R.string.LuminaSttModel),
                        LuminaConfig.getString(KEY_MODEL, DEFAULT_MODEL)));
            }
        }
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaSttInfo)));

        // ---- Carry on into translation ----
        // A transcript that stays in a language you cannot read is only half the feature, so
        // the pipeline continues into the ordinary translation provider. Both switches are
        // consumed by LuminaVoiceToText.
        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaTranslateHeader)));
        items.add(UItem.asSwitch(ITEM_AUTO_TRANSLATE, LuminaLocale.getString(R.string.LuminaSttAutoTranslate))
                .setChecked(LuminaConfig.getBoolean(KEY_AUTO_TRANSLATE, false)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaSttAutoTranslateInfo)));
        items.add(UItem.asSwitch(ITEM_AUTO_PIPELINE, LuminaLocale.getString(R.string.LuminaSttAutoPipeline))
                .setChecked(LuminaConfig.getBoolean(KEY_AUTO_PIPELINE, false)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaSttAutoPipelineInfo)));
    }

    // Per-engine key: "sttKey_whisper" / "sttKey_google" (mirrors translate's "translateKey_<id>").
    private String keyPref() {
        return "sttKey_" + currentEngine();
    }

    private String currentKey() {
        return LuminaConfig.getString(keyPref(), "");
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
            case ITEM_ENABLE:
                LuminaConfig.putBoolean(KEY_ENABLED, !LuminaConfig.getBoolean(KEY_ENABLED, true));
                update();
                break;
            case ITEM_ENGINE:
                showEnginePicker();
                break;
            case ITEM_API_KEY:
                showTextInputDialog(keyPref(), LuminaLocale.getString(R.string.LuminaSttKey), "", true, false);
                break;
            case ITEM_BASE_URL:
                showTextInputDialog(KEY_CLOUD_BASE_URL, LuminaLocale.getString(R.string.LuminaSttBaseUrl),
                        DEFAULT_BASE_URL, false, false);
                break;
            case ITEM_MODEL:
                showTextInputDialog(KEY_MODEL, LuminaLocale.getString(R.string.LuminaSttModel),
                        DEFAULT_MODEL, false, false);
                break;
            case ITEM_MANAGE_MODELS:
                presentFragment(new LuminaVoskModelsActivity());
                break;
            case ITEM_AUTO_TRANSLATE:
                LuminaConfig.putBoolean(KEY_AUTO_TRANSLATE, !LuminaConfig.getBoolean(KEY_AUTO_TRANSLATE, false));
                update();
                break;
            case ITEM_AUTO_PIPELINE:
                LuminaConfig.putBoolean(KEY_AUTO_PIPELINE, !LuminaConfig.getBoolean(KEY_AUTO_PIPELINE, false));
                // Register the incoming-message observer the moment it is switched on.
                LuminaVoiceToText.ensureAutoPipelineInstalled();
                update();
                break;
        }
    }

    private void showEnginePicker() {
        if (getParentActivity() == null) {
            return;
        }
        final String[] engines = new String[]{ENGINE_VOSK, ENGINE_WHISPER, ENGINE_GOOGLE};
        final CharSequence[] names = new CharSequence[engines.length];
        for (int i = 0; i < engines.length; ++i) {
            names[i] = engineLabel(engines[i]);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(LuminaLocale.getString(R.string.LuminaSttEngine));
        builder.setItems(names, (dialog, which) -> {
            if (which >= 0 && which < engines.length) {
                LuminaConfig.putString(KEY_ENGINE, engines[which]);
                update();
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    // Reusable single-field input dialog (API key / base URL / model). Copied from
    // LuminaTranslateActivity so the two screens share the same look and behaviour.
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

    @Override
    public void onResume() {
        super.onResume();
        // The models screen can change the selection or delete a model behind our back.
        update();
    }

    private void update() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }
}
