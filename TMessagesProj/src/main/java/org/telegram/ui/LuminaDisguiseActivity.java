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
import org.telegram.messenger.LuminaDisguiseController;
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
 *  0. Fake-crash duress code (top of the page; moved here from LuminaSecurityActivity with the
 *     same {@link LuminaConfig} keys, so existing settings carry over): an enable switch, the
 *     mode ({@code fakeCrashMode}: show the fake "keeps stopping" alert, the default, or exit at
 *     once) and the code itself. PasscodeView reads all three.
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
    private static final int ID_FAKECRASH_ENABLED = 20;
    private static final int ID_FAKECRASH_MODE_DIALOG = 21;
    private static final int ID_FAKECRASH_MODE_EXIT = 22;
    private static final int ID_FAKECRASH_SET_CODE = 23;

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
        fillFakeCrashItems(items);

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
     * Fake-crash duress code, first section of the page. The mode radios and the code row only
     * show while the switch is on (same pattern as the vault below). Mode rows use asRadio2 so
     * each choice carries its own one-line explanation.
     */
    private void fillFakeCrashItems(ArrayList<UItem> items) {
        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaSecurityFakeCrashHeader)));
        boolean fakeCrashEnabled = LuminaConfig.getBoolean(LuminaConfig.KEY_FAKE_CRASH_ENABLED, false);
        items.add(UItem.asSwitch(ID_FAKECRASH_ENABLED, LuminaLocale.getString(R.string.LuminaSecurityFakeCrashEnable))
                .setChecked(fakeCrashEnabled));
        if (fakeCrashEnabled) {
            boolean exitMode = LuminaConfig.isFakeCrashExitMode();
            items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaFakeCrashModeHeader)));
            items.add(UItem.asRadio2(ID_FAKECRASH_MODE_DIALOG,
                    LuminaLocale.getString(R.string.LuminaFakeCrashModeDialog),
                    LuminaLocale.getString(R.string.LuminaFakeCrashModeDialogInfo))
                    .setChecked(!exitMode));
            items.add(UItem.asRadio2(ID_FAKECRASH_MODE_EXIT,
                    LuminaLocale.getString(R.string.LuminaFakeCrashModeExit),
                    LuminaLocale.getString(R.string.LuminaFakeCrashModeExitInfo))
                    .setChecked(exitMode));
            items.add(UItem.asButton(ID_FAKECRASH_SET_CODE,
                    LuminaLocale.getString(R.string.LuminaSecurityFakeCrashSetCode), fakeCrashCodeValueText()));
        }
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaSecurityFakeCrashInfo)));
    }

    private CharSequence fakeCrashCodeValueText() {
        String code = LuminaConfig.getString(LuminaConfig.KEY_FAKE_CRASH_CODE, "");
        boolean set = code != null && code.length() > 0;
        return LuminaLocale.getString(set
                ? R.string.LuminaSecurityFakeCrashCodeSet
                : R.string.LuminaSecurityFakeCrashCodeNotSet);
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
            case ID_FAKECRASH_ENABLED:
                LuminaConfig.putBoolean(LuminaConfig.KEY_FAKE_CRASH_ENABLED,
                        !LuminaConfig.getBoolean(LuminaConfig.KEY_FAKE_CRASH_ENABLED, false));
                update();
                break;
            case ID_FAKECRASH_MODE_DIALOG:
                LuminaConfig.putString(LuminaConfig.KEY_FAKE_CRASH_MODE, LuminaConfig.FAKE_CRASH_MODE_DIALOG);
                update();
                break;
            case ID_FAKECRASH_MODE_EXIT:
                LuminaConfig.putString(LuminaConfig.KEY_FAKE_CRASH_MODE, LuminaConfig.FAKE_CRASH_MODE_EXIT);
                update();
                break;
            case ID_FAKECRASH_SET_CODE:
                showFakeCrashCodeDialog();
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
     * Prompt for the fake-crash duress code (moved verbatim from LuminaSecurityActivity). Stored
     * in plaintext under {@code fakeCrashCode} via {@link LuminaConfig}; this is a SEPARATE local
     * code, never the Telegram passcode.
     */
    private void showFakeCrashCodeDialog() {
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
        edit.setText(LuminaConfig.getString(LuminaConfig.KEY_FAKE_CRASH_CODE, ""));
        edit.setSelection(edit.length());

        final FrameLayout container = new FrameLayout(context);
        container.addView(edit, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL, 24, 6, 24, 0));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LuminaLocale.getString(R.string.LuminaSecurityFakeCrashCodeDialogTitle));
        builder.setView(container);
        builder.setPositiveButton(LocaleController.getString(R.string.Save), (dialog, which) -> {
            final String entered = edit.getText().toString().trim();
            // Duress code must NOT equal the real Telegram passcode. PasscodeView runs the
            // fake-crash check BEFORE SharedConfig.checkPasscode, so a collision would fire the
            // fake crash on every correct unlock and lock the user out permanently. Reject it.
            if (!entered.isEmpty() && SharedConfig.checkPasscode(entered)) {
                if (getParentActivity() != null) {
                    AlertDialog.Builder err = new AlertDialog.Builder(getParentActivity());
                    err.setTitle(LuminaLocale.getString(R.string.LuminaSecurityFakeCrashCodeDialogTitle));
                    err.setMessage(LuminaLocale.getString(R.string.LuminaSecurityFakeCrashCodeSameAsPasscode));
                    err.setPositiveButton(LocaleController.getString(R.string.OK), null);
                    showDialog(err.create());
                }
                return;
            }
            LuminaConfig.putString(LuminaConfig.KEY_FAKE_CRASH_CODE, entered);
            update();
            AndroidUtilities.hideKeyboard(edit);
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
            // Any vault change migrates a legacy user and turns the vault on.
            migrateLegacyIfNeeded();
            LuminaConfig.putString(KEY_DECOY_CODE, edit.getText().toString().trim());
            LuminaConfig.putBoolean(KEY_VAULT_ENABLED, true);
            update();
            AndroidUtilities.hideKeyboard(edit);
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }
}
