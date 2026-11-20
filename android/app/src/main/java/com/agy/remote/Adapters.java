package com.agy.remote;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Request;
import okhttp3.Response;

public class Adapters {

    public static class ProjectItem {
        public String name;
        public String path;
    }

    public static class ModelItem {
        public String id;
        public String name;
        public String shortName;
    }

    public static class ConversationItem {
        public String id;
        public String title;
        public String status;
        public int stepCount;
        public String workspacePath;
        public String workspaceName;
        public String lastModifiedTime;
    }

    public static class MessageItem {
        public String role;
        public String content;
        public String thinking;
        public String toolsSummary;
        public String status;
    }

    public static class SidebarNode {
        public static final int TYPE_HEADER = 0;
        public static final int TYPE_PROJECT = 1;
        public static final int TYPE_CONVERSATION = 2;

        public int type;
        public String headerTitle;
        public ProjectItem project;
        public int projectConvCount;
        public boolean expanded;
        public ConversationItem conversation;
        public boolean indented;
    }

    public interface SidebarListener {
        void onToggleProject(ProjectItem project);
        void onNewConvInProject(ProjectItem project);
        void onSelectConversation(ConversationItem conv);
    }

    public interface AttachmentClickListener {
        void onAttachmentClick(String remotePath);
    }

    public static class SidebarAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final List<SidebarNode> nodes = new ArrayList<>();
        private final SidebarListener listener;
        private String activeConvId = "";

        public SidebarAdapter(SidebarListener listener) {
            this.listener = listener;
        }

        public void setNodes(List<SidebarNode> list, String activeConvId) {
            this.nodes.clear();
            this.nodes.addAll(list);
            this.activeConvId = activeConvId == null ? "" : activeConvId;
            notifyDataSetChanged();
        }

        @Override
        public int getItemViewType(int position) {
            return nodes.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == SidebarNode.TYPE_HEADER) {
                TextView tv = new TextView(parent.getContext());
                tv.setPadding(36, 26, 24, 12);
                tv.setTextSize(12);
                tv.setTextColor(0xFF6B7280);
                return new HeaderVH(tv);
            }
            if (viewType == SidebarNode.TYPE_PROJECT) {
                return new ProjectVH(inf.inflate(R.layout.item_project, parent, false));
            }
            return new ConvVH(inf.inflate(R.layout.item_conversation, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
            SidebarNode node = nodes.get(pos);
            if (holder instanceof HeaderVH) {
                ((HeaderVH) holder).tv.setText(node.headerTitle);
                return;
            }
            if (holder instanceof ProjectVH) {
                ProjectVH h = (ProjectVH) holder;
                String arrow = node.expanded ? "▾ " : "▸ ";
                h.tvName.setText(arrow + "📁 " + node.project.name + " (" + node.projectConvCount + ")");
                h.itemView.setBackgroundColor(node.expanded ? 0xFFF3F4F6 : 0x00000000);
                h.itemView.setOnClickListener(v -> listener.onToggleProject(node.project));
                h.btnNew.setOnClickListener(v -> listener.onNewConvInProject(node.project));
                return;
            }
            ConvVH h = (ConvVH) holder;
            ConversationItem item = node.conversation;
            boolean isActive = item.id.equals(activeConvId);
            float density = h.itemView.getResources().getDisplayMetrics().density;
            int leftPad = (int) ((node.indented ? 30 : 12) * density);
            int vPad = (int) (9 * density);
            int rPad = (int) (12 * density);
            h.itemView.setPadding(leftPad, vPad, rPad, vPad);

            h.tvTitle.setText((isActive ? "▶ " : "") + item.title);
            boolean running = item.status != null && item.status.contains("RUNNING");
            String stateText = running ? "🟡 干活中" : "🟢 就绪";
            h.tvMeta.setText(stateText + " · " + item.stepCount + "步");
            h.itemView.setBackgroundColor(isActive ? 0xFFEFF6FF : 0x00000000);
            h.itemView.setOnClickListener(v -> listener.onSelectConversation(item));
        }

        @Override
        public int getItemCount() {
            return nodes.size();
        }

        static class HeaderVH extends RecyclerView.ViewHolder {
            TextView tv;
            HeaderVH(TextView v) {
                super(v);
                tv = v;
            }
        }

        static class ProjectVH extends RecyclerView.ViewHolder {
            TextView tvName, btnNew;
            ProjectVH(View v) {
                super(v);
                tvName = v.findViewById(R.id.tvProjectName);
                btnNew = v.findViewById(R.id.btnProjectNewConv);
            }
        }

        static class ConvVH extends RecyclerView.ViewHolder {
            TextView tvTitle, tvMeta;
            ConvVH(View v) {
                super(v);
                tvTitle = v.findViewById(R.id.tvConvTitle);
                tvMeta = v.findViewById(R.id.tvConvMeta);
            }
        }
    }

