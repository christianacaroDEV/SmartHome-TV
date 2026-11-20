package com.liskovsoft.smartyoutubetv2.common.misc;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;

/**
 * BlueTube's own updater (phone and TV). Before Android's installer says just "App not installed", it checks
 * and explains in plain words: the "install unknown apps" permission, free space, a complete download, the same
 * signature as the installed app; then it installs through Android's PackageInstaller, which tells the exact
 * reason when something fails (conflict, storage, blocked by Play Protect, invalid file...).
 */
public final class BlueTubeUpdater {
    private static final String TAG = BlueTubeUpdater.class.getSimpleName();
    private static final String ACTION_STATUS = "com.bluetube.UPDATE_STATUS";
    /** Download + temporary copy + the installed app: what must be free at least */
    private static final long MIN_FREE_BYTES = 150L * 1024 * 1024;
    private static boolean sRunning;
    private static WeakReference<Activity> sActivity = new WeakReference<>(null);
    private static BroadcastReceiver sReceiver;
    /** Waiting for the "install unknown apps" permission: continues by itself once it is given */
    private static String sPendingUrl;
    private static int sPendingVersion;

    /**
     * Back in the app (screen resumed): if the update was waiting for the permission and it is given now, go on.
     * @return true if the update continues (no need to show the "new update" card again)
     */
    public static boolean resumePending(Activity activity) {
        if (sPendingUrl == null || sRunning || activity == null) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            return false;
        }

