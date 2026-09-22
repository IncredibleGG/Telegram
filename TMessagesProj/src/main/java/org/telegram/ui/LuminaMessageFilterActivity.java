package org.telegram.ui;

import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LuminaConfig;
import org.telegram.messenger.LuminaLocale;
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
 * LuminaGram Batch 4 #22 — message filter keyword list.
 * Mirrors {@link LuminaGlossaryActivity} (UItem-based settings fragment). Lets the user
 * manage the keywords whose messages are hidden from the visible message list. The list is
 * a purely LOCAL, display-only choice — matched messages are simply not drawn; nothing is
 * ever deleted on the server and nothing is sent to Telegram. The whole list is persisted as
 * a newline-joined string in the app-private "luminagram" prefs under
 * {@link LuminaConfig#KEY_MESSAGE_FILTER}, and consumed by
 * {@link LuminaConfig#filterMessages} when {@link ChatActivity} builds its message list.
 * Default (empty list) = no filtering, i.e. exactly stock behavior.
 */
public class LuminaMessageFilterActivity extends BaseFragment {

    private static final int ITEM_ADD = 1;
    private static final int ITEM_KEYWORD_BASE = 100;

    private UniversalRecyclerView listView;
    private final ArrayList<String> keywords = new ArrayList<>();

    // ---- Persistence (newline-joined string shared with LuminaConfig) ----

    private void loadKeywords() {
        keywords.clear();
        final String raw = LuminaConfig.getString(LuminaConfig.KEY_MESSAGE_FILTER, "");
        if (raw != null && raw.length() > 0) {
            final String[] lines = raw.split("\n");
            for (String line : lines) {
                if (line == null) {
                    continue;
                }
                final String t = line.trim();
                if (t.length() > 0) {
                    keywords.add(t);
                }
            }
        }
    }

    private void saveKeywords() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keywords.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(keywords.get(i));
        }
        LuminaConfig.putString(LuminaConfig.KEY_MESSAGE_FILTER, sb.toString());
        // Refresh the cached list used by the per-message filter check.
        LuminaConfig.reloadMessageFilter();
    }

    // Case-insensitive membership test so the same keyword is not stored twice.
    private boolean containsKeyword(String candidate, String ignore) {
        for (int i = 0; i < keywords.size(); i++) {
            final String t = keywords.get(i);
            if (t.equalsIgnoreCase(candidate) && !t.equals(ignore)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onFragmentCreate() {
        loadKeywords();
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LuminaLocale.getString(R.string.LuminaMessageFilterTitle));
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

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaMessageFilterHeader)));
        items.add(UItem.asButton(ITEM_ADD, R.drawable.msg_add, LuminaLocale.getString(R.string.LuminaMessageFilterAdd)).accent());
        for (int i = 0; i < keywords.size(); i++) {
            final String t = keywords.get(i);
            final UItem u = UItem.asButton(ITEM_KEYWORD_BASE + i, preview(t));
            u.object = t;
            items.add(u);
        }
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaMessageFilterInfo)));
    }

    // Single-line preview for a list row.
    private static CharSequence preview(String t) {
        if (t == null) {
            return "";
        }
        String s = t.replace('\n', ' ').trim();
        if (s.length() > 40) {
            s = s.substring(0, 40) + "…";
        }
        return s;
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ITEM_ADD) {
            showEditDialog(null);
        } else if (item.object instanceof String) {
            showEditDialog((String) item.object);
        }
    }

    // Add (existing == null) or edit an existing keyword. Editing offers a Delete action.
    private void showEditDialog(final String existing) {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }

        final LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);

        final EditTextBoldCursor field = makeField(context,
                LuminaLocale.getString(R.string.LuminaMessageFilterKeyword), existing == null ? "" : existing);
        container.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                24, 6, 24, 0));

        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LuminaLocale.getString(existing == null ? R.string.LuminaMessageFilterAdd : R.string.LuminaMessageFilterEdit));
        builder.setView(container);
        builder.setPositiveButton(LocaleController.getString(R.string.Save), (dialog, which) -> {
            final String kw = field.getText().toString().trim();
            if (kw.length() == 0) {
                return;
            }
            if (existing == null) {
                if (!containsKeyword(kw, null)) {
                    keywords.add(kw);
                }
            } else {
                final int idx = keywords.indexOf(existing);
                if (idx >= 0) {
                    if (containsKeyword(kw, existing)) {
                        keywords.remove(idx);   // merge into the existing duplicate
                    } else {
                        keywords.set(idx, kw);
                    }
                }
            }
            saveKeywords();
            update();
            AndroidUtilities.hideKeyboard(field);
        });
        if (existing != null) {
            builder.setNeutralButton(LocaleController.getString(R.string.Delete), (dialog, which) -> {
                keywords.remove(existing);
                saveKeywords();
                update();
            });
        }
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    private EditTextBoldCursor makeField(Context context, CharSequence hint, String value) {
        final EditTextBoldCursor edit = new EditTextBoldCursor(context);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        edit.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        edit.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        edit.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        edit.setCursorSize(AndroidUtilities.dp(20));
        edit.setCursorWidth(1.5f);
        edit.setBackgroundDrawable(null);
        edit.setInputType(InputType.TYPE_CLASS_TEXT);
        edit.setSingleLine(true);
        edit.setHint(hint);
        edit.setText(value == null ? "" : value);
        edit.setSelection(edit.length());
        return edit;
    }

    private void update() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }
}