    public static class MessageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_USER = 1;
        private static final int TYPE_AI = 2;

        private static final Pattern FILE_URI_PATTERN = Pattern.compile("file:///([^\\s)\\]\"'<>#]+)");
        private static final Pattern ATTACH_PATH_PATTERN = Pattern.compile("附件文件位于云电脑：([^\\n\\r]+)");

        private final List<MessageItem> items = new ArrayList<>();
        private final Set<Integer> expandedPositions = new HashSet<>();
        private final Map<String, Bitmap> thumbCache = new HashMap<>();
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private final ApiClient api;
        private final AttachmentClickListener attachListener;
        private String lastSnapshotSignature = "";

        public MessageAdapter(ApiClient api, AttachmentClickListener attachListener) {
            this.api = api;
            this.attachListener = attachListener;
        }

        public void setItems(List<MessageItem> list) {
            StringBuilder sig = new StringBuilder();
            sig.append(list.size()).append("|");
            for (MessageItem m : list) {
                sig.append(m.role).append(":")
                        .append(m.status).append(":")
                        .append(m.content != null ? m.content.length() : 0).append(":")
                        .append(m.thinking != null ? m.thinking.length() : 0).append(":")
                        .append(m.toolsSummary != null ? m.toolsSummary.length() : 0).append(";");
            }
            String newSig = sig.toString();
            if (newSig.equals(lastSnapshotSignature)) {
                return;
            }
            lastSnapshotSignature = newSig;
            this.items.clear();
            this.items.addAll(list);
            notifyDataSetChanged();
        }

        @Override
        public int getItemViewType(int position) {
            return "user".equals(items.get(position).role) ? TYPE_USER : TYPE_AI;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_USER) {
                return new UserVH(inf.inflate(R.layout.item_message_user, parent, false));
            }
            return new AiVH(inf.inflate(R.layout.item_message_ai, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            MessageItem item = items.get(position);
            List<String> paths = extractPaths(item.content);

            if (holder instanceof UserVH) {
                UserVH uh = (UserVH) holder;
                uh.tvContent.setText(item.content);
                uh.tvContent.setTextIsSelectable(true);
                uh.btnCopy.setOnClickListener(v -> copyText(v.getContext(), item.content));
                renderAttachments(uh.layoutAttachments, paths);
                return;
            }
            AiVH h = (AiVH) holder;
            StringBuilder detail = new StringBuilder();
            if (item.toolsSummary != null && !item.toolsSummary.isEmpty()) {
                detail.append("【调用工具】").append(item.toolsSummary).append("\n");
            }
            if (item.thinking != null && !item.thinking.isEmpty()) {
                detail.append("【思考】\n").append(item.thinking);
            }

            String detailText = detail.toString().trim();
            if (detailText.isEmpty()) {
                h.tvProcessToggle.setVisibility(View.GONE);
                h.tvProcessDetail.setVisibility(View.GONE);
            } else {
                boolean expanded = expandedPositions.contains(position);
                h.tvProcessToggle.setVisibility(View.VISIBLE);
                h.tvProcessToggle.setText(expanded ? "⚙ 干活过程（点击收起） ▾" : "⚙ 干活过程（点击展开） ▸");
                h.tvProcessDetail.setVisibility(expanded ? View.VISIBLE : View.GONE);
                h.tvProcessDetail.setText(detailText);
                h.tvProcessToggle.setOnClickListener(v -> {
                    if (expandedPositions.contains(position)) {
                        expandedPositions.remove(position);
                    } else {
                        expandedPositions.add(position);
                    }
                    notifyItemChanged(position);
                });
            }

            if (item.content != null && !item.content.isEmpty()) {
                h.tvContent.setText(item.content);
                h.tvContent.setTextIsSelectable(true);
                h.btnCopy.setVisibility(View.VISIBLE);
                h.btnCopy.setOnClickListener(v -> copyText(v.getContext(), item.content));
            } else {
                h.tvContent.setText("⏳ 正在执行操作...");
                h.btnCopy.setVisibility(View.GONE);
            }
            renderAttachments(h.layoutAttachments, paths);
        }

        private List<String> extractPaths(String text) {
            Set<String> set = new LinkedHashSet<>();
            if (text == null || text.isEmpty()) return new ArrayList<>();

            Matcher m1 = FILE_URI_PATTERN.matcher(text);
            while (m1.find()) {
                try {
                    String raw = URLDecoder.decode(m1.group(1), "UTF-8");
                    raw = raw.replace("/", "\\");
                    if (raw.length() > 2 && raw.charAt(1) == ':') {
                        set.add(raw);
                    }
                } catch (Exception ignored) {
                }
            }
            Matcher m2 = ATTACH_PATH_PATTERN.matcher(text);
            while (m2.find()) {
                String p = m2.group(1).trim();
                if (!p.isEmpty()) set.add(p);
            }
            return new ArrayList<>(set);
        }

        private void renderAttachments(LinearLayout container, List<String> paths) {
            container.removeAllViews();
            if (paths.isEmpty()) {
                container.setVisibility(View.GONE);
                return;
            }
            container.setVisibility(View.VISIBLE);
            Context ctx = container.getContext();
            float d = ctx.getResources().getDisplayMetrics().density;

            for (String p : paths) {
                String name = new File(p).getName();
                boolean isImg = isImageFile(name);

                LinearLayout card = new LinearLayout(ctx);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setBackgroundColor(0xFFEEF3FA);
                int pad = (int) (10 * d);
                card.setPadding(pad, pad, pad, pad);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = (int) (6 * d);
                card.setLayoutParams(lp);

                TextView tv = new TextView(ctx);
                tv.setText((isImg ? "🖼 " : "📄 ") + name + "  [点击打开/下载]");
                tv.setTextColor(0xFF1A73E8);
                tv.setTextSize(13);
                card.addView(tv);

                if (isImg) {
                    ImageView iv = new ImageView(ctx);
                    LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, (int) (160 * d));
                    ivLp.topMargin = (int) (6 * d);
                    iv.setLayoutParams(ivLp);
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    card.addView(iv);
                    loadThumbnail(p, iv);
                }

                card.setOnClickListener(v -> {
                    if (attachListener != null) {
                        attachListener.onAttachmentClick(p);
                    }
                });
                container.addView(card);
            }
        }

