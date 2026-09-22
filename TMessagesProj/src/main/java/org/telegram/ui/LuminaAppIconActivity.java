package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LuminaLocale;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

/**
 * LuminaAppIconActivity (Batch 8 #24) — launcher-icon picker.
 *
 * Reuses the platform's existing alternate-icon plumbing ({@link LauncherIconController} +
 * the {@code <activity-alias>} entries already in the manifest). It only exposes the icons
 * that already exist: the default icon plus every non-disguise {@link LauncherIconController.LauncherIcon}.
 * It invents no new drawables — additional branded icon ART must be supplied by the user/designer
 * later; this screen simply wires a picker onto the icons that ship today.
 *
 * The stealth CALCULATOR/NOTES/CLOCK camouflage aliases are deliberately excluded (they belong to
 * {@link LuminaDisguiseActivity}, not a cosmetic picker). While a disguise is active it takes over
 * the launcher icon on the next cold start regardless of the choice made here.
 *
 * Default = the real DEFAULT icon, so a fresh install / untouched picker changes nothing.
 */
public class LuminaAppIconActivity extends BaseFragment {

    // Row id = ID_ICON_BASE + LauncherIcon.ordinal(), so the click handler can recover the icon.
    private static final int ID_ICON_BASE = 100;

    private UniversalRecyclerView listView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LuminaLocale.getString(R.string.LuminaAppIconTitle));
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
        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaAppIconHeader)));
        for (LauncherIconController.LauncherIcon icon : LauncherIconController.LauncherIcon.values()) {
            if (icon.isDisguise()) {
                continue; // stealth aliases are owned by LuminaDisguiseActivity, not a cosmetic picker
            }
            items.add(UItem.asRadio(ID_ICON_BASE + icon.ordinal(), LocaleController.getString(icon.title))
                    .setChecked(LauncherIconController.isEnabled(icon)));
        }
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaAppIconInfo)));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id < ID_ICON_BASE) {
            return;
        }
        int ordinal = item.id - ID_ICON_BASE;
        LauncherIconController.LauncherIcon[] all = LauncherIconController.LauncherIcon.values();
        if (ordinal < 0 || ordinal >= all.length) {
            return;
        }
        LauncherIconController.LauncherIcon icon = all[ordinal];
        if (icon.isDisguise() || LauncherIconController.isEnabled(icon)) {
            return; // never target a camouflage alias, and no-op if already selected
        }
        LauncherIconController.setIcon(icon);
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }
}
