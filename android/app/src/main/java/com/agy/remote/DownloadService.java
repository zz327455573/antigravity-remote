package com.agy.remote;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Request;
import okhttp3.Response;

public class DownloadService extends Service {
    public static final String EXTRA_REMOTE_PATH = "remote_path";
    private static final String CHANNEL_ID = "agy_download_channel";
    private static int nextNotifId = 2001;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private NotificationManager nm;

    public static void startDownload(Context ctx, String remotePath) {
        Intent i = new Intent(ctx, DownloadService.class);
        i.putExtra(EXTRA_REMOTE_PATH, remotePath);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "文件下载", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        String remotePath = intent.getStringExtra(EXTRA_REMOTE_PATH);
        if (remotePath == null || remotePath.isEmpty()) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        String filename = new File(remotePath).getName();
        int notifId = nextNotifId++;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_app_logo)
                .setContentTitle("正在下载: " + filename)
                .setContentText("准备连接云电脑...")
                .setOngoing(true)
                .setProgress(100, 0, true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notifId, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(notifId, builder.build());
        }

        executor.execute(() -> {
            doDownloadWithResume(remotePath, filename, notifId);
            stopSelf(startId);
        });

        return START_NOT_STICKY;
    }

    private void doDownloadWithResume(String remotePath, String filename, int notifId) {
        ApiClient api = new ApiClient(this);
        File tmpDir = new File(getCacheDir(), "dl_tmp");
        if (!tmpDir.exists()) tmpDir.mkdirs();
        File tmpFile = new File(tmpDir, filename + ".part");
        if (tmpFile.exists()) tmpFile.delete();

        long totalBytes = -1;
        long downloaded = 0;
        int maxRetries = 3;
        boolean success = false;
        String lastErr = "";

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                String encoded = URLEncoder.encode(remotePath, "UTF-8");
                String url = api.getBaseUrl() + "/api/files/download?path=" + encoded;
                Request.Builder rb = new Request.Builder()
                        .url(url)
                        .addHeader("x-auth-token", api.getToken())
                        .get();
                if (downloaded > 0) {
                    rb.addHeader("Range", "bytes=" + downloaded + "-");
                }
                try (Response resp = api.getDownloadClient().newCall(rb.build()).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        lastErr = "HTTP " + resp.code();
                        continue;
                    }
                    long cl = resp.body().contentLength();
                    if (attempt == 0 || totalBytes <= 0) {
                        totalBytes = cl > 0 ? (downloaded + cl) : -1;
                    }
                    boolean append = resp.code() == 206 && downloaded > 0;
                    try (InputStream is = resp.body().byteStream();
                         FileOutputStream fos = new FileOutputStream(tmpFile, append)) {
                        byte[] buf = new byte[32768];
                        int n;
                        long lastNotify = 0;
                        while ((n = is.read(buf)) != -1) {
                            fos.write(buf, 0, n);
                            downloaded += n;
                            long now = System.currentTimeMillis();
                            if (now - lastNotify > 400) {
                                lastNotify = now;
                                updateProgressNotif(notifId, filename, downloaded, totalBytes);
                            }
                        }
                    }
                    success = true;
                    break;
                }
            } catch (Exception e) {
                lastErr = e.getMessage();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
            }
        }

        if (!success) {
            NotificationCompat.Builder errNotif = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_app_logo)
                    .setContentTitle("下载失败: " + filename)
                    .setContentText("原因: " + lastErr)
                    .setAutoCancel(true);
            nm.notify(notifId + 10000, errNotif.build());
            mainHandler.post(() -> Toast.makeText(this, "下载失败: " + filename, Toast.LENGTH_LONG).show());
            return;
        }

        try {
            Uri savedUri = saveToPublicDownloads(tmpFile, filename);
            tmpFile.delete();

            Intent openIntent = new Intent(Intent.ACTION_VIEW);
            openIntent.setDataAndType(savedUri, guessMime(filename));
            openIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(
                    this, notifId, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder doneNotif = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_app_logo)
                    .setContentTitle("下载完成: " + filename)
                    .setContentText("已保存到 Download/反重力遥控，点击打开")
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify(notifId + 10000, doneNotif.build());
            mainHandler.post(() -> Toast.makeText(this, "下载完成: Download/反重力遥控/" + filename, Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            mainHandler.post(() -> Toast.makeText(this, "保存文件失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    private void updateProgressNotif(int notifId, String filename, long downloaded, long total) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_app_logo)
                .setContentTitle("正在下载: " + filename)
                .setOngoing(true);
        if (total > 0) {
            int pct = (int) (downloaded * 100 / total);
            b.setProgress(100, pct, false)
                    .setContentText(formatSize(downloaded) + " / " + formatSize(total) + " (" + pct + "%)");
        } else {
            b.setProgress(100, 0, true)
                    .setContentText("已下载 " + formatSize(downloaded));
        }
        nm.notify(notifId, b.build());
    }

    private Uri saveToPublicDownloads(File src, String filename) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
            values.put(MediaStore.Downloads.MIME_TYPE, guessMime(filename));
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/反重力遥控");
            ContentResolver cr = getContentResolver();
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new Exception("MediaStore insert returned null");
            try (InputStream is = new FileInputStream(src);
                 OutputStream os = cr.openOutputStream(uri)) {
                byte[] buf = new byte[32768];
                int n;
                while ((n = is.read(buf)) != -1) {
                    os.write(buf, 0, n);
                }
            }
            return uri;
        } else {
            File dlDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "反重力遥控");
            if (!dlDir.exists()) dlDir.mkdirs();
            File out = new File(dlDir, filename);
            try (InputStream is = new FileInputStream(src);
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[32768];
                int n;
                while ((n = is.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                }
            }
            return FileProvider.getUriForFile(this, "com.agy.remote.fileprovider", out);
        }
    }

    private String guessMime(String filename) {
        String ext = "";
        int idx = filename.lastIndexOf('.');
        if (idx >= 0 && idx < filename.length() - 1) {
            ext = filename.substring(idx + 1).toLowerCase();
        }
        if ("md".equals(ext) || "py".equals(ext) || "java".equals(ext) || "json".equals(ext)
                || "log".equals(ext) || "txt".equals(ext)) {
            return "text/plain";
        }
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime != null ? mime : "*/*";
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
