package com.agy.remote;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

public class ApiClient {
    private static final String PREF_NAME = "agy_remote_prefs";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_TOKEN = "auth_token";
    private static final String KEY_MODEL = "selected_model_id";
    private static final String KEY_MODEL_LABEL = "selected_model_label";
    private static final String KEY_PROJECT_PATH = "selected_project_path";
    private static final String KEY_PROJECT_NAME = "selected_project_name";

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final SharedPreferences prefs;
    private final OkHttpClient client;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface JsonCallback {
        void onSuccess(JSONObject json);
        void onError(String message);
    }

    public interface ProgressCallback {
        void onProgress(long uploaded, long total);
    }

    private static final String DEFAULT_CF_URL = "https://your-tunnel-domain.example.com";
    public static final String CHROME_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private final OkHttpClient downloadClient;

    public ApiClient(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(35, TimeUnit.SECONDS)
                .writeTimeout(35, TimeUnit.SECONDS)
                .addInterceptor(chain -> {
                    Request req = chain.request().newBuilder()
                            .header("User-Agent", CHROME_UA)
                            .build();
                    return chain.proceed(req);
                })
                .build();

        downloadClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.MINUTES)
                .writeTimeout(10, TimeUnit.MINUTES)
                .addInterceptor(chain -> {
                    Request req = chain.request().newBuilder()
                            .header("User-Agent", CHROME_UA)
                            .build();
                    return chain.proceed(req);
                })
                .build();
    }

    public String getBaseUrl() {
        String url = prefs.getString(KEY_BASE_URL, DEFAULT_CF_URL);
        if (url.contains("127.0.0.1")) {
            url = DEFAULT_CF_URL;
            prefs.edit().putString(KEY_BASE_URL, url).apply();
        }
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    public String getToken() {
        return prefs.getString(KEY_TOKEN, "");
    }

    public void saveServerConfig(String baseUrl, String token) {
        String clean = baseUrl.trim();
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "http://" + clean;
        }
        if (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        prefs.edit()
                .putString(KEY_BASE_URL, clean)
                .putString(KEY_TOKEN, token.trim())
                .apply();
    }

    public String getSelectedModel() {
        return prefs.getString(KEY_MODEL, "MODEL_PLACEHOLDER_M526");
    }

    public String getSelectedModelLabel() {
        return prefs.getString(KEY_MODEL_LABEL, "Gemini 4 Hydrogen (Medium)");
    }

    public void setSelectedModel(String modelId, String label) {
        prefs.edit()
                .putString(KEY_MODEL, modelId)
                .putString(KEY_MODEL_LABEL, label)
                .apply();
    }

    public String getSelectedProjectPath() {
        return prefs.getString(KEY_PROJECT_PATH, "");
    }

    public String getSelectedProjectName() {
        return prefs.getString(KEY_PROJECT_NAME, "默认工作区");
    }

    public void setSelectedProject(String name, String path) {
        prefs.edit()
                .putString(KEY_PROJECT_NAME, name)
                .putString(KEY_PROJECT_PATH, path)
                .apply();
    }

    public void get(String path, JsonCallback cb) {
        Request req = new Request.Builder()
                .url(getBaseUrl() + path)
                .addHeader("x-auth-token", getToken())
                .get()
                .build();
        enqueueJson(req, cb);
    }

    public void post(String path, JSONObject body, JsonCallback cb) {
        RequestBody rb = RequestBody.create(body.toString(), JSON);
        Request req = new Request.Builder()
                .url(getBaseUrl() + path)
                .addHeader("x-auth-token", getToken())
                .post(rb)
                .build();
        enqueueJson(req, cb);
    }

    public Call uploadStreamWithProgress(
            ContentResolver cr,
            Uri uri,
            String filename,
            long totalSize,
            String projectPath,
            String targetDir,
            ProgressCallback progressCb,
            JsonCallback cb
    ) {
        RequestBody fileBody = new RequestBody() {
            @Override
            public MediaType contentType() {
                return MediaType.parse("application/octet-stream");
            }

            @Override
            public long contentLength() {
                return totalSize > 0 ? totalSize : -1;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                try (InputStream is = cr.openInputStream(uri)) {
                    if (is == null) throw new IOException("无法打开文件流");
                    byte[] buf = new byte[32768];
                    long sent = 0;
                    long lastUi = 0;
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        sink.write(buf, 0, n);
                        sent += n;
                        long now = System.currentTimeMillis();
                        if (now - lastUi > 150) {
                            lastUi = now;
                            final long sNow = sent;
                            mainHandler.post(() -> progressCb.onProgress(sNow, totalSize));
                        }
                    }
                    final long sFinal = sent;
                    mainHandler.post(() -> progressCb.onProgress(sFinal, totalSize));
                }
            }
        };

        MultipartBody.Builder mb = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", filename, fileBody);
        if (projectPath != null && !projectPath.isEmpty()) {
            mb.addFormDataPart("project_path", projectPath);
        }
        if (targetDir != null && !targetDir.isEmpty()) {
            mb.addFormDataPart("target_dir", targetDir);
        }
        Request req = new Request.Builder()
                .url(getBaseUrl() + "/api/upload")
                .addHeader("x-auth-token", getToken())
                .post(mb.build())
                .build();
        Call call = downloadClient.newCall(req);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call c, IOException e) {
                mainHandler.post(() -> cb.onError("上传失败: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call c, Response response) throws IOException {
                String raw = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    mainHandler.post(() -> cb.onError("上传失败 (" + response.code() + "): " + raw));
                    return;
                }
                try {
                    JSONObject json = new JSONObject(raw);
                    mainHandler.post(() -> cb.onSuccess(json));
                } catch (Exception e) {
                    mainHandler.post(() -> cb.onError("解析响应失败: " + e.getMessage()));
                }
            }
        });
        return call;
    }

    public OkHttpClient getOkHttpClient() {
        return client;
    }

    public OkHttpClient getDownloadClient() {
        return downloadClient;
    }

    private void enqueueJson(Request req, JsonCallback cb) {
        client.newCall(req).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> cb.onError("网络错误: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String raw = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    String msg = "请求失败 (" + response.code() + ")";
                    try {
                        JSONObject err = new JSONObject(raw);
                        if (err.has("detail")) {
                            msg = err.getString("detail");
                        }
                    } catch (Exception ignored) {
                    }
                    String finalMsg = msg;
                    mainHandler.post(() -> cb.onError(finalMsg));
                    return;
                }
                try {
                    JSONObject json = new JSONObject(raw);
                    mainHandler.post(() -> cb.onSuccess(json));
                } catch (Exception e) {
                    mainHandler.post(() -> cb.onError("解析响应失败: " + e.getMessage()));
                }
            }
        });
    }
}
