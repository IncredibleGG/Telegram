package org.telegram.messenger;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.FileProvider;

import org.telegram.messenger.web.R;
import org.telegram.ui.LaunchActivity;

import java.io.File;

/**
 * LuminaGram: notification + process-priority host for the in-app self-updater.
 *
 * <p>The APK download itself still runs where it always did — on
 * {@code Utilities.globalQueue} inside {@code ApplicationLoaderImpl} — this service does
 * not own it and never touches it. It exists for two reasons only:
 *
 * <ol>
 *   <li>a foreground service keeps the process out of the low-memory killer's way, so a
 *       ~140 MB download survives the user leaving the app for other things;</li>
 *   <li>it owns the progress notification, which is the only way to see how the download
 *       is doing once the dialog has been sent to the background.</li>
 * </ol>
 *
 * <p>Every entry point is fail-safe: if the service cannot start (OEM restrictions, a
 * background-start throttle on API 31+, notifications denied on API 33+) nothing is
 * thrown at the caller and the download simply continues in the foreground exactly the
 * way it did before this class existed.
 *
 * <p>Follows the fork's existing convention for internal notifications — the same
 * {@code NotificationsController.OTHER_NOTIFICATIONS_CHANNEL} channel used by
 * {@code ImportingService} (id 5) and {@code SMSJobsNotification} (id 38); this class
 * takes ids 41/42. The manifest declares it {@code foregroundServiceType="dataSync"},
 * matching the two services already declared in the standalone manifest.
 */
public class LuminaUpdateService extends Service {

    // 5 = history import, 38 = sms jobs are already taken by this fork.
    public static final int PROGRESS_NOTIFICATION_ID = 41;
    public static final int READY_NOTIFICATION_ID = 42;

    private static final int REQUEST_OPEN_APP = 41;
    private static final int REQUEST_INSTALL = 42;

    private static volatile LuminaUpdateService instance;
    /** True between {@link #start()} and {@link #stop()}; guards the start/stop race. */
    private static volatile boolean active;
    private static volatile long downloadedBytes;
    private static volatile long totalBytes;

    private NotificationCompat.Builder builder;
    /** The shade drops updates posted faster than ~10/s, so throttle to ~1/s. */
    private long lastNotifyTime;
    private int lastNotifiedPercent = -1;

    // ------------------------------------------------------------------ static API

    /** Called on the UI thread when a download starts, i.e. while we are foreground. */
    public static void start() {
        active = true;
        if (instance != null) {
            update();
            return;
        }
        try {
            final Intent intent = new Intent(ApplicationLoader.applicationContext, LuminaUpdateService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ApplicationLoader.applicationContext.startForegroundService(intent);
            } else {
                ApplicationLoader.applicationContext.startService(intent);
            }
        } catch (Throwable e) {
            // Fail-safe: no service means no progress notification, but the download on
            // globalQueue is entirely unaffected and keeps running.
            FileLog.e(e);
        }
    }

    public static void setProgress(long downloaded, long total) {
        downloadedBytes = downloaded;
        totalBytes = total;
        update();
    }

    private static void update() {
        final LuminaUpdateService service = instance;
        if (service != null) {
            service.updateNotification(false);
        }
    }

    /** Tears down the progress notification. Safe to call when nothing was started. */
    public static void stop() {
        active = false;
        downloadedBytes = 0;
        totalBytes = 0;
        try {
            ApplicationLoader.applicationContext.stopService(
                    new Intent(ApplicationLoader.applicationContext, LuminaUpdateService.class));
        } catch (Throwable e) {
            FileLog.e(e);
        }
        try {
            NotificationManagerCompat.from(ApplicationLoader.applicationContext).cancel(PROGRESS_NOTIFICATION_ID);
        } catch (Throwable ignore) {
        }
    }

    /**
     * "Update downloaded — tap to install". Posted only when the download finished while
     * the app was in the background: from Android 10 an app in the background may not
     * start an activity, so the installer cannot simply be thrown on screen.
     */
    public static void showReadyNotification(File apk, String version) {
        if (apk == null || !apk.exists()) {
            return;
        }
        try {
            final Context context = ApplicationLoader.applicationContext;
            NotificationsController.checkOtherNotificationsChannel();
            final NotificationCompat.Builder ready = new NotificationCompat.Builder(context, NotificationsController.OTHER_NOTIFICATIONS_CHANNEL);
            ready.setChannelId(NotificationsController.OTHER_NOTIFICATIONS_CHANNEL);
            ready.setSmallIcon(org.telegram.messenger.R.drawable.notification);
            ready.setWhen(System.currentTimeMillis());
            ready.setAutoCancel(true);
            ready.setContentTitle(LuminaLocale.getString(org.telegram.messenger.R.string.LuminaUpdateReadyTitle));
            if (version != null && version.length() > 0) {
                ready.setContentText(String.format(java.util.Locale.US,
                        LuminaLocale.getString(org.telegram.messenger.R.string.LuminaUpdateReadyVersionText), version));
            } else {
                ready.setContentText(LuminaLocale.getString(org.telegram.messenger.R.string.LuminaUpdateReadyText));
            }
            ready.setContentIntent(installIntent(context, apk));
            NotificationManagerCompat.from(context).notify(READY_NOTIFICATION_ID, ready.build());
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void cancelReadyNotification() {
        try {
            NotificationManagerCompat.from(ApplicationLoader.applicationContext).cancel(READY_NOTIFICATION_ID);
        } catch (Throwable ignore) {
        }
    }

    // ------------------------------------------------------------------ intents

    private static int pendingIntentFlags(int extra) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return extra | PendingIntent.FLAG_IMMUTABLE;
        }
        return extra;
    }