        String url = sPendingUrl;
        sPendingUrl = null;
        new Handler(Looper.getMainLooper()).postDelayed(() -> start(activity, url, sPendingVersion), 600);
        return true;
    }

    private BlueTubeUpdater() {
    }

    public static void start(Activity activity, String url, int versionCode) {
        if (sRunning || activity == null) {
            return;
        }

        sActivity = new WeakReference<>(activity);

        // 1) Android 8+: BlueTube must be allowed to install apps (asked once)
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            sPendingUrl = url;
            sPendingVersion = versionCode;
            StartupUpdateCheck.card(activity, activity.getString(R.string.update_permission_title),
                    activity.getString(R.string.update_permission_text),
                    activity.getString(R.string.update_open_settings), () -> openUnknownSourcesSettings(activity),
                    activity.getString(R.string.bluetube_update_later), null);
            return;
        }

        // 2) Enough free space
        long free = freeBytes(activity);
        if (free >= 0 && free < MIN_FREE_BYTES) {
            showNoSpace(activity, MIN_FREE_BYTES - free);
            return;
        }

        // 3) Download (with progress), then check and install
        sRunning = true;
        StartupUpdateCheck.Card progress = StartupUpdateCheck.card(activity, activity.getString(R.string.update_downloading_title),
                activity.getString(R.string.update_downloading, 0), null, null, null, null);
        progress.dialog.setCancelable(false);
        Handler main = new Handler(Looper.getMainLooper());
        Context app = activity.getApplicationContext();
        File apk = new File(new File(app.getCacheDir(), "update"), "BlueTube-" + versionCode + ".apk");

        new Thread(() -> {
            String error = download(url, apk, percent -> main.post(() ->
                    // the screen's context: the app language (the application one follows the system)
                    progress.message.setText(progress.message.getContext().getString(R.string.update_downloading, percent))));
            main.post(() -> {
                dismiss(progress);
                sRunning = false;
                Activity current = currentActivity();
                if (current == null) {
                    return;
                }
                if (error != null) {
                    Log.e(TAG, "Download: %s", error);
                    showError(current, current.getString(R.string.update_download_failed), url, versionCode);
                    return;
                }
                verifyAndInstall(current, apk, url, versionCode);
            });
        }, "BlueTubeUpdater").start();
    }

    // ---------------------------------------------------------------------------------------------
    // Checks
    // ---------------------------------------------------------------------------------------------

    private static void verifyAndInstall(Activity activity, File apk, String url, int versionCode) {
        PackageManager pm = activity.getPackageManager();
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), signatureFlags());

        // A broken file: Android would say "There was a problem parsing the package"
        if (archive == null || !activity.getPackageName().equals(archive.packageName)) {
            apk.delete();
            showError(activity, activity.getString(R.string.update_failed_invalid), url, versionCode);
            return;
        }

        // Another signature: Android refuses the update ("App not installed"), only a fresh install works
        if (!sameSignature(activity, archive)) {
            showSignatureConflict(activity);
            return;
        }

        install(activity, apk, url, versionCode);
    }

    @SuppressWarnings("deprecation")
    private static int signatureFlags() {
        return Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }

    @SuppressWarnings("deprecation")
    private static boolean sameSignature(Context context, PackageInfo archive) {
        try {
            PackageInfo installed = context.getPackageManager().getPackageInfo(context.getPackageName(), signatureFlags());
            Signature[] a = signatures(installed);
            Signature[] b = signatures(archive);
            if (a == null || b == null || a.length == 0 || b.length == 0) {
                return true; // unknown: let Android decide (and explain its answer)
            }
            return Arrays.equals(a, b);
        } catch (Exception e) {
            return true;
        }
    }

    @SuppressWarnings("deprecation")
    @SuppressLint("NewApi")
    private static Signature[] signatures(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            return info.signingInfo.hasMultipleSigners() ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
        }
        return info.signatures;
    }

    private static long freeBytes(Context context) {
        try {
            return context.getCacheDir().getUsableSpace();
        } catch (Exception e) {
            return -1;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Download
    // ---------------------------------------------------------------------------------------------

    private interface Progress {
        void onPercent(int percent);
    }

    /** null = done; else the reason */
    private static String download(String url, File target, Progress progress) {
        File folder = target.getParentFile();
        if (folder != null) {
            File[] old = folder.listFiles();
            for (int i = 0; old != null && i < old.length; i++) {
                old[i].delete(); // older downloads take space for nothing
            }
            folder.mkdirs();
        }

        File part = new File(target.getAbsolutePath() + ".part");
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection) new URL(url + (url.contains("?") ? "&" : "?") + "t=" + System.currentTimeMillis()).openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setInstanceFollowRedirects(true);
            int code = connection.getResponseCode();
            if (code != 200) {
                return "HTTP " + code;
            }

            long total = connection.getContentLength();
            long done = 0;
            int lastPercent = -1;
            InputStream in = connection.getInputStream();
            OutputStream out = new FileOutputStream(part);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                done += read;
                int percent = total > 0 ? (int) (done * 100 / total) : 0;
                if (percent != lastPercent) {
                    lastPercent = percent;
                    progress.onPercent(percent);
                }
            }
            out.close();
            in.close();

            // The whole file arrived (a cut connection left a broken APK before)
            if (total > 0 && done != total) {
                part.delete();
                return "incomplete " + done + "/" + total;
            }

            return part.renameTo(target) ? null : "rename";
        } catch (Exception e) {
            part.delete();
            return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Install
    // ---------------------------------------------------------------------------------------------

    private static void install(Activity activity, File apk, String url, int versionCode) {
        if (Build.VERSION.SDK_INT < 21) {
            Helpers.installPackage(activity, apk.getAbsolutePath()); // old Android: the classic installer screen
            return;
        }

        Context app = activity.getApplicationContext();

        try {
            registerReceiver(app, url, versionCode);
            PackageInstaller installer = app.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(app.getPackageName());
            params.setSize(apk.length());
            int sessionId = installer.createSession(params);
            PackageInstaller.Session session = installer.openSession(sessionId);
            OutputStream out = session.openWrite("bluetube", 0, apk.length());
            InputStream in = new FileInputStream(apk);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            in.close();
            session.fsync(out);
            out.close();

            Intent status = new Intent(ACTION_STATUS).setPackage(app.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pending = PendingIntent.getBroadcast(app, sessionId, status, flags);
            session.commit(pending.getIntentSender());
            session.close();
        } catch (Exception e) {
            Log.e(TAG, "Install: %s", e.getMessage());
            // Some boxes don't let apps use the installer API: the classic installer screen instead
            Helpers.installPackage(activity, apk.getAbsolutePath());
        }
    }

    @SuppressLint({"UnspecifiedRegisterReceiverFlag", "WrongConstant"})
    private static void registerReceiver(Context app, String url, int versionCode) {
        if (sReceiver != null) {
            try {
                app.unregisterReceiver(sReceiver);
            } catch (Exception e) {
                // NOP
            }
        }

        sReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Log.d(TAG, "Status %s: %s", status, message);

                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    // Android's confirmation: "Do you want to install this update?"
                    Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(confirm);
                    }
                    return;
                }

                Activity activity = currentActivity();
                if (status == PackageInstaller.STATUS_SUCCESS || activity == null) {
                    return; // the new version starts by itself (this process is replaced)
                }

                explainFailure(activity, status, message, url, versionCode);
            }
        };

        IntentFilter filter = new IntentFilter(ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(sReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            app.registerReceiver(sReceiver, filter);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Messages
    // ---------------------------------------------------------------------------------------------

    private static void explainFailure(Activity activity, int status, String detail, String url, int versionCode) {
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                return; // the person tapped Cancel
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                showSignatureConflict(activity);
                return;
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                showNoSpace(activity, MIN_FREE_BYTES);
                return;
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                showError(activity, activity.getString(R.string.update_failed_blocked), url, versionCode);
                return;
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                showError(activity, activity.getString(R.string.update_failed_incompatible), null, versionCode);
                return;
            case PackageInstaller.STATUS_FAILURE_INVALID:
                showError(activity, activity.getString(R.string.update_failed_invalid), url, versionCode);
                return;
            default:
                showError(activity, activity.getString(R.string.update_failed_generic,
                        detail != null ? detail : String.valueOf(status)), url, versionCode);
        }
    }

    private static void showSignatureConflict(Activity activity) {
        StartupUpdateCheck.card(activity, activity.getString(R.string.update_signature_title),
                activity.getString(R.string.update_signature_text),
                activity.getString(R.string.update_uninstall), () -> {
                    Intent uninstall = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + activity.getPackageName()));
                    uninstall.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        activity.startActivity(uninstall);
                    } catch (Exception e) {
                        // NOP
                    }
                },
                activity.getString(R.string.update_close), null);
    }

    private static void showNoSpace(Activity activity, long missingBytes) {
        long mb = Math.max(1, missingBytes / (1024 * 1024));
        StartupUpdateCheck.card(activity, activity.getString(R.string.update_no_space_title),
                activity.getString(R.string.update_no_space, mb),
                activity.getString(R.string.update_open_storage), () -> {
                    try {
                        activity.startActivity(new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception e) {
                        // NOP
                    }
                },
                activity.getString(R.string.update_close), null);
    }

    private static void showError(Activity activity, String message, String url, int versionCode) {
        StartupUpdateCheck.card(activity, activity.getString(R.string.update_failed_title), message,
                url != null ? activity.getString(R.string.update_retry) : activity.getString(R.string.update_close),
                url != null ? () -> start(activity, url, versionCode) : null,
                url != null ? activity.getString(R.string.update_close) : null, null);
    }

    private static void openUnknownSourcesSettings(Activity activity) {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e2) {
                // NOP
            }
        }
    }

    private static void dismiss(StartupUpdateCheck.Card card) {
        try {
            card.dialog.dismiss();
        } catch (Exception e) {
            // NOP
        }
    }

    private static Activity currentActivity() {
        Activity activity = sActivity.get();
        return activity != null && !activity.isFinishing() ? activity : null;
    }
}
