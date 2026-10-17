package org.telegram.messenger;

import static org.telegram.messenger.AndroidUtilities.isInAirplaneMode;
import static org.telegram.ui.PremiumPreviewFragment.applyNewSpan;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.view.ViewGroup;

import androidx.core.content.FileProvider;

import org.json.JSONObject;
import org.telegram.messenger.web.BuildConfig;
import org.telegram.messenger.web.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TL_smsjobs;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.UpdateAppAlertDialog;
import org.telegram.ui.Components.UpdateLayout;
import org.telegram.ui.IUpdateLayout;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.SMSStatsActivity;
import org.telegram.ui.SMSSubscribeSheet;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class ApplicationLoaderImpl extends ApplicationLoader {

    // --- LuminaGram in-app self-updater (R2 version.json -> download -> install) ---
    private static final String LUMINA_UPDATE_MANIFEST_URL = "https://pub-d6a54d2e5f5947e2b0b23fb8e27ce0a5.r2.dev/version.json";
    // LuminaGram: beta-channel manifest, a sibling of version.json on the same R2 bucket.
    // Polled instead of the stable manifest when LuminaConfig.betaChannel is ON, so opted-in
    // users auto-update to test builds while stable users only ever see promoted builds.
    private static final String LUMINA_UPDATE_MANIFEST_URL_BETA = "https://pub-d6a54d2e5f5947e2b0b23fb8e27ce0a5.r2.dev/version-beta.json";

    // LuminaGram: an APK that finished downloading but has not been installed yet is
    // remembered in the app-private "luminagram" prefs, so the offer to install survives
    // the process being killed while the user is away.
    private static final String KEY_READY_APK = "luminaUpdateReadyApk";
    private static final String KEY_READY_VERSION = "luminaUpdateReadyVersion";
    private static final String KEY_READY_VERSION_CODE = "luminaUpdateReadyVersionCode";
    private static final String KEY_READY_PROMPTED = "luminaUpdateReadyPrompted";

    private volatile BetaUpdate pendingUpdate;
    private volatile String pendingUpdateUrl;
    private volatile File downloadedApk;
    private volatile boolean downloadingUpdate;
    private volatile boolean cancelUpdateDownload;
    private volatile float updateDownloadProgress;
    // LuminaGram: absolute byte counters for the running download. They back both the
    // progress dialog and the progress notification, and they are what lets a dialog that
    // was dismissed (or a whole new "Check for updates" tap) re-attach to the download
    // already in flight instead of starting a second one.
    private volatile long updateDownloadedBytes;
    private volatile long updateTotalBytes;
    private long lastUpdateCheckTime;
    // LuminaGram: true when the last manifest fetch threw (offline, DNS, timeout, bad JSON).
    // Without this the caller cannot tell "nothing newer" from "we never reached the server",
    // and reports the misleading "your version is latest" while the device is offline.
    private volatile boolean lastUpdateCheckFailed;
    // UI thread only.
    private AlertDialog updateProgressDialog;
    private boolean updateInstallPromptScheduled;

    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }

    @Override
    protected boolean isStandalone() {
        return true;
    }

    @Override
    protected void startAppCenterInternal(Activity context) {

    }

    @Override
    protected void checkForUpdatesInternal() {

    }

    protected void appCenterLogInternal(Throwable e) {

    }

    protected void logDualCameraInternal(boolean success, boolean vendor) {

    }

    @Override
    public boolean checkApkInstallPermissions(final Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ApplicationLoader.applicationContext.getPackageManager().canRequestPackageInstalls()) {
            AlertsCreator.createApkRestrictedDialog(context, null).show();
            return false;
        }
        return true;
    }

    @Override
    public boolean openApkInstall(Activity activity, TLRPC.Document document) {
        boolean exists = false;
        try {
            String fileName = FileLoader.getAttachFileName(document);
            File f = FileLoader.getInstance(UserConfig.selectedAccount).getPathToAttach(document, true);
            if (exists = f.exists()) {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                if (Build.VERSION.SDK_INT >= 24) {
                    intent.setDataAndType(FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", f), "application/vnd.android.package-archive");
                } else {
                    intent.setDataAndType(Uri.fromFile(f), "application/vnd.android.package-archive");
                }
                try {
                    activity.startActivityForResult(intent, 500);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return exists;
    }

    @Override
    public boolean showUpdateAppPopup(Context context, TLRPC.TL_help_appUpdate update, int account) {
        try {
            (new UpdateAppAlertDialog(context, update, account)).show();
        } catch (Exception e) {
            FileLog.e(e);
        }
        return true;
    }

    @Override
    public IUpdateLayout takeUpdateLayout(Activity activity, ViewGroup sideMenuContainer) {
        return new UpdateLayout(activity, sideMenuContainer);
    }

    @Override
    public TLRPC.Update parseTLUpdate(int constructor) {
        if (constructor == TL_smsjobs.TL_updateSmsJob.constructor) {
            return new TL_smsjobs.TL_updateSmsJob();
        }
        return super.parseTLUpdate(constructor);
    }

    @Override
    public void processUpdate(int currentAccount, TLRPC.Update update) {
        if (update instanceof TL_smsjobs.TL_updateSmsJob) {
            SMSJobController.getInstance(currentAccount).processJobUpdate(((TL_smsjobs.TL_updateSmsJob) update).job_id);
        }
    }

    @Override
    public void addItemOptions(ItemOptions itemOptions) {
        if (SMSJobController.getInstance(UserConfig.selectedAccount).isAvailable()) {
            CharSequence text = LocaleController.getString(R.string.SmsJobsMenu);
            if (MessagesController.getGlobalMainSettings().getBoolean("newppsms", true)) {
                text = applyNewSpan(text.toString());
            }
            boolean withError = isInAirplaneMode(LaunchActivity.instance) || SMSJobController.getInstance(UserConfig.selectedAccount).hasError();
            itemOptions.add(R.drawable.left_sms, text, withError, () -> {
                MessagesController.getGlobalMainSettings().edit().putBoolean("newppsms", false).apply();
                SMSJobController controller = (SMSJobController) SMSJobController.getInstance(UserConfig.selectedAccount);
                final int state = controller.currentState;
                if (state == SMSJobController.STATE_NONE) {
                    SMSSubscribeSheet.show(LaunchActivity.instance, SMSJobController.getInstance(UserConfig.selectedAccount).isEligible, null, null);
                    return;
                } else if (state == SMSJobController.STATE_NO_SIM) {
                    controller.checkSelectedSIMCard();
                    if (controller.getSelectedSIM() == null) {
                        new AlertDialog.Builder(LaunchActivity.instance)
                                .setTitle(LocaleController.getString(R.string.SmsNoSimTitle))
                                .setMessage(AndroidUtilities.replaceTags(LocaleController.getString(R.string.SmsNoSimMessage)))
                                .setPositiveButton(LocaleController.getString(org.telegram.messenger.R.string.OK), null)
                                .show();
                        return;
                    }
                } else if (state == SMSJobController.STATE_ASKING_PERMISSION) {
                    SMSSubscribeSheet.requestSMSPermissions(LaunchActivity.instance, () -> {
                        controller.checkSelectedSIMCard();
                        if (controller.getSelectedSIM() == null) {
                            controller.setState(SMSJobController.STATE_NO_SIM);
                            new AlertDialog.Builder(LaunchActivity.instance)
                                    .setTitle(LocaleController.getString(R.string.SmsNoSimTitle))
                                    .setMessage(AndroidUtilities.replaceTags(LocaleController.getString(R.string.SmsNoSimMessage)))
                                    .setPositiveButton(LocaleController.getString(org.telegram.messenger.R.string.OK), null)
                                    .show();
                            return;
                        }
                        ConnectionsManager.getInstance(UserConfig.selectedAccount).sendRequest(new TL_smsjobs.TL_smsjobs_join(), (res, err) -> AndroidUtilities.runOnUIThread(() -> {
                            if (err != null) {
                                BulletinFactory.showError(err);
                            } else if (res instanceof TLRPC.TL_boolFalse) {
                                BulletinFactory.global().createErrorBulletin(LocaleController.getString(org.telegram.messenger.R.string.UnknownError)).show();
                            } else {
                                controller.setState(SMSJobController.STATE_JOINED);
                                controller.loadStatus(true);
                                SMSSubscribeSheet.showSubscribed(LaunchActivity.instance, null);
                                BaseFragment lastFragment = LaunchActivity.getLastFragment();
                                if (lastFragment != null) {
                                    lastFragment.presentFragment(new SMSStatsActivity());
                                }
                            }
                        }));
                    }, false);
                    return;
                }
                BaseFragment lastFragment = LaunchActivity.getLastFragment();
                if (lastFragment != null) {
                    lastFragment.presentFragment(new SMSStatsActivity());
                }
            });
        }
    }

    @Override
    public boolean checkRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        if (SMSSubscribeSheet.checkSMSPermissions(requestCode, permissions, grantResults)) {
            return true;
        }
        return super.checkRequestPermissionResult(requestCode, permissions, grantResults);
    }

    @Override
    public boolean onSuggestionFill(String suggestion, CharSequence[] output, boolean[] closeable) {
        if (suggestion == null && SMSJobController.getInstance(UserConfig.selectedAccount).hasError()) {
            output[0] = new SpannableStringBuilder().append(SMSStatsActivity.error(17)).append("  ").append(LocaleController.getString(R.string.SmsJobsErrorHintTitle));
            output[1] = LocaleController.getString(R.string.SmsJobsErrorHintMessage);
            closeable[0] = false;
            return true;
        }
        if ("PREMIUM_SMSJOBS".equals(suggestion) && SMSJobController.getInstance(UserConfig.selectedAccount).currentState != SMSJobController.STATE_JOINED) {
            output[0] = LocaleController.getString(R.string.SmsJobsPremiumHintTitle);
            output[1] = LocaleController.getString(R.string.SmsJobsPremiumHintMessage);
            closeable[0] = true;
            return true;
        }
        return super.onSuggestionFill(suggestion, output, closeable);
    }

    @Override
    public boolean onSuggestionClick(String suggestion) {
        if (suggestion == null) {
            BaseFragment lastFragment = LaunchActivity.getLastFragment();
            if (lastFragment != null) {
                SMSJobController.getInstance(UserConfig.selectedAccount).seenError();
                SMSStatsActivity fragment = new SMSStatsActivity();
                lastFragment.presentFragment(fragment);
                AndroidUtilities.runOnUIThread(() -> {
                    fragment.showDialog(new SMSStatsActivity.SMSHistorySheet(fragment));
                }, 800);
            }
            return true;
        } else if ("PREMIUM_SMSJOBS".equals(suggestion)) {
            SMSJobController controller = SMSJobController.getInstance(UserConfig.selectedAccount);
            if (controller.isEligible != null) {
                SMSSubscribeSheet.show(LaunchActivity.instance, controller.isEligible, null, null);
            } else {
                controller.checkIsEligible(true, isEligible -> {
                    if (isEligible == null) {
                        MessagesController.getInstance(UserConfig.selectedAccount).removeSuggestion(0, "PREMIUM_SMSJOBS");
                        return;
                    }
                    SMSSubscribeSheet.show(LaunchActivity.instance, isEligible, null, null);
                });
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean consumePush(int account, JSONObject json) {
        try {
            if (json != null && "SMSJOB".equals(json.getString("loc_key"))) {
                JSONObject custom = json.getJSONObject("custom");
                String job_id = custom.getString("job_id");
                SMSJobController.getInstance(UserConfig.selectedAccount).processJobUpdate(job_id);
                return true;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    @Override
    public boolean onPause() {
        super.onPause();
        return SMSJobsNotification.check();
    }

    @Override
    public void onResume() {
        super.onResume();
        SMSJobsNotification.check();
        // LuminaGram: an update that finished downloading while we were in the background
        // could not open the installer (Android 10+ blocks background activity starts), so
        // the offer was parked. Now that we are visible again, make good on it.
        try {
            checkPendingUpdateInstall();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public BaseFragment openSettings(int n) {
        if (n == 13) {
            if (SMSJobController.getInstance(UserConfig.selectedAccount).getState() == SMSJobController.STATE_JOINED) {
                return new SMSStatsActivity();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------
    // LuminaGram custom self-updater. Activates the scaffold already wired into
    // LaunchActivity.checkAppUpdate() by returning true from isCustomUpdate(). Reads a
    // JSON manifest on Cloudflare R2, compares versions, downloads a newer APK over
    // HTTPS and hands it to the system installer via AndroidUtilities.openForView().
    // ---------------------------------------------------------------------------------

    @Override
    public boolean isCustomUpdate() {
        return true;
    }

    @Override
    public BetaUpdate getUpdate() {
        return pendingUpdate;
    }

    @Override
    public boolean didLastUpdateCheckFail() {
        return lastUpdateCheckFailed;
    }

    @Override
    public void checkUpdate(boolean force, Runnable whenDone) {
        // LuminaGram: a download is already in flight. Never fire a second manifest fetch
        // (and, further down, never a second download) - just put the progress dialog back
        // on screen so a repeat "Check for updates" re-attaches to what is already running
        // instead of looking like it did nothing.
        if (downloadingUpdate) {
            lastUpdateCheckFailed = false;
            AndroidUtilities.runOnUIThread(() -> {
                if (force) {
                    showUpdateProgressDialog(null);
                }
                if (whenDone != null) {
                    whenDone.run();
                }
            });
            return;
        }
        // Rate-limit background (non-forced) checks so the resume hook doesn't hit the
        // network every time; manual "Check for updates" passes force=true.
        if (!force && System.currentTimeMillis() - lastUpdateCheckTime < 60L * 60L * 1000L) {
            if (whenDone != null) {
                AndroidUtilities.runOnUIThread(whenDone);
            }
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            BetaUpdate parsed = null;
            String parsedUrl = null;
            boolean failed = false;
            HttpURLConnection connection = null;
            try {
                String manifestUrl = LuminaConfig.betaChannel ? LUMINA_UPDATE_MANIFEST_URL_BETA : LUMINA_UPDATE_MANIFEST_URL;
                URL url = new URL(manifestUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestMethod("GET");
                connection.setDoInput(true);
                int statusCode = connection.getResponseCode();
                InputStream stream = (statusCode >= 200 && statusCode < 300) ? connection.getInputStream() : connection.getErrorStream();
                StringBuilder body = new StringBuilder();
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
                reader.close();

                JSONObject json = new JSONObject(body.toString());
                String versionName = json.getString("versionName");
                int versionCode = json.getInt("versionCode");
                String notes = json.optString("notes", null);
                String apkUrl = json.getString("url");

                // Compare against the installed build. The standalone flavor overrides the
                // versionCode as base*10+abi (see TMessagesProj_AppStandalone/build.gradle),
                // and the manifest carries the base code, so divide the installed code by 10.
                PackageInfo packageInfo = ApplicationLoader.applicationContext.getPackageManager()
                        .getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
                int installedBaseCode = packageInfo.versionCode / 10;
                String installedName = packageInfo.versionName;

                boolean newer = versionCode > installedBaseCode
                        || (versionCode == installedBaseCode
                            && SharedConfig.versionBiggerOrEqual(versionName, installedName)
                            && !versionName.equals(installedName));
                if (newer) {
                    parsed = new BetaUpdate(versionName, versionCode, notes);
                    parsedUrl = apkUrl;
                }
            } catch (Exception e) {
                // LuminaGram: the fetch failed rather than found nothing - remember that so the
                // UI can offer "check your connection" instead of claiming we are up to date.
                failed = true;
                FileLog.e(e);
            } finally {
                if (connection != null) {
                    try {
                        connection.disconnect();
                    } catch (Exception ignore) {
                    }
                }
            }
            lastUpdateCheckTime = System.currentTimeMillis();
            lastUpdateCheckFailed = failed;
            final BetaUpdate result = parsed;
            final String resultUrl = parsedUrl;
            AndroidUtilities.runOnUIThread(() -> {
                if (result != null) {
                    pendingUpdate = result;
                    pendingUpdateUrl = resultUrl;
                }
                if (whenDone != null) {
                    whenDone.run();
                }
                // LuminaGram: the APK for this update is already sitting on disk, finished but
                // not installed (the user backed out of the installer, or the download landed
                // while the app was away). A manual check must surface it again - LaunchActivity
                // only pops the "Update available" alert for an update it has not seen before.
                if (force) {
                    final File ready = readyUpdateFile();
                    if (ready != null) {
                        promptInstallDownloadedUpdate(null, ready, true);
                    }
                }
            });
        });
    }

    @Override
    public void downloadUpdate() {
        startDownload();
    }

    @Override
    public void cancelDownloadingUpdate() {
        cancelUpdateDownload = true;
        try {
            LuminaUpdateService.stop();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public boolean isDownloadingUpdate() {
        return downloadingUpdate;
    }

    @Override
    public float getDownloadingUpdateProgress() {
        return updateDownloadProgress;
    }

    @Override
    public File getDownloadedUpdateFile() {
        return downloadedApk;
    }

    /**
     * Starts the APK download, or does nothing at all when one is already running - the
     * single guarantee that no tap anywhere can ever open a second transfer. Progress and
     * completion are dispatched through {@link #onUpdateDownloadProgress} /
     * {@link #onUpdateDownloadFinished} rather than through per-call callbacks, so the
     * dialog can come and go (or be replaced by the notification) mid-download.
     */
    private void startDownload() {
        if (downloadingUpdate) {
            return;
        }
        final String downloadUrl = pendingUpdateUrl;
        if (downloadUrl == null || pendingUpdate == null) {
            AndroidUtilities.runOnUIThread(() -> onUpdateDownloadFinished(null));
            return;
        }
        if (downloadedApk != null && downloadedApk.exists()) {
            final File cached = downloadedApk;
            AndroidUtilities.runOnUIThread(() -> onUpdateDownloadFinished(cached));
            return;
        }
        downloadingUpdate = true;
        cancelUpdateDownload = false;
        updateDownloadProgress = 0f;
        updateDownloadedBytes = 0;
        updateTotalBytes = 0;
        // Started from a user tap, i.e. while we are foreground, which is exactly when a
        // foreground service may legally be started on API 31+. Failure is non-fatal.
        try {
            LuminaUpdateService.start();
        } catch (Throwable e) {
            FileLog.e(e);
        }
        Utilities.globalQueue.postRunnable(() -> {
            File result = null;
            HttpURLConnection connection = null;
            try {
                File out = new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), "LuminaGram-update.apk");
                connection = (HttpURLConnection) new URL(downloadUrl).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(true);
                connection.connect();
                final long totalBytes = connection.getContentLength();
                InputStream in = connection.getInputStream();
                FileOutputStream fos = new FileOutputStream(out);
                byte[] buffer = new byte[16 * 1024];
                long downloaded = 0;
                long lastProgressPost = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (cancelUpdateDownload) {
                        break;
                    }
                    fos.write(buffer, 0, read);
                    downloaded += read;
                    if (totalBytes > 0) {
                        updateDownloadProgress = Math.min(1f, (float) downloaded / (float) totalBytes);
                    }
                    // LuminaGram: the APK is ~140 MB, so with a 16 KB buffer this loop runs about
                    // 9000 times; posting to the UI thread on every chunk would flood the looper.
                    // Throttle to ~10 updates/s, plus one final post when the last byte lands.
                    final long now = System.currentTimeMillis();
                    if (now - lastProgressPost >= 100 || (totalBytes > 0 && downloaded >= totalBytes)) {
                        lastProgressPost = now;
                        final long downloadedSoFar = downloaded;
                        AndroidUtilities.runOnUIThread(() -> onUpdateDownloadProgress(downloadedSoFar, totalBytes));
                    }
                }
                fos.flush();
                fos.close();
                in.close();
                if (!cancelUpdateDownload) {
                    result = out;
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (connection != null) {
                    try {
                        connection.disconnect();
                    } catch (Exception ignore) {
                    }
                }
                downloadingUpdate = false;
            }
            final File finalResult = result;
            downloadedApk = finalResult;
            AndroidUtilities.runOnUIThread(() -> onUpdateDownloadFinished(finalResult));
        });
    }

    /** UI thread. Feeds both the (optional) dialog and the (optional) notification. */
    private void onUpdateDownloadProgress(long downloaded, long total) {
        updateDownloadedBytes = downloaded;
        updateTotalBytes = total;
        applyProgressToDialog();
        try {
            LuminaUpdateService.setProgress(downloaded, total);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** UI thread. */
    private void onUpdateDownloadFinished(File file) {
        dismissUpdateProgressDialog();
        try {
            LuminaUpdateService.stop();
        } catch (Throwable e) {
            FileLog.e(e);
        }
        if (file != null && file.exists()) {
            rememberReadyUpdate(file);
            // The user should never have to go hunting for the update they just waited for.
            // In the foreground we open the installer right away; in the background Android
            // 10+ forbids that, so we post a tappable notification AND leave the offer parked
            // for onResume() - whichever the user reaches first wins.
            boolean prompted = false;
            if (!ApplicationLoader.mainInterfacePaused && !SharedConfig.appLocked) {
                prompted = promptInstallDownloadedUpdate(null, file, true);
            }
            if (!prompted) {
                try {
                    LuminaUpdateService.showReadyNotification(file, LuminaConfig.getString(KEY_READY_VERSION, ""));
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            return;
        }
        if (cancelUpdateDownload) {
            return;
        }
        try {
            BaseFragment fragment = LaunchActivity.getLastFragment();
            if (fragment != null) {
                BulletinFactory.of(fragment).createErrorBulletin(LuminaLocale.getString(R.string.LuminaUpdateFailed)).show();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public boolean showCustomUpdateAppPopup(Context context, BetaUpdate update, int account) {
        if (context == null || update == null) {
            return false;
        }
        AndroidUtilities.runOnUIThread(() -> {
            try {
                final Activity activity = (context instanceof Activity) ? (Activity) context : LaunchActivity.instance;
                // Already downloading? Show the live progress instead of offering to start
                // the very same download over again.
                if (downloadingUpdate) {
                    showUpdateProgressDialog(activity);
                    return;
                }
                // Already downloaded? Offer to install it, not to fetch it a second time.
                final File ready = readyUpdateFile();
                if (ready != null) {
                    promptInstallDownloadedUpdate(activity, ready, true);
                    return;
                }
                AlertDialog.Builder builder = new AlertDialog.Builder(context);
                builder.setTitle(LuminaLocale.getString(R.string.LuminaUpdateAvailable));
                StringBuilder message = new StringBuilder();
                message.append(LuminaLocale.getString(R.string.LuminaUpdateNewVersion)).append(' ').append(update.version);
                if (update.changelog != null && update.changelog.length() > 0) {
                    message.append("\n\n").append(update.changelog);
                }
                builder.setMessage(message.toString());
                builder.setPositiveButton(LuminaLocale.getString(R.string.LuminaUpdateNow), (dialog, which) -> {
                    if (activity == null) {
                        return;
                    }
                    if (!checkApkInstallPermissions(activity)) {
                        return;
                    }
                    beginDownloadAndInstall(activity);
                });
                builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
                builder.show();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
        return true;
    }

    private void beginDownloadAndInstall(final Activity activity) {
        File ready = readyUpdateFile();
        if (ready == null && downloadedApk != null && downloadedApk.exists()) {
            ready = downloadedApk;
        }
        if (ready != null) {
            promptInstallDownloadedUpdate(activity, ready, true);
            return;
        }
        showUpdateProgressDialog(activity);
        // No-op when a download is already running: re-attaching above is all that happens.
        startDownload();
    }

    // ------------------------------------------------------------------ progress dialog

    /**
     * Shows - or re-attaches to - the download progress dialog.
     *
     * <p>This used to be a plain cancellable loading dialog whose cancel listener aborted
     * the transfer, so a stray tap anywhere outside it (or the back key) silently threw
     * away a 140 MB download. It is now sealed: only the two explicit buttons can close
     * it, and only one of them stops the download.
     */
    private void showUpdateProgressDialog(final Activity activity) {
        final Activity host = (activity != null) ? activity : LaunchActivity.instance;
        if (host == null || host.isFinishing() || host.isDestroyed()) {
            return;
        }
        if (updateProgressDialog != null && updateProgressDialog.isShowing()) {
            applyProgressToDialog();
            return;
        }
        dismissUpdateProgressDialog();
        try {
            final AlertDialog dialog = new AlertDialog(host, AlertDialog.ALERT_TYPE_LOADING);
            dialog.setCanCancel(false);
            dialog.setCancelable(false);            // back key no longer aborts the download
            dialog.setCanceledOnTouchOutside(false); // neither does a tap outside the box
            dialog.setDismissDialogByButtons(false); // we decide what each button closes
            dialog.setMessage(LuminaLocale.getString(R.string.LuminaUpdateDownloading));
            // Keep downloading, just get out of the way: the transfer lives on globalQueue
            // and is held up by LuminaUpdateService, so dismissing changes nothing about it.
            dialog.setNeutralButton(LuminaLocale.getString(R.string.LuminaUpdateDownloadInBackground), (d, which) -> dismissUpdateProgressDialog());
            // The only thing in the whole UI that actually stops the download.
            dialog.setNegativeButton(LuminaLocale.getString(R.string.LuminaUpdateCancelDownload), (d, which) -> {
                cancelDownloadingUpdate();
                dismissUpdateProgressDialog();
            });
            dialog.setOnDismissListener(d -> {
                if (updateProgressDialog == d) {
                    updateProgressDialog = null;
                }
            });
            updateProgressDialog = dialog;
            dialog.show();
            applyProgressToDialog();
        } catch (Exception e) {
            FileLog.e(e);
            updateProgressDialog = null;
        }
    }

    private void applyProgressToDialog() {
        final AlertDialog dialog = updateProgressDialog;
        if (dialog == null) {
            return;
        }
        try {
            final long downloaded = updateDownloadedBytes;
            final long total = updateTotalBytes;
            // LuminaGram: a ~140 MB APK on a slow link sits on the same percentage for a
            // long time, so show the absolute MB counts as well. When Content-Length is
            // missing we cannot compute either, so keep the plain "Downloading update..."
            if (total > 0) {
                dialog.setProgress((int) Math.max(0L, Math.min(100L, downloaded * 100L / total)));
                dialog.setMessage(String.format(java.util.Locale.US,
                        LuminaLocale.getString(R.string.LuminaUpdateDownloadingProgress),
                        formatUpdateSizeMb(downloaded), formatUpdateSizeMb(total)));
            } else {
                dialog.setMessage(LuminaLocale.getString(R.string.LuminaUpdateDownloading));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void dismissUpdateProgressDialog() {
        final AlertDialog dialog = updateProgressDialog;
        updateProgressDialog = null;
        if (dialog == null) {
            return;
        }
        try {
            dialog.dismiss();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // ------------------------------------------------------- finished-but-not-installed

    /** Persists "there is a finished APK waiting" so it survives the process being killed. */
    private void rememberReadyUpdate(File apk) {
        try {
            final BetaUpdate update = pendingUpdate;
            LuminaConfig.putString(KEY_READY_APK, apk.getAbsolutePath());
            LuminaConfig.putString(KEY_READY_VERSION, update != null && update.version != null ? update.version : "");
            LuminaConfig.putInt(KEY_READY_VERSION_CODE, update != null ? update.versionCode : 0);
            LuminaConfig.putBoolean(KEY_READY_PROMPTED, false);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void forgetReadyUpdate(boolean deleteFile) {
        try {
            if (deleteFile) {
                final String path = LuminaConfig.getString(KEY_READY_APK, "");
                if (path != null && path.length() > 0) {
                    final File stale = new File(path);
                    if (stale.exists()) {
                        stale.delete();
                    }
                }
                downloadedApk = null;
            }
            LuminaConfig.putString(KEY_READY_APK, "");
            LuminaConfig.putString(KEY_READY_VERSION, "");
            LuminaConfig.putInt(KEY_READY_VERSION_CODE, 0);
            LuminaConfig.putBoolean(KEY_READY_PROMPTED, false);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /**
     * The downloaded-but-not-yet-installed APK, or null. Self-healing: once the running
     * build is at or past the version that was downloaded, the record and the ~140 MB file
     * are dropped, so a stale APK can never be offered forever.
     */
    private File readyUpdateFile() {
        try {
            final String path = LuminaConfig.getString(KEY_READY_APK, "");
            if (path == null || path.length() == 0) {
                return null;
            }
            final File apk = new File(path);
            if (!apk.exists() || apk.length() <= 0) {
                forgetReadyUpdate(false);
                return null;
            }
            final int readyCode = LuminaConfig.getInt(KEY_READY_VERSION_CODE, 0);
            final String readyName = LuminaConfig.getString(KEY_READY_VERSION, "");
            final PackageInfo packageInfo = ApplicationLoader.applicationContext.getPackageManager()
                    .getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
            // Standalone flavor encodes the versionCode as base*10+abi, same as checkUpdate().
            final int installedBaseCode = packageInfo.versionCode / 10;
            final boolean alreadyInstalled = readyCode > 0
                    && installedBaseCode >= readyCode
                    && (readyName == null || readyName.length() == 0 || packageInfo.versionName == null
                        || SharedConfig.versionBiggerOrEqual(packageInfo.versionName, readyName));
            if (alreadyInstalled) {
                forgetReadyUpdate(true);
                return null;
            }
            return apk;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * Opens the system installer for a finished APK.
     *
     * @param userInitiated true when the user asked for it (tapped Update / Check for
     *                      updates, or the download just finished under their eyes). When
     *                      false this is the automatic on-return prompt, which fires at
     *                      most once per downloaded file - if the user backs out of the
     *                      installer we must not shove it at them on every single resume.
     * @return true when the installer was actually launched.
     */
    private boolean promptInstallDownloadedUpdate(Activity activity, File apk, boolean userInitiated) {
        if (apk == null || !apk.exists()) {
            forgetReadyUpdate(false);
            return false;
        }
        final Activity host = (activity != null) ? activity : LaunchActivity.instance;
        if (host == null || host.isFinishing() || host.isDestroyed()) {
            return false; // stays parked; the next onResume() tries again
        }
        if (!userInitiated && LuminaConfig.getBoolean(KEY_READY_PROMPTED, false)) {
            return false;
        }
        if (!checkApkInstallPermissions(host)) {
            // The "allow installs from this source" dialog is up instead; report false so
            // the caller still leaves a notification as the way back. Deliberately before
            // the flag is written: this is the path a first-time installer always takes,
            // and marking it "prompted" here stranded the downloaded APK for good - every
            // later resume returned early and the manual check had its own gate.
            return false;
        }
        try {
            installApk(host, apk);
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
        // Only now: the installer is actually up, so this offer really was delivered.
        LuminaConfig.putBoolean(KEY_READY_PROMPTED, true);
        try {
            LuminaUpdateService.cancelReadyNotification();
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return true;
    }

    /** Called from onResume(). Re-offers an update that finished while we were away. */
    private void checkPendingUpdateInstall() {
        final File ready = readyUpdateFile();
        if (ready == null) {
            return;
        }
        // Re-seed the in-memory handle so that after a process restart tapping "Update"
        // installs the file we already have instead of downloading it all over again.
        if (downloadedApk == null) {
            downloadedApk = ready;
        }
        if (LuminaConfig.getBoolean(KEY_READY_PROMPTED, false) || updateInstallPromptScheduled) {
            return;
        }
        updateInstallPromptScheduled = true;
        scheduleUpdateInstallPrompt(ready, 20);
    }

    /**
     * Waits for the app to actually be usable before popping the installer: right after
     * onResume() the passcode / disguise lock screen may still be up, and throwing the
     * package installer over it would be both jarring and useless. Retries about once a
     * second for ~20s, then gives up quietly leaving the offer parked for a later resume.
     */
    private void scheduleUpdateInstallPrompt(final File apk, final int attemptsLeft) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                if (LuminaConfig.getBoolean(KEY_READY_PROMPTED, false)) {
                    updateInstallPromptScheduled = false;
                    return;
                }
                if (ApplicationLoader.mainInterfacePaused) {
                    updateInstallPromptScheduled = false; // gone again; next resume retries
                    return;
                }
                if (SharedConfig.appLocked || LaunchActivity.instance == null) {
                    if (attemptsLeft > 0) {
                        scheduleUpdateInstallPrompt(apk, attemptsLeft - 1);
                    } else {
                        updateInstallPromptScheduled = false;
                    }
                    return;
                }
                updateInstallPromptScheduled = false;
                promptInstallDownloadedUpdate(null, apk, false);
            } catch (Exception e) {
                updateInstallPromptScheduled = false;
                FileLog.e(e);
            }
        }, 1000);
    }

    // LuminaGram: bytes -> "138.0". Always Locale.US so the decimal separator is stable no
    // matter which in-app language supplies the surrounding template (the number is injected
    // through the template's %1$s / %2$s placeholders, which carry the localized unit).
    private static String formatUpdateSizeMb(long bytes) {
        return String.format(java.util.Locale.US, "%.1f", bytes / (1024f * 1024f));
    }

    private void installApk(Activity activity, File apk) {
        AndroidUtilities.openForView(apk, "LuminaGram.apk", "application/vnd.android.package-archive", activity, null, false);
    }
}
