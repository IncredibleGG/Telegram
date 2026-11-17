package org.telegram.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LuminaConfig;
import org.telegram.messenger.LuminaDecoy;
import org.telegram.messenger.LuminaDisguiseController;
import org.telegram.messenger.LuminaDuress;
import org.telegram.messenger.LuminaLocale;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
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
 * LuminaDisguiseActivity — "App disguise" settings.
 *
 * Mirrors {@link LuminaAppearanceActivity} / {@link LuminaSecurityActivity}'s
 * UItem / UniversalRecyclerView pattern. Three sections:
 *
 *  0. Duress code (top of the page; formerly the fake-crash code): an enable switch, the mode
 *     (log out the current account / log out all accounts / only close the app) and the code
 *     itself, all stored through {@link LuminaDuress}. Choosing a logout mode always goes
 *     through a warning dialog that has to be confirmed; the switch itself only ever turns on
 *     the non-destructive "close the app" mode. The code must differ from the Telegram
 *     passcode and from the vault's secret code (checked in both code dialogs). PasscodeView,
 *     the vault password door and both decoys read it.
 *
 *  1. App disguise: a master switch ({@code disguiseEnabled}) plus selectable preset
 *     rows (Default / Calculator / Notes / Clock). Picking a preset stores
 *     {@code disguisePreset} and swaps the launcher alias via
 *     {@link LuminaDisguiseController#applyDisguise(Context, String)}. While the switch
 *     is off the launcher is forced back to the real LuminaGram (Default) icon.
 *
 *  2. Disguise vault: a unified "vault" that hides LuminaGram behind a harmless-looking
 *     decoy app. A master switch ({@code vaultEnabled}) reveals the vault mode
 *     ({@code vaultMode}: {@code passwordDoor} / {@code decoyApp}), the decoy skin
 *     ({@code decoySkin}: {@code notepad} / {@code calculator}) and a secret code
 *     ({@code decoyUnlockCode}). The decoy/unlock behaviour itself is implemented
 *     separately (see {@code LuminaDecoy}); this page only renders the controls and
 *     persists the config keys.
 *
 *     Mode and skin are picked with in-list radio rows rather than an AlertDialog list:
 *     {@code AlertDialog.Builder.setItems} renders single-line entries with no selection
 *     marker, so the two-line mode labels were being ellipsized ("Password door Opening
 *     asks for a co…") and the active choice was invisible. The radio rows reuse the very
 *     same UItem pattern as the disguise presets right above them.
 *
 *     Launcher coupling: while the vault owns the launcher icon (see
 *     {@link #syncVaultLauncherIcon(boolean)}) the home-screen icon and name follow the
 *     decoy skin, so the camouflage is complete without a second manual step.
 *
 *     Legacy migration: earlier builds only had a single {@code decoyLockEnabled} toggle.
 *     When {@code vaultEnabled} has never been written we fall back to that flag for the
 *     initial switch state and, if it was on, present the vault as decoyApp + calculator.
 *     Nothing is overwritten until the user actually changes a vault setting, at which
 *     point the legacy state is snapshotted and {@code vaultEnabled} is set to true.
 *
 * All values are persisted through {@link LuminaConfig}'s generic accessors.
 */
public class LuminaDisguiseActivity extends BaseFragment {

    private static final int ID_DISGUISE_ENABLED = 1;
    private static final int ID_PRESET_DEFAULT = 2;
    private static final int ID_PRESET_CALCULATOR = 3;
    private static final int ID_PRESET_NOTES = 4;
    private static final int ID_PRESET_CLOCK = 5;
    private static final int ID_VAULT_ENABLED = 6;
    private static final int ID_VAULT_MODE_PASSWORD_DOOR = 7;
    private static final int ID_VAULT_MODE_DECOY_APP = 8;
    private static final int ID_VAULT_SKIN_NOTEPAD = 9;
    private static final int ID_VAULT_SKIN_CALCULATOR = 10;
    private static final int ID_VAULT_SET_CODE = 11;
    private static final int ID_DURESS_ENABLED = 20;
    private static final int ID_DURESS_MODE_LOGOUT_CURRENT = 21;
    private static final int ID_DURESS_MODE_EXIT = 22;
    private static final int ID_DURESS_SET_CODE = 23;
    private static final int ID_DURESS_MODE_LOGOUT_ALL = 24;

    private static final String KEY_DISGUISE_ENABLED = LuminaDisguiseController.KEY_ENABLED;
    private static final String KEY_DISGUISE_PRESET = LuminaDisguiseController.KEY_PRESET;
    // Legacy single-toggle key from earlier builds (read-only fallback for migration).
    private static final String KEY_DECOY_ENABLED = "decoyLockEnabled";
    // Unified disguise-vault keys.
    private static final String KEY_VAULT_ENABLED = "vaultEnabled";
    private static final String KEY_VAULT_MODE = "vaultMode";
    private static final String KEY_DECOY_SKIN = "decoySkin";
    private static final String KEY_DECOY_CODE = "decoyUnlockCode";
    /**
     * True while the launcher icon is being driven BY the vault (icon follows the decoy
     * skin) rather than hand-picked in the "App disguise" section above. See
     * {@link #syncVaultLauncherIcon(boolean)} for the full coupling rules.
     */
    private static final String KEY_VAULT_OWNS_ICON = "vaultOwnsDisguiseIcon";

    // vaultMode values.
    private static final String VAULT_MODE_PASSWORD_DOOR = "passwordDoor";
    private static final String VAULT_MODE_DECOY_APP = "decoyApp";
    // decoySkin values.
    private static final String DECOY_SKIN_NOTEPAD = "notepad";
    private static final String DECOY_SKIN_CALCULATOR = "calculator";

    private UniversalRecyclerView listView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LuminaLocale.getString(R.string.LuminaDisguiseTitle));
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
        fillDuressItems(items);

        boolean enabled = LuminaConfig.getBoolean(KEY_DISGUISE_ENABLED, false);

        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaDisguiseSectionHeader)));
        items.add(UItem.asSwitch(ID_DISGUISE_ENABLED, LuminaLocale.getString(R.string.LuminaDisguiseEnable))
                .setChecked(enabled));
        if (enabled) {
            String preset = LuminaConfig.getString(KEY_DISGUISE_PRESET, LuminaDisguiseController.PRESET_DEFAULT);
            items.add(UItem.asRadio(ID_PRESET_DEFAULT, LuminaLocale.getString(R.string.LuminaDisguisePresetDefault))
                    .setChecked(LuminaDisguiseController.PRESET_DEFAULT.equals(preset)));
            items.add(UItem.asRadio(ID_PRESET_CALCULATOR, LuminaLocale.getString(R.string.LuminaDisguisePresetCalculator))
                    .setChecked(LuminaDisguiseController.PRESET_CALCULATOR.equals(preset)));
            items.add(UItem.asRadio(ID_PRESET_NOTES, LuminaLocale.getString(R.string.LuminaDisguisePresetNotes))
                    .setChecked(LuminaDisguiseController.PRESET_NOTES.equals(preset)));
            items.add(UItem.asRadio(ID_PRESET_CLOCK, LuminaLocale.getString(R.string.LuminaDisguisePresetClock))
                    .setChecked(LuminaDisguiseController.PRESET_CLOCK.equals(preset)));
        }
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaDisguiseInfo)));

        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaVaultHeader)));
        boolean vaultEnabled = vaultEnabled();
        items.add(UItem.asSwitch(ID_VAULT_ENABLED, LuminaLocale.getString(R.string.LuminaVaultEnable))
                .setChecked(vaultEnabled));
        if (vaultEnabled) {
            // Mode: asRadio2 (RadioButtonCell) gives a title line PLUS a wrapped explanation
            // line and a radio marking the active choice — what the old AlertDialog.setItems
            // list could not do (single-line rows, no selection state).
            String mode = currentVaultMode();
            boolean decoyAppMode = VAULT_MODE_DECOY_APP.equals(mode);
            items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaVaultMode)));
            items.add(UItem.asRadio2(ID_VAULT_MODE_PASSWORD_DOOR,
                    LuminaLocale.getString(R.string.LuminaVaultModePasswordDoor),
                    LuminaLocale.getString(R.string.LuminaVaultModePasswordDoorInfo))
                    .setChecked(!decoyAppMode));
            items.add(UItem.asRadio2(ID_VAULT_MODE_DECOY_APP,
                    LuminaLocale.getString(R.string.LuminaVaultModeDecoyApp),
                    LuminaLocale.getString(R.string.LuminaVaultModeDecoyAppInfo))
                    .setChecked(decoyAppMode));

            // Skin: plain single-line radios, identical to the disguise preset rows above.
            boolean calculatorSkin = DECOY_SKIN_CALCULATOR.equals(currentDecoySkin());
            items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaVaultSkin)));
            items.add(UItem.asRadio(ID_VAULT_SKIN_NOTEPAD,
                    LuminaLocale.getString(R.string.LuminaVaultSkinNotepad))
                    .setChecked(!calculatorSkin));
            items.add(UItem.asRadio(ID_VAULT_SKIN_CALCULATOR,
                    LuminaLocale.getString(R.string.LuminaVaultSkinCalculator))
                    .setChecked(calculatorSkin));

            items.add(UItem.asButton(ID_VAULT_SET_CODE,
                    LuminaLocale.getString(R.string.LuminaVaultSecretCode), decoyCodeValueText()));
        }
        items.add(UItem.asShadow(vaultInfoText(vaultEnabled)));
    }

    /**
     * Duress code, first section of the page. The mode radios and the code row only show while
     * the switch is on (same pattern as the vault below). Mode rows use asRadio2 so each choice
     * carries its own explanation; for the two logout modes that explanation IS the warning, so
     * it stays visible next to the choice. While a logout mode is active the footer adds the
     * general warning that this device's secret chats are lost.
     */
    private void fillDuressItems(ArrayList<UItem> items) {
        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaDuressHeader)));
        final String mode = LuminaDuress.getMode();
        final boolean enabled = !LuminaDuress.MODE_OFF.equals(mode);
        items.add(UItem.asSwitch(ID_DURESS_ENABLED, LuminaLocale.getString(R.string.LuminaDuressEnable))
                .setChecked(enabled));
        if (enabled) {
            items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaDuressModeHeader)));
            items.add(UItem.asRadio2(ID_DURESS_MODE_LOGOUT_CURRENT,
                    LuminaLocale.getString(R.string.LuminaDuressModeLogoutCurrent),
                    LuminaLocale.getString(R.string.LuminaDuressModeLogoutCurrentInfo))
                    .setChecked(LuminaDuress.MODE_LOGOUT_CURRENT.equals(mode)));
            items.add(UItem.asRadio2(ID_DURESS_MODE_LOGOUT_ALL,
                    LuminaLocale.getString(R.string.LuminaDuressModeLogoutAll),
                    LuminaLocale.getString(R.string.LuminaDuressModeLogoutAllInfo))
                    .setChecked(LuminaDuress.MODE_LOGOUT_ALL.equals(mode)));
            items.add(UItem.asRadio2(ID_DURESS_MODE_EXIT,
                    LuminaLocale.getString(R.string.LuminaDuressModeExit),
                    LuminaLocale.getString(R.string.LuminaDuressModeExitInfo))
                    .setChecked(LuminaDuress.MODE_EXIT.equals(mode)));
            items.add(UItem.asButton(ID_DURESS_SET_CODE,
                    LuminaLocale.getString(R.string.LuminaDuressSetCode), duressCodeValueText()));
        }
        CharSequence info = LuminaLocale.getString(R.string.LuminaDuressInfo);
        if (LuminaDuress.isLogoutMode(mode)) {
            info = info + "\n\n" + LuminaLocale.getString(R.string.LuminaDuressLogoutWarning);
        }
        // The passcode or the vault's secret code may have been set to the duress code after it
        // was saved; the duress code then does nothing. Say so while the feature is on.
        final int clash = enabled ? LuminaDuress.clashOf(LuminaDuress.getCode()) : LuminaDuress.CLASH_NONE;
        if (clash != LuminaDuress.CLASH_NONE) {
            info = info + "\n\n" + LuminaLocale.getString(R.string.LuminaDuressCodeDialogTitle) + ": "
                    + LuminaLocale.getString(clashMessage(clash));
        }
        items.add(UItem.asShadow(info));
    }

    private static int clashMessage(int clash) {
        return clash == LuminaDuress.CLASH_PASSCODE
                ? R.string.LuminaDuressCodeSameAsPasscode
                : R.string.LuminaDuressCodeSameAsVault;
    }

    /**
     * Before arming a mode: if the stored duress code is the passcode or the vault's secret
     * code (set to it later, or a code kept from the fake-crash era), open the code dialog with
     * that reason instead; {@code then} runs once a valid code is saved. Returns true when it
     * did so, false when there is no clash and the caller can go ahead.
     */
    private boolean fixDuressCodeClash(Runnable then) {
        final int clash = LuminaDuress.clashOf(LuminaDuress.getCode());
        if (clash == LuminaDuress.CLASH_NONE) {
            return false;
        }
        showDuressCodeDialog(clashMessage(clash), then);
        return true;
    }

    private CharSequence duressCodeValueText() {
        boolean set = LuminaDuress.getCode().length() > 0;
        return LuminaLocale.getString(set
                ? R.string.LuminaDuressCodeSet
                : R.string.LuminaDuressCodeNotSet);
    }

    /**
     * Initial checked state of the vault switch. Reads {@code vaultEnabled}, falling back
     * to the legacy {@code decoyLockEnabled} flag so existing decoy-lock users still show ON.
     */
    private boolean vaultEnabled() {
        return LuminaConfig.getBoolean(KEY_VAULT_ENABLED,
                LuminaConfig.getBoolean(KEY_DECOY_ENABLED, false));
    }

    /**
     * True while a legacy decoy-lock user has not yet been migrated: {@code vaultEnabled}
     * was never written but the old {@code decoyLockEnabled} flag is on. In that window the
     * vault is presented as decoyApp + calculator without persisting anything.
     */
    private boolean legacyDecoyPending() {
        return !LuminaConfig.contains(KEY_VAULT_ENABLED)
                && LuminaConfig.getBoolean(KEY_DECOY_ENABLED, false);
    }

    private String currentVaultMode() {
        return LuminaConfig.getString(KEY_VAULT_MODE,
                legacyDecoyPending() ? VAULT_MODE_DECOY_APP : VAULT_MODE_PASSWORD_DOOR);
    }

    private String currentDecoySkin() {
        return LuminaConfig.getString(KEY_DECOY_SKIN,
                legacyDecoyPending() ? DECOY_SKIN_CALCULATOR : DECOY_SKIN_NOTEPAD);
    }

    /**
     * Footer info: general (local, ToS-safe) blurb plus, while the vault is on, a note that
     * the launcher icon follows the decoy skin. The per-mode hint is no longer appended here
     * — each mode radio now carries its own explanation line.
     */
    private CharSequence vaultInfoText(boolean vaultEnabled) {
        CharSequence info = LuminaLocale.getString(R.string.LuminaVaultInfo);
        if (vaultEnabled) {
            info = info + "\n\n" + LuminaLocale.getString(R.string.LuminaVaultIconInfo);
        }
        return info;
    }

    private CharSequence decoyCodeValueText() {
        String code = LuminaConfig.getString(KEY_DECOY_CODE, "");
        boolean set = code != null && code.length() > 0;
        return LuminaLocale.getString(set
                ? R.string.LuminaDisguiseDecoyCodeSet
                : R.string.LuminaDisguiseDecoyCodeNotSet);
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_DURESS_ENABLED:
                // Turning the switch on only ever selects the non-destructive "close the app"
                // mode; a logout mode needs its own confirmed warning (confirmDuressLogoutMode).
                // A stored code that clashes with the passcode / vault code is fixed first.
                if (LuminaDuress.isEnabled()) {
                    LuminaDuress.setMode(LuminaDuress.MODE_OFF);
                    update();
                } else if (!fixDuressCodeClash(() -> {
                    if (!LuminaDuress.isEnabled()) {
                        LuminaDuress.setMode(LuminaDuress.MODE_EXIT);
                    }
                    update();
                })) {
                    LuminaDuress.setMode(LuminaDuress.MODE_EXIT);
                    update();
                }
                break;
            case ID_DURESS_MODE_LOGOUT_CURRENT:
                confirmDuressLogoutMode(LuminaDuress.MODE_LOGOUT_CURRENT);
                break;
            case ID_DURESS_MODE_LOGOUT_ALL:
                confirmDuressLogoutMode(LuminaDuress.MODE_LOGOUT_ALL);
                break;
            case ID_DURESS_MODE_EXIT:
                LuminaDuress.setMode(LuminaDuress.MODE_EXIT);
                update();
                break;
            case ID_DURESS_SET_CODE:
                showDuressCodeDialog();
                break;
            case ID_DISGUISE_ENABLED: {
                boolean enable = !LuminaConfig.getBoolean(KEY_DISGUISE_ENABLED, false);
                // The user is driving the launcher icon by hand from now on: hand ownership
                // back so the vault stops re-pointing the icon at its decoy skin.
                releaseVaultIconOwnership();
                LuminaConfig.putBoolean(KEY_DISGUISE_ENABLED, enable);
                if (enable) {
                    // Re-apply the stored preset (defaults to the real icon until one is picked).
                    LuminaDisguiseController.applyDisguise(getParentActivity(),
                            LuminaConfig.getString(KEY_DISGUISE_PRESET, LuminaDisguiseController.PRESET_DEFAULT));
                } else {
                    // Off -> force the real LuminaGram icon + name.
                    LuminaDisguiseController.applyDisguise(getParentActivity(), LuminaDisguiseController.PRESET_DEFAULT);
                }
                update();
                break;
            }
            case ID_PRESET_DEFAULT:
                selectPreset(LuminaDisguiseController.PRESET_DEFAULT);
                break;
            case ID_PRESET_CALCULATOR:
                selectPreset(LuminaDisguiseController.PRESET_CALCULATOR);
                break;
            case ID_PRESET_NOTES:
                selectPreset(LuminaDisguiseController.PRESET_NOTES);
                break;
            case ID_PRESET_CLOCK:
                selectPreset(LuminaDisguiseController.PRESET_CLOCK);
                break;
            case ID_VAULT_ENABLED: {
                boolean newValue = !vaultEnabled();
                // Snapshot legacy decoy state before the first write flips it out of range.
                migrateLegacyIfNeeded();
                LuminaConfig.putBoolean(KEY_VAULT_ENABLED, newValue);
                // Flipping the master switch is the moment the vault may adopt (or must give
                // back) the launcher icon.
                syncVaultLauncherIcon(true);
                update();
                break;
            }
            case ID_VAULT_MODE_PASSWORD_DOOR:
                selectVaultMode(VAULT_MODE_PASSWORD_DOOR);
                break;
            case ID_VAULT_MODE_DECOY_APP:
                selectVaultMode(VAULT_MODE_DECOY_APP);
                break;
            case ID_VAULT_SKIN_NOTEPAD:
                selectDecoySkin(DECOY_SKIN_NOTEPAD);
                break;
            case ID_VAULT_SKIN_CALCULATOR:
                selectDecoySkin(DECOY_SKIN_CALCULATOR);
                break;
            case ID_VAULT_SET_CODE:
                showUnlockCodeDialog();
                break;
        }
    }

    /**
     * Migrate a legacy decoy-lock user into the vault the first time they touch any vault
     * control. Snapshots the inferred mode/skin so later reads stay stable once
     * {@code vaultEnabled} is written. No-op once {@code vaultEnabled} exists.
     */
    private void migrateLegacyIfNeeded() {
        if (LuminaConfig.contains(KEY_VAULT_ENABLED)) {
            return;
        }
        if (LuminaConfig.getBoolean(KEY_DECOY_ENABLED, false)) {
            if (!LuminaConfig.contains(KEY_VAULT_MODE)) {
                LuminaConfig.putString(KEY_VAULT_MODE, VAULT_MODE_DECOY_APP);
            }
            if (!LuminaConfig.contains(KEY_DECOY_SKIN)) {
                LuminaConfig.putString(KEY_DECOY_SKIN, DECOY_SKIN_CALCULATOR);
            }
        }
    }

    private void selectPreset(String presetId) {
        // An explicit icon choice always wins over the vault's automatic coupling.
        releaseVaultIconOwnership();
        LuminaConfig.putString(KEY_DISGUISE_PRESET, presetId);
        // Preset rows are only shown while the master switch is on, so applying here is safe.
        LuminaDisguiseController.applyDisguise(getParentActivity(), presetId);
        update();
    }

    private void update() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
        // Every setting change on this page ends here: arm / disarm the vault's Recents privacy
        // right away instead of on the next resume (Android 13+; see LuminaDecoy).
        LuminaDecoy.applyRecentsPrivacy(getParentActivity());
    }

    /** Pick the vault mode. Any vault change migrates a legacy user and turns the vault on. */
    private void selectVaultMode(String mode) {
        migrateLegacyIfNeeded();
        LuminaConfig.putString(KEY_VAULT_MODE, mode);
        LuminaConfig.putBoolean(KEY_VAULT_ENABLED, true);
        update();
    }

    /** Pick the decoy skin; the launcher icon follows it whenever the vault owns the icon. */
    private void selectDecoySkin(String skin) {
        migrateLegacyIfNeeded();
        LuminaConfig.putString(KEY_DECOY_SKIN, skin);
        LuminaConfig.putBoolean(KEY_VAULT_ENABLED, true);
        syncVaultLauncherIcon(false);
        update();
    }

    /**
     * Stop coupling the launcher icon to the decoy skin, because the user just made an
     * explicit icon choice in the "App disguise" section. Writing {@code false} (rather
     * than removing the key) is deliberate: it records "already decided, hands off", which
     * {@link #syncVaultLauncherIcon(boolean)} distinguishes from "never decided".
     */
    private void releaseVaultIconOwnership() {
        try {
            LuminaConfig.putBoolean(KEY_VAULT_OWNS_ICON, false);
        } catch (Throwable ignore) {
        }
    }

    /**
     * Keep the launcher icon in step with the vault so the camouflage is complete: turning
     * the vault on also turns the home-screen icon + name into the decoy app's (notepad ->
     * Notes alias, calculator -> Calculator alias), and turning it off restores the real
     * LuminaGram icon.
     *
     * COUPLING RULES (deliberately conservative — an icon is a visible, user-owned thing):
     * <ul>
     *   <li>The vault never fights the "App disguise" section. It only adopts the icon while
     *       {@code disguiseEnabled} is off, i.e. when there is no hand-picked disguise to
     *       clobber.</li>
     *   <li>Adoption happens when the user switches the vault ON, or — for users who enabled
     *       the vault before this coupling existed — the first time they change the skin
     *       ({@code vaultOwnsDisguiseIcon} not present yet = "never decided"). Once the user
     *       has taken the icon back by hand (key present and false) the vault stays out until
     *       the vault switch is turned on again.</li>
     *   <li>While owned, the icon follows every skin change.</li>
     *   <li>Turning the vault off only restores the default icon if the vault owned it. A
     *       user's own disguise preset is left exactly as it was.</li>
     * </ul>
     *
     * The vault drives the icon THROUGH the disguise preset keys instead of calling
     * {@link LauncherIconController#setIcon} directly. That matters: on every cold start
     * {@link LauncherIconController#tryFixLauncherIconIfNeeded()} re-applies
     * {@code disguisePreset} when {@code disguiseEnabled} is on, so an alias set behind the
     * preset system's back would silently revert on the next launch — and the preset system
     * stays the single owner of the aliases (no two controllers fighting, which is what
     * produced the historical "app vanished from the launcher" brick).
     *
     * FAIL-SAFE: fully wrapped in try/catch, and every alias switch goes through
     * {@link LuminaDisguiseController#applyDisguise(Context, String)}, which enables the
     * target alias FIRST and only then disables the siblings — so at no point are zero
     * launcher components enabled, and a PackageManager failure leaves the launcher exactly
     * as it was.
     *
     * @param vaultSwitchToggled true when called right after the user flipped the vault
     *                           master switch (the one action that may re-adopt the icon).
     */
    private void syncVaultLauncherIcon(boolean vaultSwitchToggled) {
        try {
            final boolean owns = LuminaConfig.getBoolean(KEY_VAULT_OWNS_ICON, false);
            if (!vaultEnabled()) {
                if (!owns) {
                    // Never owned it -> the vault has no business touching the launcher.
                    return;
                }
                LuminaConfig.putBoolean(KEY_VAULT_OWNS_ICON, false);
                LuminaConfig.putBoolean(KEY_DISGUISE_ENABLED, false);
                LuminaConfig.putString(KEY_DISGUISE_PRESET, LuminaDisguiseController.PRESET_DEFAULT);
                LuminaDisguiseController.applyDisguise(getParentActivity(),
                        LuminaDisguiseController.PRESET_DEFAULT);
                return;
            }
            if (!owns) {
                if (LuminaConfig.getBoolean(KEY_DISGUISE_ENABLED, false)) {
                    // A hand-picked disguise is active: leave it alone.
                    return;
                }
                if (!vaultSwitchToggled && LuminaConfig.contains(KEY_VAULT_OWNS_ICON)) {
                    // The user already took the icon back; only the master switch re-adopts.
                    return;
                }
            }
            final String preset = LuminaDisguiseController.presetForDecoySkin(currentDecoySkin());
            LuminaConfig.putBoolean(KEY_VAULT_OWNS_ICON, true);
            LuminaConfig.putBoolean(KEY_DISGUISE_ENABLED, true);
            LuminaConfig.putString(KEY_DISGUISE_PRESET, preset);
            LuminaDisguiseController.applyDisguise(getParentActivity(), preset);
        } catch (Throwable ignore) {
            // An icon that did not change is always better than a crash in settings.
        }
    }

    /**
     * Pick a logout mode only after the user confirmed its warning: the mode's own warning
     * (the same text shown under its radio) plus the general line that this device's secret
     * chats are lost. Cancel leaves the current mode untouched. Re-picking the active mode is
     * a no-op.
     */
    private void confirmDuressLogoutMode(String mode) {
        if (mode.equals(LuminaDuress.getMode()) || getParentActivity() == null) {
            return;
        }
        // Never arm a logout mode on a code that is the passcode or the vault's secret code:
        // fix the code first, then come back here for the warning.
        if (fixDuressCodeClash(() -> confirmDuressLogoutMode(mode))) {
            return;
        }
        final boolean all = LuminaDuress.MODE_LOGOUT_ALL.equals(mode);
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LuminaLocale.getString(all
                ? R.string.LuminaDuressModeLogoutAll
                : R.string.LuminaDuressModeLogoutCurrent));
        builder.setMessage(LuminaLocale.getString(all
                ? R.string.LuminaDuressModeLogoutAllInfo
                : R.string.LuminaDuressModeLogoutCurrentInfo)
                + "\n\n" + LuminaLocale.getString(R.string.LuminaDuressLogoutWarning));
        builder.setPositiveButton(LuminaLocale.getString(R.string.LuminaDuressConfirmEnable), (d, w) -> {
            LuminaDuress.setMode(mode);
            update();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        TextView button = (TextView) dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (button != null) {
            button.setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    /** Show a one-button error for a rejected code. */
    private void showCodeError(int titleRes, int messageRes) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder err = new AlertDialog.Builder(getParentActivity());
        err.setTitle(LuminaLocale.getString(titleRes));
        err.setMessage(LuminaLocale.getString(messageRes));
        err.setPositiveButton(LocaleController.getString(R.string.OK), null);
        showDialog(err.create());
    }

    /**
     * Prompt for the duress code. Stored in plaintext under {@code fakeCrashCode} via
     * {@link LuminaDuress}; this is a SEPARATE local code that must differ from both the
     * Telegram passcode and the vault's secret code. With a 4-digit PIN passcode the lock
     * screen only accepts 4 digits, so the dialog says so.
     */
    private void showDuressCodeDialog() {
        showDuressCodeDialog(0, null);
    }

    /**
     * @param reasonRes when non-zero, shown as the dialog's message: why the code has to be
     *                  changed before a mode can be armed (see {@link #fixDuressCodeClash}).
     * @param afterSave run once a valid code was saved (not on cancel or a rejected code).
     */
    private void showDuressCodeDialog(int reasonRes, Runnable afterSave) {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        final EditTextBoldCursor edit = new EditTextBoldCursor(context);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        edit.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        edit.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        edit.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        edit.setCursorSize(AndroidUtilities.dp(20));
        edit.setCursorWidth(1.5f);
        edit.setBackgroundDrawable(null);
        edit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        edit.setSingleLine(true);
        edit.setText(LuminaDuress.getCode());
        edit.setSelection(edit.length());

        final FrameLayout container = new FrameLayout(context);
        container.addView(edit, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL, 24, 6, 24, 0));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LuminaLocale.getString(R.string.LuminaDuressCodeDialogTitle));
        String message = reasonRes != 0 ? LuminaLocale.getString(reasonRes) : null;
        if (SharedConfig.passcodeHash.length() > 0 && SharedConfig.passcodeType == SharedConfig.PASSCODE_TYPE_PIN) {
            String pinHint = LuminaLocale.getString(R.string.LuminaDuressCodePinHint);
            message = message == null ? pinHint : message + "\n\n" + pinHint;
        }
        if (message != null) {
            builder.setMessage(message);
        }
        builder.setView(container);
        builder.setPositiveButton(LocaleController.getString(R.string.Save), (dialog, which) -> {
            final String entered = edit.getText().toString().trim();
            // The duress code must NOT equal the real Telegram passcode. PasscodeView lets the
            // real passcode win anyway, but then the duress code could never fire there; say so
            // instead of saving a code that silently does nothing. Nor the vault's secret code:
            // the vault checks its unlock code first, so the duress code would never fire on the
            // password door or the decoys.
            final int clash = LuminaDuress.clashOf(entered);
            if (clash != LuminaDuress.CLASH_NONE) {
                showCodeError(R.string.LuminaDuressCodeDialogTitle, clashMessage(clash));
                return;
            }
            LuminaDuress.setCode(entered);
            update();
            AndroidUtilities.hideKeyboard(edit);
            if (afterSave != null) {
                // After this dialog is gone: afterSave may open the next one.
                AndroidUtilities.runOnUIThread(afterSave);
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void showUnlockCodeDialog() {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        final EditTextBoldCursor edit = new EditTextBoldCursor(context);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        edit.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        edit.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        edit.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        edit.setCursorSize(AndroidUtilities.dp(20));
        edit.setCursorWidth(1.5f);
        edit.setBackgroundDrawable(null);
        edit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        edit.setSingleLine(true);
        edit.setText(LuminaConfig.getString(KEY_DECOY_CODE, ""));
        edit.setSelection(edit.length());

        final FrameLayout container = new FrameLayout(context);
        container.addView(edit, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL, 24, 6, 24, 0));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LuminaLocale.getString(R.string.LuminaVaultSecretCodeDialogTitle));
        builder.setView(container);
        builder.setPositiveButton(LocaleController.getString(R.string.Save), (dialog, which) -> {
            final String entered = edit.getText().toString().trim();
            // The secret code must differ from the duress code (see showDuressCodeDialog): the
            // vault would always unlock and the duress code could never fire there.
            if (!entered.isEmpty() && entered.equals(LuminaDuress.getCode())) {
                showCodeError(R.string.LuminaVaultSecretCodeDialogTitle, R.string.LuminaVaultCodeSameAsDuress);
                return;
            }
            // Any vault change migrates a legacy user and turns the vault on.
            migrateLegacyIfNeeded();
            LuminaConfig.putString(KEY_DECOY_CODE, entered);
            LuminaConfig.putBoolean(KEY_VAULT_ENABLED, true);
            update();
            AndroidUtilities.hideKeyboard(edit);
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }
}
