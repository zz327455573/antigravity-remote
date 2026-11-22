package com.agy.remote;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private ApiClient api;
    private DrawerLayout drawerLayout;
    private TextView tvTitle, tvSubtitle, tvRunningBanner, tvEmptyHint, tvAttachmentName;
    private LinearLayout layoutAttachment;
    private RecyclerView rvMessages, rvSidebar;
    private EditText etPrompt;
    private MaterialButton btnModel, btnSend;

    private Adapters.SidebarAdapter sidebarAdapter;
    private Adapters.MessageAdapter msgAdapter;

    private final List<Adapters.ProjectItem> allProjects = new ArrayList<>();
    private final List<Adapters.ConversationItem> allConversations = new ArrayList<>();
    private final List<Adapters.ModelItem> allModels = new ArrayList<>();
    private final Set<String> expandedProjectPaths = new HashSet<>();

    private String targetNewConvProjectPath = "";
    private String currentConversationId = "";
    private String pendingUploadedPath = "";
    private String pendingUploadedName = "";
    private String currentTransferDir = "";
    private boolean isSending = false;

    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!currentConversationId.isEmpty()) {
                loadMessages(currentConversationId, false);
            }
            pollHandler.postDelayed(this, 2500);
        }
    };

    private final ActivityResultLauncher<String[]> filePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    uploadSelectedFile(uri);
                }
            });

    private final ActivityResultLauncher<String[]> transferUploadLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    uploadFileToTransferDir(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        api = new ApiClient(this);
        initViews();
        updateModelButton();
        requestNotifPermissionIfNeeded();

        checkConnectionAndLoad();
    }

    private void requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        pollHandler.postDelayed(pollRunnable, 2500);
    }

    @Override
    protected void onPause() {
        super.onPause();
        pollHandler.removeCallbacks(pollRunnable);
    }

    private void initViews() {
        drawerLayout = findViewById(R.id.drawerLayout);
        tvTitle = findViewById(R.id.tvTitle);
        tvSubtitle = findViewById(R.id.tvSubtitle);
        tvRunningBanner = findViewById(R.id.tvRunningBanner);
        tvEmptyHint = findViewById(R.id.tvEmptyHint);
        tvAttachmentName = findViewById(R.id.tvAttachmentName);
        layoutAttachment = findViewById(R.id.layoutAttachment);
        rvMessages = findViewById(R.id.rvMessages);
        rvSidebar = findViewById(R.id.rvSidebar);
        etPrompt = findViewById(R.id.etPrompt);
        btnModel = findViewById(R.id.btnModel);
        btnSend = findViewById(R.id.btnSend);

        rvMessages.setLayoutManager(new LinearLayoutManager(this));
        msgAdapter = new Adapters.MessageAdapter(api, this::showFileActionDialog);
        rvMessages.setAdapter(msgAdapter);

        rvSidebar.setLayoutManager(new LinearLayoutManager(this));
        sidebarAdapter = new Adapters.SidebarAdapter(new Adapters.SidebarListener() {
            @Override
            public void onToggleProject(Adapters.ProjectItem project) {
                String key = project.path.toLowerCase();
                if (expandedProjectPaths.contains(key)) {
                    expandedProjectPaths.remove(key);
                } else {
                    expandedProjectPaths.add(key);
                    api.setSelectedProject(project.name, project.path);
                }
                rebuildSidebar();
            }

            @Override
            public void onNewConvInProject(Adapters.ProjectItem project) {
                startNewConversation(project.path, project.name);
            }

            @Override
            public void onSelectConversation(Adapters.ConversationItem conv) {
                drawerLayout.closeDrawer(GravityCompat.START);
                currentConversationId = conv.id;
                if (conv.workspacePath != null && !conv.workspacePath.isEmpty()) {
                    api.setSelectedProject(conv.workspaceName, conv.workspacePath);
                }
                tvTitle.setText(conv.title);
                loadMessages(conv.id, true);
                rebuildSidebar();
            }
        });
        rvSidebar.setAdapter(sidebarAdapter);

        findViewById(R.id.btnMenu).setOnClickListener(v -> {
            loadProjects();
            loadConversations();
            drawerLayout.openDrawer(GravityCompat.START);
        });

        findViewById(R.id.btnRefresh).setOnClickListener(v -> {
            checkConnectionAndLoad();
            if (!currentConversationId.isEmpty()) {
                loadMessages(currentConversationId, true);
            }
        });

        findViewById(R.id.btnSettings).setOnClickListener(v -> showSettingsDialog());

        findViewById(R.id.btnNewConversation).setOnClickListener(v ->
                startNewConversation("", "独立小任务"));

        findViewById(R.id.btnNewProject).setOnClickListener(v -> showNewProjectDialog());

        btnModel.setOnClickListener(v -> showModelPickerDialog());
        findViewById(R.id.btnAttach).setOnClickListener(v ->
                filePickerLauncher.launch(new String[]{"*/*"}));
        findViewById(R.id.btnRemoveAttachment).setOnClickListener(v -> clearAttachment());
        findViewById(R.id.btnFiles).setOnClickListener(v -> showProjectFilesDialog());
        btnSend.setOnClickListener(v -> sendPrompt());
    }

    private void startNewConversation(String projectPath, String projectName) {
        currentConversationId = "";
        targetNewConvProjectPath = projectPath == null ? "" : projectPath;
        if (!targetNewConvProjectPath.isEmpty()) {
            api.setSelectedProject(projectName, targetNewConvProjectPath);
            tvTitle.setText("新建会话 · " + projectName);
        } else {
            tvTitle.setText("新建独立会话");
        }
        msgAdapter.setItems(new ArrayList<>());
        rvMessages.setVisibility(View.GONE);
        tvEmptyHint.setVisibility(View.VISIBLE);
        tvRunningBanner.setVisibility(View.GONE);
        drawerLayout.closeDrawer(GravityCompat.START);
        rebuildSidebar();
    }

    private void checkConnectionAndLoad() {
        api.get("/api/ping", new ApiClient.JsonCallback() {
            @Override
            public void onSuccess(JSONObject json) {
                String ver = json.optString("version", "");
                tvSubtitle.setText("● 已连接云电脑 (v" + ver + ")");
                tvSubtitle.setTextColor(getColor(R.color.status_idle));
                loadModels();
                loadProjects();
                loadConversations();
            }

            @Override
            public void onError(String message) {
                tvSubtitle.setText("● 连接失败，请点右上角 ⚙ 设置");
                tvSubtitle.setTextColor(0xFFEF4444);
            }
        });
    }

    private void loadModels() {
        api.get("/api/models", new ApiClient.JsonCallback() {
            @Override
            public void onSuccess(JSONObject json) {
                JSONArray arr = json.optJSONArray("models");
                allModels.clear();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        Adapters.ModelItem m = new Adapters.ModelItem();
                        m.id = o.optString("id");
                        m.name = o.optString("name");
                        m.shortName = o.optString("shortName", m.name);
                        allModels.add(m);
                    }
                }
            }

            @Override
            public void onError(String message) {
            }
        });
    }

    private void loadProjects() {
        api.get("/api/projects", new ApiClient.JsonCallback() {
            @Override
            public void onSuccess(JSONObject json) {
                JSONArray arr = json.optJSONArray("projects");
                allProjects.clear();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        Adapters.ProjectItem p = new Adapters.ProjectItem();
                        p.name = o.optString("name");
                        p.path = o.optString("path");
                        allProjects.add(p);
                    }
                }
                rebuildSidebar();
            }

            @Override
            public void onError(String message) {
            }
        });
    }

    private void loadConversations() {
        api.get("/api/conversations", new ApiClient.JsonCallback() {
            @Override
            public void onSuccess(JSONObject json) {
                JSONArray arr = json.optJSONArray("conversations");
                allConversations.clear();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        Adapters.ConversationItem item = new Adapters.ConversationItem();
                        item.id = o.optString("id");
                        item.title = o.optString("title", "未命名会话");
                        item.status = o.optString("status", "");
                        item.stepCount = o.optInt("stepCount", 0);
                        item.workspacePath = o.optString("workspacePath", "");
                        item.workspaceName = o.optString("workspaceName", "");
                        item.lastModifiedTime = o.optString("lastModifiedTime", "");
                        allConversations.add(item);
                    }
                }
                rebuildSidebar();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void rebuildSidebar() {
        List<Adapters.SidebarNode> nodes = new ArrayList<>();

        // 1. 项目手风琴树
        for (Adapters.ProjectItem p : allProjects) {
            List<Adapters.ConversationItem> childConvs = new ArrayList<>();
            for (Adapters.ConversationItem c : allConversations) {
                if (c.workspacePath != null && c.workspacePath.equalsIgnoreCase(p.path)) {
                    childConvs.add(c);
                }
            }
            boolean isExpanded = expandedProjectPaths.contains(p.path.toLowerCase());

            Adapters.SidebarNode pNode = new Adapters.SidebarNode();
            pNode.type = Adapters.SidebarNode.TYPE_PROJECT;
            pNode.project = p;
            pNode.projectConvCount = childConvs.size();
            pNode.expanded = isExpanded;
            nodes.add(pNode);

            if (isExpanded) {
                for (Adapters.ConversationItem c : childConvs) {
                    Adapters.SidebarNode cNode = new Adapters.SidebarNode();
                    cNode.type = Adapters.SidebarNode.TYPE_CONVERSATION;
                    cNode.conversation = c;
                    cNode.indented = true;
                    nodes.add(cNode);
                }
            }
        }

        // 2. 独立会话（不在任何项目下的会话）
        List<Adapters.ConversationItem> standalone = new ArrayList<>();
        for (Adapters.ConversationItem c : allConversations) {
            if (c.workspacePath == null || c.workspacePath.isEmpty()) {
                standalone.add(c);
            }
        }
        if (!standalone.isEmpty()) {
            Adapters.SidebarNode hdr = new Adapters.SidebarNode();
            hdr.type = Adapters.SidebarNode.TYPE_HEADER;
            hdr.headerTitle = "独立会话 (" + standalone.size() + ")";
            nodes.add(hdr);

            for (Adapters.ConversationItem c : standalone) {
                Adapters.SidebarNode cNode = new Adapters.SidebarNode();
                cNode.type = Adapters.SidebarNode.TYPE_CONVERSATION;
                cNode.conversation = c;
                cNode.indented = false;
                nodes.add(cNode);
            }
        }

        sidebarAdapter.setNodes(nodes, currentConversationId);
    }

    private void loadMessages(String cid, boolean scrollToBottom) {
        api.get("/api/conversations/" + cid + "/messages", new ApiClient.JsonCallback() {
            @Override
            public void onSuccess(JSONObject json) {
                String status = json.optString("status", "CASCADE_RUN_STATUS_IDLE");
                boolean running = status.contains("RUNNING");
                tvRunningBanner.setVisibility(running ? View.VISIBLE : View.GONE);

                String title = json.optString("title", "");
                if (!title.isEmpty()) {
                    tvTitle.setText(title);
                }

                JSONArray arr = json.optJSONArray("messages");
                List<Adapters.MessageItem> list = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        Adapters.MessageItem m = new Adapters.MessageItem();
                        m.role = o.optString("role", "assistant");
                        m.content = o.optString("content", "");
                        m.thinking = o.optString("thinking", "");
                        m.status = o.optString("status", "DONE");

                        JSONArray toolsArr = o.optJSONArray("tools");
                        if (toolsArr != null && toolsArr.length() > 0) {
                            StringBuilder sb = new StringBuilder();
                            for (int j = 0; j < toolsArr.length(); j++) {
                                if (j > 0) sb.append(", ");
                                sb.append(toolsArr.optString(j));
                            }
                            m.toolsSummary = sb.toString();
                        }
                        list.add(m);
                    }
                }

                if (list.isEmpty()) {
                    tvEmptyHint.setVisibility(View.VISIBLE);
                    rvMessages.setVisibility(View.GONE);
                } else {
                    tvEmptyHint.setVisibility(View.GONE);
                    rvMessages.setVisibility(View.VISIBLE);
                    msgAdapter.setItems(list);
                    if (scrollToBottom) {
                        rvMessages.scrollToPosition(list.size() - 1);
                    }
                }
            }

            @Override
            public void onError(String message) {
            }
        });
    }

    private void sendPrompt() {
        if (isSending) return;
        String text = etPrompt.getText().toString().trim();
        if (text.isEmpty() && pendingUploadedPath.isEmpty()) {
            Toast.makeText(this, "请输入指令内容", Toast.LENGTH_SHORT).show();
            return;
        }
        String finalPrompt = text;
        if (!pendingUploadedPath.isEmpty()) {
            finalPrompt = (text.isEmpty() ? "请查看并处理我上传的文件：" : text + "\n\n附件文件位于云电脑：")
                    + pendingUploadedPath;
        }

        isSending = true;
        btnSend.setEnabled(false);
        btnSend.setText("发送中");

        try {
            if (currentConversationId.isEmpty()) {
                JSONObject body = new JSONObject();
                body.put("prompt", finalPrompt);
                body.put("model", api.getSelectedModel());
                String titleHint = text.isEmpty() ? pendingUploadedName : text;
                if (titleHint.length() > 24) titleHint = titleHint.substring(0, 24);
                body.put("title", titleHint);
                if (!targetNewConvProjectPath.isEmpty()) {
                    body.put("project_path", targetNewConvProjectPath);
                }

                api.post("/api/conversations", body, new ApiClient.JsonCallback() {
                    @Override
                    public void onSuccess(JSONObject json) {
                        resetSendState();
                        etPrompt.setText("");
                        clearAttachment();
                        String cid = json.optString("conversationId", "");
                        if (!cid.isEmpty()) {
                            currentConversationId = cid;
                            tvRunningBanner.setVisibility(View.VISIBLE);
                            loadConversations();
                            loadMessages(cid, true);
                        }
                    }

                    @Override
                    public void onError(String message) {
                        resetSendState();
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
            } else {
                JSONObject body = new JSONObject();
                body.put("content", finalPrompt);
                body.put("model", api.getSelectedModel());
                api.post("/api/conversations/" + currentConversationId + "/messages", body, new ApiClient.JsonCallback() {
                    @Override
                    public void onSuccess(JSONObject json) {
                        resetSendState();
                        etPrompt.setText("");
                        clearAttachment();
                        tvRunningBanner.setVisibility(View.VISIBLE);
                        loadMessages(currentConversationId, true);
                    }

                    @Override
                    public void onError(String message) {
                        resetSendState();
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
            }
        } catch (Exception e) {
            resetSendState();
            Toast.makeText(this, "构造请求失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void resetSendState() {
        isSending = false;
        btnSend.setEnabled(true);
        btnSend.setText("发送");
    }

    private void uploadSelectedFile(Uri uri) {
        String uploadProj = !targetNewConvProjectPath.isEmpty()
                ? targetNewConvProjectPath : api.getSelectedProjectPath();
        uploadUriWithProgress(uri, uploadProj, null, false);
    }

    private void uploadFileToTransferDir(Uri uri) {
        if (currentTransferDir.isEmpty()) return;
        uploadUriWithProgress(uri, null, currentTransferDir, true);
    }

    private void uploadUriWithProgress(Uri uri, String projectPath, String targetDir, boolean refreshBrowser) {
        String filename = queryFileName(uri);
        long totalSize = queryFileSize(uri);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 32, 48, 24);
        TextView tvProg = new TextView(this);
        tvProg.setText("准备上传...");
        tvProg.setTextSize(13);
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setIndeterminate(totalSize <= 0);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pbLp.topMargin = 20;
        pb.setLayoutParams(pbLp);
        box.addView(tvProg);
        box.addView(pb);

        final Call[] callRef = new Call[1];
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("正在上传 " + filename)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("取消", (d, w) -> {
                    if (callRef[0] != null) callRef[0].cancel();
                })
                .show();

        callRef[0] = api.uploadStreamWithProgress(
                getContentResolver(), uri, filename, totalSize, projectPath, targetDir,
                (uploaded, total) -> {
                    if (total > 0) {
                        int pct = (int) (uploaded * 100 / total);
                        pb.setIndeterminate(false);
                        pb.setProgress(pct);
                        tvProg.setText(formatSize(uploaded) + " / " + formatSize(total) + " (" + pct + "%)");
                    } else {
                        tvProg.setText("已上传 " + formatSize(uploaded));
                    }
                },
                new ApiClient.JsonCallback() {
                    @Override
                    public void onSuccess(JSONObject json) {
                        dlg.dismiss();
                        if (refreshBrowser) {
                            Toast.makeText(MainActivity.this, "上传成功: " + json.optString("filename"), Toast.LENGTH_LONG).show();
                            browseFolder(targetDir);
                        } else {
                            pendingUploadedPath = json.optString("path", "");
                            pendingUploadedName = json.optString("filename", filename);
                            tvAttachmentName.setText("📎 " + pendingUploadedName);
                            layoutAttachment.setVisibility(View.VISIBLE);
                            Toast.makeText(MainActivity.this, "已传到云电脑，点发送即可让反重力处理", Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onError(String message) {
                        dlg.dismiss();
                        if (!message.contains("Canceled")) {
                            Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                        }
                    }
                }
        );
    }

    private long queryFileSize(Uri uri) {
        ContentResolver cr = getContentResolver();
        try (Cursor cursor = cr.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !cursor.isNull(idx)) {
                    return cursor.getLong(idx);
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private String queryFileName(Uri uri) {
        String name = "file_" + System.currentTimeMillis();
        ContentResolver cr = getContentResolver();
        try (Cursor cursor = cr.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    name = cursor.getString(idx);
                }
            }
        } catch (Exception ignored) {
        }
        return name;
    }

    private void clearAttachment() {
        pendingUploadedPath = "";
        pendingUploadedName = "";
        layoutAttachment.setVisibility(View.GONE);
    }

    private void showSettingsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 24, 48, 8);

        EditText etUrl = new EditText(this);
        etUrl.setHint("云电脑网关地址 (支持任意域名或 IP:端口)");
        etUrl.setText(api.getBaseUrl());
        box.addView(etUrl);

        LinearLayout quickRow = new LinearLayout(this);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);
        quickRow.setPadding(0, 8, 0, 12);

        MaterialButton btnCf = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnCf.setText("☁ 小黄云");
        btnCf.setTextSize(12);
        btnCf.setOnClickListener(v -> etUrl.setText("https://your-tunnel-domain.example.com"));

        MaterialButton btnFrp = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnFrp.setText("🖥 腾讯备用");
        btnFrp.setTextSize(12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(16);
        btnFrp.setLayoutParams(lp);
        btnFrp.setOnClickListener(v -> etUrl.setText("http://your-vps-ip:8080"));

        quickRow.addView(btnCf);
        quickRow.addView(btnFrp);
        box.addView(quickRow);

        EditText etToken = new EditText(this);
        etToken.setHint("请输入网关访问密码 (auth_token)");
        etToken.setText(api.getToken());
        box.addView(etToken);

        new AlertDialog.Builder(this)
                .setTitle("连接云电脑设置")
                .setView(box)
                .setPositiveButton("保存并测试", (d, w) -> {
                    api.saveServerConfig(etUrl.getText().toString(), etToken.getText().toString());
                    checkConnectionAndLoad();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showModelPickerDialog() {
        if (allModels.isEmpty()) {
            loadModels();
            Toast.makeText(this, "正在拉取云电脑真实模型列表，请稍候再点", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[allModels.size()];
        for (int i = 0; i < allModels.size(); i++) {
            labels[i] = allModels.get(i).name;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择模型（与云电脑实时同步）")
                .setItems(labels, (d, which) -> {
                    Adapters.ModelItem chosen = allModels.get(which);
                    api.setSelectedModel(chosen.id, chosen.shortName);
                    updateModelButton();
                })
                .show();
    }

    private void updateModelButton() {
        String label = api.getSelectedModelLabel();
        if (label.length() > 18) {
            label = label.substring(0, 18) + "…";
        }
        btnModel.setText(label + " ▾");
    }

    private void showNewProjectDialog() {
        EditText et = new EditText(this);
        et.setHint("输入新项目文件夹名称");
        LinearLayout box = new LinearLayout(this);
        box.setPadding(48, 24, 48, 8);
        box.addView(et);

        new AlertDialog.Builder(this)
                .setTitle("在云电脑新建项目")
                .setView(box)
                .setPositiveButton("创建", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) return;
                    try {
                        JSONObject body = new JSONObject();
                        body.put("name", name);
                        api.post("/api/projects", body, new ApiClient.JsonCallback() {
                            @Override
                            public void onSuccess(JSONObject json) {
                                String n = json.optString("name");
                                String p = json.optString("path");
                                api.setSelectedProject(n, p);
                                expandedProjectPaths.add(p.toLowerCase());
                                loadProjects();
                                Toast.makeText(MainActivity.this, "已新建项目: " + n, Toast.LENGTH_SHORT).show();
                            }

                            @Override
                            public void onError(String message) {
                                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                            }
                        });
                    } catch (Exception ignored) {
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showProjectFilesDialog() {
        api.get("/api/drives", new ApiClient.JsonCallback() {
            @Override
            public void onSuccess(JSONObject json) {
                JSONArray arr = json.optJSONArray("shortcuts");
                List<String> labels = new ArrayList<>();
                List<String> paths = new ArrayList<>();

                String curProj = !targetNewConvProjectPath.isEmpty()
                        ? targetNewConvProjectPath : api.getSelectedProjectPath();
                if (curProj != null && !curProj.isEmpty()) {
                    labels.add("📌 当前项目: " + new File(curProj).getName());
                    paths.add(curProj);
                }
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        labels.add(o.optString("name"));
                        paths.add(o.optString("path"));
                    }
                }
                if (labels.isEmpty()) {
                    Toast.makeText(MainActivity.this, "未找到可用磁盘目录", Toast.LENGTH_SHORT).show();
                    return;
                }
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("云电脑全盘文件互传")
                        .setItems(labels.toArray(new String[0]), (d, which) -> browseFolder(paths.get(which)))
                        .setNegativeButton("关闭", null)
                        .show();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void browseFolder(String folderPath) {
        currentTransferDir = folderPath;
        try {
            String encoded = URLEncoder.encode(folderPath, "UTF-8");
            api.get("/api/files?path=" + encoded, new ApiClient.JsonCallback() {
                @Override
                public void onSuccess(JSONObject json) {
                    String parentPath = json.optString("parent", "");
                    JSONArray arr = json.optJSONArray("entries");

                    List<String> labels = new ArrayList<>();
                    List<String> paths = new ArrayList<>();
                    List<Boolean> isDirs = new ArrayList<>();

                    if (!parentPath.isEmpty()) {
                        labels.add("⬆ 返回上一级");
                        paths.add(parentPath);
                        isDirs.add(true);
                    } else {
                        labels.add("🏠 返回磁盘入口");
                        paths.add("__DRIVES__");
                        isDirs.add(true);
                    }

                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.optJSONObject(i);
                            boolean dir = o.optBoolean("isDir");
                            long size = o.optLong("size", 0);
                            isDirs.add(dir);
                            paths.add(o.optString("path"));
                            if (dir) {
                                labels.add("📁 " + o.optString("name"));
                            } else {
                                labels.add("📄 " + o.optString("name") + " (" + formatSize(size) + ")");
                            }
                        }
                    }

                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle(folderPath)
                            .setItems(labels.toArray(new String[0]), (d, which) -> {
                                String target = paths.get(which);
                                if ("__DRIVES__".equals(target)) {
                                    showProjectFilesDialog();
                                } else if (isDirs.get(which)) {
                                    browseFolder(target);
                                } else {
                                    showFileActionDialog(target);
                                }
                            })
                            .setPositiveButton("📤 上传文件到此目录", (d, w) ->
                                    transferUploadLauncher.launch(new String[]{"*/*"}))
                            .setNegativeButton("关闭", null)
                            .show();
                }

                @Override
                public void onError(String message) {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception ignored) {
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }

    private void showFileActionDialog(String remotePath) {
        String filename = new File(remotePath).getName();
        String[] actions = {"👁 直接打开（调用手机程序）", "⬇ 下载保存到手机"};
        new AlertDialog.Builder(this)
                .setTitle(filename)
                .setItems(actions, (d, which) -> {
                    if (which == 0) {
                        openRemoteFile(remotePath);
                    } else {
                        downloadRemoteFile(remotePath);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openRemoteFile(String remotePath) {
        String filename = new File(remotePath).getName();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 32, 48, 24);
        TextView tvProg = new TextView(this);
        tvProg.setText("正在连接云电脑...");
        tvProg.setTextSize(13);
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setIndeterminate(true);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pbLp.topMargin = 20;
        pb.setLayoutParams(pbLp);
        box.addView(tvProg);
        box.addView(pb);

        final Call[] callRef = new Call[1];
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("正在获取 " + filename)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("取消", (d, w) -> {
                    if (callRef[0] != null) callRef[0].cancel();
                })
                .show();

        new Thread(() -> {
            try {
                String encoded = URLEncoder.encode(remotePath, "UTF-8");
                Request req = new Request.Builder()
                        .url(api.getBaseUrl() + "/api/files/download?path=" + encoded)
                        .addHeader("x-auth-token", api.getToken())
                        .get()
                        .build();
                Call call = api.getDownloadClient().newCall(req);
                callRef[0] = call;
                try (Response resp = call.execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        runOnUiThread(() -> {
                            dlg.dismiss();
                            Toast.makeText(this, "获取文件失败 (" + resp.code() + ")", Toast.LENGTH_SHORT).show();
                        });
                        return;
                    }
                    long total = resp.body().contentLength();
                    File cacheDir = new File(getCacheDir(), "remote_open");
                    if (!cacheDir.exists()) cacheDir.mkdirs();
                    File out = new File(cacheDir, filename);
                    try (InputStream is = resp.body().byteStream();
                         FileOutputStream fos = new FileOutputStream(out)) {
                        byte[] buf = new byte[32768];
                        long read = 0;
                        long lastUi = 0;
                        int n;
                        while ((n = is.read(buf)) != -1) {
                            fos.write(buf, 0, n);
                            read += n;
                            long now = System.currentTimeMillis();
                            if (now - lastUi > 200) {
                                lastUi = now;
                                final long rNow = read;
                                runOnUiThread(() -> {
                                    if (total > 0) {
                                        int pct = (int) (rNow * 100 / total);
                                        pb.setIndeterminate(false);
                                        pb.setProgress(pct);
                                        tvProg.setText(formatSize(rNow) + " / " + formatSize(total) + " (" + pct + "%)");
                                    } else {
                                        tvProg.setText("已下载 " + formatSize(rNow));
                                    }
                                });
                            }
                        }
                    }
                    Uri contentUri = FileProvider.getUriForFile(this, "com.agy.remote.fileprovider", out);
                    String mime = guessMimeType(filename);
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(contentUri, mime);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    runOnUiThread(() -> {
                        dlg.dismiss();
                        try {
                            startActivity(Intent.createChooser(intent, "选择打开方式"));
                        } catch (Exception e) {
                            Toast.makeText(this, "手机上没有能打开此类文件的程序", Toast.LENGTH_LONG).show();
                        }
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    dlg.dismiss();
                    if (!"Canceled".equals(e.getMessage())) {
                        Toast.makeText(this, "打开失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private String guessMimeType(String filename) {
        String ext = "";
        int idx = filename.lastIndexOf('.');
        if (idx >= 0 && idx < filename.length() - 1) {
            ext = filename.substring(idx + 1).toLowerCase();
        }
        if ("md".equals(ext) || "py".equals(ext) || "java".equals(ext) || "json".equals(ext)
                || "yaml".equals(ext) || "yml".equals(ext) || "log".equals(ext) || "bat".equals(ext)
                || "toml".equals(ext) || "xml".equals(ext) || "js".equals(ext) || "ts".equals(ext)) {
            return "text/plain";
        }
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime != null ? mime : "*/*";
    }

    private void downloadRemoteFile(String remotePath) {
        String filename = new File(remotePath).getName();
        DownloadService.startDownload(this, remotePath);
        Toast.makeText(this, "已开启后台下载: " + filename + "（请在通知栏查看进度）", Toast.LENGTH_LONG).show();
    }
}