        private boolean isImageFile(String name) {
            String low = name.toLowerCase();
            return low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".jpeg")
                    || low.endsWith(".webp") || low.endsWith(".gif");
        }

        private void loadThumbnail(String remotePath, ImageView iv) {
            Bitmap cached = thumbCache.get(remotePath);
            if (cached != null) {
                iv.setImageBitmap(cached);
                return;
            }
            new Thread(() -> {
                try {
                    String encoded = URLEncoder.encode(remotePath, "UTF-8");
                    Request req = new Request.Builder()
                            .url(api.getBaseUrl() + "/api/files/download?path=" + encoded)
                            .addHeader("x-auth-token", api.getToken())
                            .get()
                            .build();
                    try (Response resp = api.getOkHttpClient().newCall(req).execute()) {
                        if (!resp.isSuccessful() || resp.body() == null) return;
                        byte[] bytes = resp.body().bytes();
                        if (bytes.length > 8 * 1024 * 1024) return;
                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inSampleSize = bytes.length > 1024 * 1024 ? 4 : 2;
                        Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
                        if (bmp != null) {
                            thumbCache.put(remotePath, bmp);
                            mainHandler.post(() -> iv.setImageBitmap(bmp));
                        }
                    }
                } catch (Exception ignored) {
                }
            }).start();
        }

        private void copyText(Context ctx, String text) {
            if (text == null || text.isEmpty()) return;
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("agy_msg", text));
                Toast.makeText(ctx, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class UserVH extends RecyclerView.ViewHolder {
            TextView tvContent, btnCopy;
            LinearLayout layoutAttachments;
            UserVH(View v) {
                super(v);
                tvContent = v.findViewById(R.id.tvUserContent);
                btnCopy = v.findViewById(R.id.btnCopyUser);
                layoutAttachments = v.findViewById(R.id.layoutUserAttachments);
            }
        }

        static class AiVH extends RecyclerView.ViewHolder {
            TextView tvProcessToggle, tvProcessDetail, tvContent, btnCopy;
            LinearLayout layoutAttachments;
            AiVH(View v) {
                super(v);
                tvProcessToggle = v.findViewById(R.id.tvProcessToggle);
                tvProcessDetail = v.findViewById(R.id.tvProcessDetail);
                tvContent = v.findViewById(R.id.tvAiContent);
                btnCopy = v.findViewById(R.id.btnCopyAi);
                layoutAttachments = v.findViewById(R.id.layoutAiAttachments);
            }
        }
    }
}