    private static PendingIntent openAppIntent(Context context) {
        try {
            final Intent open = new Intent(context, LaunchActivity.class);
            open.setAction(Intent.ACTION_MAIN);
            open.addCategory(Intent.CATEGORY_LAUNCHER);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return PendingIntent.getActivity(context, REQUEST_OPEN_APP, open,
                    pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT));
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * Hands the downloaded APK straight to the package installer. Built exactly like
     * {@code AndroidUtilities.openForView()} does it (same FileProvider authority), so
     * whatever cache directory the APK landed in is already covered by provider_paths.
     */
    private static PendingIntent installIntent(Context context, File apk) {
        try {
            final Intent install = new Intent(Intent.ACTION_VIEW);
            final Uri uri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                uri = FileProvider.getUriForFile(context, ApplicationLoader.getApplicationId() + ".provider", apk);
            } else {
                uri = Uri.fromFile(apk);
            }
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return PendingIntent.getActivity(context, REQUEST_INSTALL, install,
                    pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT));
        } catch (Throwable e) {
            // Better to land the user in the app than to post a dead notification.
            FileLog.e(e);
            return openAppIntent(context);
        }
    }

    // ------------------------------------------------------------------ service

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        instance = this;
        if (builder == null) {
            try {
                NotificationsController.checkOtherNotificationsChannel();
                builder = new NotificationCompat.Builder(ApplicationLoader.applicationContext, NotificationsController.OTHER_NOTIFICATIONS_CHANNEL);
                builder.setChannelId(NotificationsController.OTHER_NOTIFICATIONS_CHANNEL);
                builder.setSmallIcon(android.R.drawable.stat_sys_download);
                builder.setWhen(System.currentTimeMillis());
                builder.setOngoing(true);
                builder.setOnlyAlertOnce(true);
                builder.setContentTitle(LuminaLocale.getString(org.telegram.messenger.R.string.LuminaUpdateNotificationTitle));
                final PendingIntent open = openAppIntent(ApplicationLoader.applicationContext);
                if (open != null) {
                    builder.setContentIntent(open);
                }
            } catch (Throwable e) {
                FileLog.e(e);
                builder = null;
            }
        }
        // startForeground() must happen promptly after startForegroundService() or the
        // system kills us, so do it before anything else can bail out.
        try {
            if (builder != null) {
                applyProgress();
                startForeground(PROGRESS_NOTIFICATION_ID, builder.build());
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        if (builder == null || !active) {
            stopSelf();
        }
        return Service.START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
        try {
            stopForeground(true);
        } catch (Throwable ignore) {
        }
        try {
            NotificationManagerCompat.from(ApplicationLoader.applicationContext).cancel(PROGRESS_NOTIFICATION_ID);
        } catch (Throwable ignore) {
        }
    }

    private void applyProgress() {
        if (builder == null) {
            return;
        }
        final long downloaded = downloadedBytes;
        final long total = totalBytes;
        if (total > 0) {
            final int percent = (int) Math.max(0, Math.min(100L, downloaded * 100L / total));
            builder.setProgress(100, percent, false);
            builder.setContentText(String.format(java.util.Locale.US,
                    LuminaLocale.getString(org.telegram.messenger.R.string.LuminaUpdateNotificationProgress),
                    formatMb(downloaded), formatMb(total)));
        } else {
            // No Content-Length (chunked transfer): indeterminate bar, no fake numbers.
            builder.setProgress(100, 0, true);
            builder.setContentText(LuminaLocale.getString(org.telegram.messenger.R.string.LuminaUpdateDownloading));
        }
    }

    private void updateNotification(boolean force) {
        if (builder == null) {
            return;
        }
        final long total = totalBytes;
        final int percent = total > 0 ? (int) Math.max(0, Math.min(100L, downloadedBytes * 100L / total)) : -1;
        final long now = System.currentTimeMillis();
        if (!force && percent == lastNotifiedPercent && now - lastNotifyTime < 1000) {
            return;
        }
        lastNotifiedPercent = percent;
        lastNotifyTime = now;
        try {
            applyProgress();
            NotificationManagerCompat.from(ApplicationLoader.applicationContext).notify(PROGRESS_NOTIFICATION_ID, builder.build());
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static String formatMb(long bytes) {
        return String.format(java.util.Locale.US, "%.1f", bytes / (1024f * 1024f));
    }
}
