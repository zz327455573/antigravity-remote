import json
import os
import re
import sqlite3
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Optional

import uvicorn
from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Query, Request, Response, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, RedirectResponse
from pydantic import BaseModel

BASE_DIR = Path(__file__).resolve().parent
CONFIG_PATH = BASE_DIR / "config.json"

ANTIGRAVITY_HOME = Path(os.path.expandvars(r"%USERPROFILE%\.gemini\antigravity"))
DAEMON_DIR = ANTIGRAVITY_HOME / "daemon"
BRAIN_DIR = ANTIGRAVITY_HOME / "brain"
CONVERSATIONS_DIR = ANTIGRAVITY_HOME / "conversations"
AGENTAPI_BAT = ANTIGRAVITY_HOME / "bin" / "agentapi.bat"


def load_config() -> Dict[str, Any]:
    with open(CONFIG_PATH, "r", encoding="utf-8") as f:
        return json.load(f)


app = FastAPI(title="反重力遥控网关", version="1.1.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
    expose_headers=["WWW-Authenticate", "Mcp-Session-Id", "*"],
)


@app.middleware("http")
async def _mcp_debug_logger(request: Request, call_next):
    path = request.url.path
    if path.startswith("/mcp") or path.startswith("/.well-known") or path.startswith("/oauth"):
        resp = await call_next(request)
        try:
            log_file = BASE_DIR / "mcp_debug.log"
            ts = time.strftime("%Y-%m-%d %H:%M:%S")
            ua = request.headers.get("user-agent", "")[:80]
            origin = request.headers.get("origin", "")
            has_auth = "yes" if request.headers.get("authorization") else "no"
            line = f"[{ts}] {request.method} {path} -> {resp.status_code} | auth={has_auth} | origin={origin} | ua={ua}\n"
            with open(log_file, "a", encoding="utf-8") as f:
                f.write(line)
        except Exception:
            pass
        return resp
    return await call_next(request)


def verify_token(
    x_auth_token: Optional[str] = Header(default=None),
    token: Optional[str] = Query(default=None),
):
    expected = load_config().get("auth_token", "")
    if not expected:
        return
    provided = x_auth_token or token
    if provided != expected:
        raise HTTPException(status_code=401, detail="访问密码错误")


def get_all_active_hubs() -> List[Dict[str, Any]]:
    hub_files = sorted(
        DAEMON_DIR.glob("hub_*.json"),
        key=lambda p: p.stat().st_mtime,
        reverse=True,
    )
    active: List[Dict[str, Any]] = []
    for hf in hub_files:
        try:
            with open(hf, "r", encoding="utf-8") as f:
                data = json.load(f)
            port = data.get("httpPort")
            if port:
                req = urllib.request.Request(f"http://127.0.0.1:{port}/healthz", method="GET")
                with urllib.request.urlopen(req, timeout=1.5) as resp:
                    if resp.status == 200:
                        data["_hubFile"] = hf.name
                        active.append(data)
        except Exception:
            continue
    return active


def get_active_hub() -> Dict[str, Any]:
    hubs = get_all_active_hubs()
    if not hubs:
        raise HTTPException(status_code=503, detail="未检测到运行中的 Antigravity 服务，请确认桌面端已启动")
    return hubs[0]


def call_ls_rpc_on_hub(hub: Dict[str, Any], method: str, payload: Dict[str, Any]) -> Dict[str, Any]:
    port = hub["httpPort"]
    token = hub["csrfToken"]
    url = f"http://127.0.0.1:{port}/exa.language_server_pb.LanguageServerService/{method}"
    req = urllib.request.Request(
        url,
        method="POST",
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "x-codeium-csrf-token": token,
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=12) as resp:
            raw = resp.read().decode("utf-8")
            return json.loads(raw) if raw.strip() else {}
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        raise HTTPException(status_code=e.code, detail=f"引擎调用失败: {body}")
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"引擎通信异常: {e}")


def call_ls_rpc(method: str, payload: Dict[str, Any]) -> Dict[str, Any]:
    return call_ls_rpc_on_hub(get_active_hub(), method, payload)


def _normalize_win_path(p: str) -> str:
    if not p:
        return ""
    return os.path.normpath(p).rstrip("\\/").lower()


def find_hub_for_project(project_path: Optional[str]) -> Optional[Dict[str, Any]]:
    target = _normalize_win_path(project_path or "")
    if not target:
        return None
    for hub in get_all_active_hubs():
        try:
            live_data = call_ls_rpc_on_hub(hub, "GetAllCascadeTrajectories", {})
            for info in live_data.get("trajectorySummaries", {}).values():
                workspaces = (info.get("trajectoryMetadata") or {}).get("workspaces") or []
                for ws in workspaces:
                    wpath = _normalize_win_path(uri_to_win_path(ws.get("workspaceFolderAbsoluteUri", "")))
                    if wpath and wpath == target:
                        return hub
        except Exception:
            continue
    return None


def run_agentapi(args: List[str], cwd: Optional[str] = None, hub: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    if not AGENTAPI_BAT.exists():
        raise HTTPException(status_code=500, detail=f"未找到 agentapi.bat: {AGENTAPI_BAT}")
    work_dir = cwd if cwd and os.path.isdir(cwd) else str(BASE_DIR)
    env = os.environ.copy()
    try:
        target_hub = hub or get_active_hub()
        env["ANTIGRAVITY_LS_ADDRESS"] = f"127.0.0.1:{target_hub['httpPort']}"
        if target_hub.get("csrfToken"):
            env["ANTIGRAVITY_CSRF_TOKEN"] = target_hub["csrfToken"]
    except HTTPException:
        pass
    proc = subprocess.run(
        ["cmd", "/c", str(AGENTAPI_BAT)] + args,
        cwd=work_dir,
        env=env,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=30,
    )
    out = (proc.stdout or "").strip()
    err = (proc.stderr or "").strip()
    if proc.returncode != 0:
        raise HTTPException(status_code=500, detail=f"agentapi 执行失败: {err or out}")
    try:
        return json.loads(out)
    except Exception:
        return {"raw": out}


def extract_user_prompt(raw: str) -> str:
    m = re.search(r"<USER_REQUEST>\s*(.*?)\s*</USER_REQUEST>", raw, re.DOTALL)
    if m:
        return m.group(1).strip()
    return raw.strip()


def uri_to_win_path(uri: str) -> str:
    if not uri:
        return ""
    if uri.startswith("file:///"):
        p = urllib.parse.unquote(uri[8:])
        return os.path.normpath(p)
    return uri


def extract_db_workspace(db_path: Path) -> str:
    try:
        conn = sqlite3.connect(str(db_path))
        row = conn.execute("SELECT data FROM trajectory_metadata_blob LIMIT 1").fetchone()
        conn.close()
        if row and row[0]:
            m = re.search(rb"file:///[^\x00-\x1f\x7f\"'<>]+", row[0])
            if m:
                uri = m.group(0).decode("utf-8", errors="ignore")
                return uri_to_win_path(uri)
    except Exception:
        pass
    return ""


def read_transcript_summary(cid: str) -> Dict[str, Any]:
    tfile = BRAIN_DIR / cid / ".system_generated" / "logs" / "transcript.jsonl"
    if not tfile.exists():
        return {"title": "", "step_count": 0}
    first_prompt = ""
    count = 0
    try:
        with open(tfile, "r", encoding="utf-8", errors="replace") as f:
            for i, line in enumerate(f):
                if i > 15:
                    break
                line = line.strip()
                if not line:
                    continue
                count += 1
                if not first_prompt:
                    try:
                        obj = json.loads(line)
                        if obj.get("type") == "USER_INPUT":
                            txt = extract_user_prompt(obj.get("content", ""))
                            first_prompt = txt.replace("\n", " ")[:40]
                            break
                    except Exception:
                        pass
    except Exception:
        pass
    return {"title": first_prompt, "step_count": count}


# ---------- Pydantic Models ----------

class CreateProjectReq(BaseModel):
    name: str
    root_path: Optional[str] = None


class CreateConversationReq(BaseModel):
    prompt: str
    title: Optional[str] = None
    model: Optional[str] = None
    project_path: Optional[str] = None


class SendMessageReq(BaseModel):
    content: str
    model: Optional[str] = None


# ---------- Routes ----------

@app.get("/api/ping")
def ping(_: None = Depends(verify_token)):
    hub = get_active_hub()
    return {
        "ok": True,
        "version": hub.get("lsVersion", "unknown"),
        "httpPort": hub.get("httpPort"),
        "serverTime": int(time.time()),
    }


@app.get("/api/models")
def list_models(_: None = Depends(verify_token)):
    try:
        data = call_ls_rpc("GetCascadeModelConfigData", {})
        models = []
        for m in data.get("clientModelConfigs", []):
            label = m.get("label", "")
            mid = m.get("modelOrAlias", {}).get("model", "")
            quota = m.get("quotaInfo", {}).get("remainingFraction", 1.0)
            if label and mid:
                models.append({
                    "id": mid,
                    "name": f"{label} ({int(quota * 100)}%)",
                    "shortName": label,
                    "quota": int(quota * 100),
                })
        if models:
            return {"models": models}
    except Exception:
        pass
    return {
        "models": [
            {"id": "MODEL_PLACEHOLDER_M526", "name": "Gemini 4 Hydrogen (Medium)", "shortName": "Gemini 4 Hydrogen (Medium)", "quota": 100},
            {"id": "MODEL_PLACEHOLDER_M318", "name": "Gemini 3.8 Flash (High)", "shortName": "Gemini 3.8 Flash (High)", "quota": 100},
            {"id": "MODEL_PLACEHOLDER_M418", "name": "Gemini 4 Argon (Medium)", "shortName": "Gemini 4 Argon (Medium)", "quota": 100},
        ]
    }


@app.get("/api/projects")
def list_projects(_: None = Depends(verify_token)):
    cfg = load_config()
    roots = cfg.get("workspace_roots", [])
    projects: List[Dict[str, Any]] = []
    seen = set()

    def add_proj(p_str: str, mtime_val: Optional[int] = None):
        if not p_str:
            return
        p = Path(p_str)
        if not p.exists() or not p.is_dir():
            return
        cpath = str(p.resolve())
        low = cpath.lower()
        if low in seen:
            return
        seen.add(low)
        projects.append({
            "name": p.name or cpath,
            "path": cpath,
            "mtime": mtime_val if mtime_val is not None else int(p.stat().st_mtime),
        })

    # 1. Projects from live trajectories
    try:
        live_data = call_ls_rpc("GetAllCascadeTrajectories", {})
        for info in live_data.get("trajectorySummaries", {}).values():
            workspaces = (info.get("trajectoryMetadata") or {}).get("workspaces") or []
            if workspaces:
                add_proj(uri_to_win_path(workspaces[0].get("workspaceFolderAbsoluteUri", "")))
    except Exception:
        pass

    # 2. Projects discovered in historical conversation DBs
    if CONVERSATIONS_DIR.exists():
        for db_path in CONVERSATIONS_DIR.glob("*.db"):
            ws = extract_db_workspace(db_path)
            if ws:
                add_proj(ws, int(db_path.stat().st_mtime))

    # 3. Direct subdirectories under workspace_roots (e.g. D:\YourWorkspace)
    for root in roots:
        rp = Path(root)
        if rp.exists() and rp.is_dir():
            try:
                for child in rp.iterdir():
                    if child.is_dir() and not child.name.startswith("."):
                        add_proj(str(child))
            except Exception:
                pass

    projects.sort(key=lambda x: x["mtime"], reverse=True)
    return {"projects": projects, "roots": roots}


@app.post("/api/projects")
def create_project(req: CreateProjectReq, _: None = Depends(verify_token)):
    name = req.name.strip().replace("/", "_").replace("\\", "_")
    if not name or name in (".", ".."):
        raise HTTPException(status_code=400, detail="项目名称不合法")
    cfg = load_config()
    roots = cfg.get("workspace_roots", [])
    root = req.root_path or (roots[0] if roots else r"D:\YourWorkspace")
    target = Path(root) / name
    target.mkdir(parents=True, exist_ok=True)
    return {"ok": True, "name": name, "path": str(target.resolve())}


@app.get("/api/conversations")
def list_conversations(_: None = Depends(verify_token)):
    live_data = call_ls_rpc("GetAllCascadeTrajectories", {})
    live_map: Dict[str, Any] = live_data.get("trajectorySummaries", {})

    items: Dict[str, Dict[str, Any]] = {}
    for cid, info in live_map.items():
        meta = info.get("trajectoryMetadata") or {}
        workspaces = meta.get("workspaces") or []
        ws_path = ""
        ws_name = ""
        if workspaces and isinstance(workspaces, list):
            ws_uri = workspaces[0].get("workspaceFolderAbsoluteUri", "")
            ws_path = uri_to_win_path(ws_uri)
            if ws_path:
                ws_name = os.path.basename(ws_path) or ws_path

        items[cid] = {
            "id": cid,
            "title": info.get("summary") or "未命名会话",
            "status": info.get("status", "CASCADE_RUN_STATUS_IDLE"),
            "stepCount": info.get("stepCount", 0),
            "lastModifiedTime": info.get("lastModifiedTime", ""),
            "workspacePath": ws_path,
            "workspaceName": ws_name,
            "sortTs": 0.0,
            "isLive": True,
        }

    # Merge historical conversations from conversations/*.db
    if CONVERSATIONS_DIR.exists():
        for db_path in CONVERSATIONS_DIR.glob("*.db"):
            cid = db_path.stem
            mtime = db_path.stat().st_mtime
            if cid in items:
                items[cid]["sortTs"] = mtime
                continue
            tmeta = read_transcript_summary(cid)
            ws_path = extract_db_workspace(db_path)
            ws_name = os.path.basename(ws_path) if ws_path else ""
            items[cid] = {
                "id": cid,
                "title": tmeta["title"] or f"历史会话 {cid[:8]}",
                "status": "CASCADE_RUN_STATUS_IDLE",
                "stepCount": tmeta["step_count"],
                "lastModifiedTime": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(mtime)),
                "workspacePath": ws_path,
                "workspaceName": ws_name,
                "sortTs": mtime,
                "isLive": False,
            }

    for cid, item in items.items():
        if item["sortTs"] == 0.0:
            tfile = BRAIN_DIR / cid / ".system_generated" / "logs" / "transcript.jsonl"
            if tfile.exists():
                item["sortTs"] = tfile.stat().st_mtime
            else:
                item["sortTs"] = time.time()

    result = sorted(items.values(), key=lambda x: x["sortTs"], reverse=True)
    return {"conversations": result[:80]}


@app.post("/api/conversations")
def create_conversation(req: CreateConversationReq, _: None = Depends(verify_token)):
    prompt = req.prompt.strip()
    if not prompt:
        raise HTTPException(status_code=400, detail="指令内容不能为空")

    target_project = (req.project_path or "").strip()
    matched_hub = find_hub_for_project(target_project) if target_project else None
    if target_project:
        constraint = (
            f"[系统工作区约束：所有文件读写、检索与终端命令必须在 {target_project} 下执行，"
            f"禁止操作默认安装目录，无需单独回复确认，请直接执行以下任务] "
        )
        if not prompt.startswith("[系统工作区约束"):
            prompt = constraint + prompt

    # Windows cmd /c 传参遇到换行符会截断后续内容，统一折叠为单行空格
    prompt = " ".join(prompt.splitlines()).strip()

    args = ["new-conversation"]
    if req.model:
        args.append(f"--model={req.model}")
    if req.title:
        args.append(f"--title={req.title}")
    args.append(prompt)

    # If project_path is empty (standalone conversation), run in ANTIGRAVITY_HOME/scratch to keep outside workspace
    cwd = target_project if target_project and os.path.isdir(target_project) else str(ANTIGRAVITY_HOME / "scratch")
    os.makedirs(cwd, exist_ok=True)
    res = run_agentapi(args, cwd=cwd, hub=matched_hub)
    conv_id = res.get("response", {}).get("newConversation", {}).get("conversationId", "")
    return {
        "ok": True,
        "conversationId": conv_id,
        "routedHubPort": matched_hub.get("httpPort") if matched_hub else None,
        "raw": res,
    }


@app.get("/api/health")
def get_server_health(detail: bool = False, _: None = Depends(verify_token)):
    hubs = get_all_active_hubs()
    hub_list: List[Dict[str, Any]] = []
    running_tasks: List[Dict[str, Any]] = []

    for h in hubs:
        workspaces_seen = set()
        try:
            live_data = call_ls_rpc_on_hub(h, "GetAllCascadeTrajectories", {})
            for cid, info in live_data.get("trajectorySummaries", {}).items():
                meta = info.get("trajectoryMetadata") or {}
                for ws in (meta.get("workspaces") or []):
                    wp = uri_to_win_path(ws.get("workspaceFolderAbsoluteUri", ""))
                    if wp:
                        workspaces_seen.add(wp)
                st = str(info.get("status", ""))
                if "RUNNING" in st.upper() or "ACTIVE" in st.upper():
                    running_tasks.append({
                        "conversation_id": cid,
                        "title": info.get("summary") or "未命名会话",
                        "status": st,
                        "hub_port": h.get("httpPort"),
                    })
        except Exception:
            pass

        entry: Dict[str, Any] = {
            "hub_file": h.get("_hubFile", ""),
            "pid": h.get("pid"),
            "http_port": h.get("httpPort"),
            "ls_version": h.get("lsVersion", "unknown"),
            "workspaces": sorted(workspaces_seen),
        }
        if detail:
            entry["https_port"] = h.get("httpsPort")
        hub_list.append(entry)

    return {
        "ok": len(hub_list) > 0,
        "active_hub_count": len(hub_list),
        "running_task_count": len(running_tasks),
        "hubs": hub_list,
        "running_tasks": running_tasks,
    }


@app.post("/api/conversations/{cid}/stop")
def stop_conversation(cid: str, _: None = Depends(verify_token)):
    cid = cid.strip()
    if not cid:
        raise HTTPException(status_code=400, detail="conversation_id 不能为空")

    # 优先在包含该会话的 hub 上调用 CancelCascadeInvocation
    hubs = get_all_active_hubs()
    if not hubs:
        raise HTTPException(status_code=503, detail="未检测到运行中的 Antigravity 服务")

    target_hub = hubs[0]
    for h in hubs:
        try:
            live_data = call_ls_rpc_on_hub(h, "GetAllCascadeTrajectories", {})
            if cid in live_data.get("trajectorySummaries", {}):
                target_hub = h
                break
        except Exception:
            continue

    call_ls_rpc_on_hub(target_hub, "CancelCascadeInvocation", {"cascadeId": cid})

    # 确认最新状态
    new_status = "UNKNOWN"
    try:
        live_data = call_ls_rpc_on_hub(target_hub, "GetAllCascadeTrajectories", {})
        info = live_data.get("trajectorySummaries", {}).get(cid)
        if info:
            new_status = info.get("status", "UNKNOWN")
    except Exception:
        pass

    return {
        "ok": True,
        "conversation_id": cid,
        "status": new_status,
        "hub_port": target_hub.get("httpPort"),
    }


@app.get("/api/conversations/{cid}/messages")
def get_conversation_messages(cid: str, _: None = Depends(verify_token)):
    status = "CASCADE_RUN_STATUS_IDLE"
    title = ""
    workspace = ""
    try:
        live_data = call_ls_rpc("GetAllCascadeTrajectories", {})
        info = live_data.get("trajectorySummaries", {}).get(cid)
        if info:
            status = info.get("status", status)
            title = info.get("summary", "")
            workspaces = (info.get("trajectoryMetadata") or {}).get("workspaces") or []
            if workspaces:
                workspace = uri_to_win_path(workspaces[0].get("workspaceFolderAbsoluteUri", ""))
    except Exception:
        pass

    tfile = BRAIN_DIR / cid / ".system_generated" / "logs" / "transcript.jsonl"
    messages: List[Dict[str, Any]] = []
    if tfile.exists():
        with open(tfile, "r", encoding="utf-8", errors="replace") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    step = json.loads(line)
                except Exception:
                    continue
                stype = step.get("type", "")
                if stype == "USER_INPUT":
                    txt = extract_user_prompt(step.get("content", ""))
                    if txt:
                        messages.append({
                            "role": "user",
                            "content": txt,
                            "thinking": "",
                            "tools": [],
                            "status": step.get("status", "DONE"),
                            "createdAt": step.get("createdAt") or step.get("created_at", ""),
                        })
                elif stype == "SYSTEM_MESSAGE":
                    raw_sys = step.get("content", "")
                    # 过滤 Antigravity 系统内部的后台任务/服务器通知，不当作用户消息展示
                    if (
                        "Task id \"" in raw_sys
                        or "All your subagents and background tasks" in raw_sys
                        or "sender=system " in raw_sys
                        or "/task-" in raw_sys
                    ):
                        continue
                    if "sender=" in raw_sys and "content=" in raw_sys:
                        m = re.search(r"content=(.*)$", raw_sys, re.DOTALL)
                        if m:
                            msg_txt = m.group(1).replace("</SYSTEM_MESSAGE>", "").strip()
                            if msg_txt:
                                messages.append({
                                    "role": "user",
                                    "content": msg_txt,
                                    "thinking": "",
                                    "tools": [],
                                    "status": step.get("status", "DONE"),
                                    "createdAt": step.get("createdAt") or step.get("created_at", ""),
                                })
                elif stype == "PLANNER_RESPONSE":
                    content = (step.get("content") or "").strip()
                    thinking = (step.get("thinking") or "").strip()
                    tool_calls = step.get("toolCalls") or step.get("tool_calls") or []
                    tools = [tc.get("name", "") for tc in tool_calls if isinstance(tc, dict) and tc.get("name")]
                    if content or tools or step.get("status") != "DONE":
                        messages.append({
                            "role": "assistant",
                            "content": content,
                            "thinking": thinking,
                            "tools": tools,
                            "status": step.get("status", "DONE"),
                            "createdAt": step.get("createdAt") or step.get("created_at", ""),
                        })

    return {
        "conversationId": cid,
        "title": title,
        "status": status,
        "workspace": workspace,
        "messages": messages,
    }


@app.post("/api/conversations/{cid}/messages")
def send_message(cid: str, req: SendMessageReq, _: None = Depends(verify_token)):
    content = req.content.strip()
    if not content:
        raise HTTPException(status_code=400, detail="消息不能为空")

    payload: Dict[str, Any] = {
        "cascadeId": cid,
        "items": [{"text": content}],
    }
    if req.model:
        payload["cascadeConfig"] = {
            "plannerConfig": {
                "requestedModel": {"model": req.model}
            }
        }
    try:
        call_ls_rpc("SendUserCascadeMessage", payload)
        return {"ok": True, "method": "SendUserCascadeMessage"}
    except HTTPException:
        # Fallback to agentapi if native RPC rejects
        res = run_agentapi(["send-message", cid, content])
        return {"ok": True, "method": "agentapi", "raw": res}


@app.post("/api/upload")
async def upload_file(
    file: UploadFile = File(...),
    project_path: Optional[str] = Form(default=None),
    target_dir: Optional[str] = Form(default=None),
    _: None = Depends(verify_token),
):
    cfg = load_config()
    roots = cfg.get("workspace_roots", [])
    if target_dir and os.path.isdir(target_dir):
        upload_dir = Path(target_dir)
    else:
        base = Path(project_path) if project_path and os.path.isdir(project_path) else Path(roots[0] if roots else r"D:\YourWorkspace")
        upload_dir = base / "_mobile_uploads"
    upload_dir.mkdir(parents=True, exist_ok=True)

    safe_name = os.path.basename(file.filename or f"upload_{int(time.time())}")
    target = upload_dir / safe_name
    if target.exists():
        stem, suffix = os.path.splitext(safe_name)
        target = upload_dir / f"{stem}_{int(time.time())}{suffix}"

    data_written = 0
    with open(target, "wb") as f:
        while True:
            chunk = await file.read(1024 * 256)
            if not chunk:
                break
            f.write(chunk)
            data_written += len(chunk)

    return {
        "ok": True,
        "filename": target.name,
        "path": str(target.resolve()),
        "size": data_written,
    }


@app.get("/api/drives")
def list_drives(_: None = Depends(verify_token)):
    shortcuts: List[Dict[str, str]] = []
    user_home = Path(os.path.expanduser("~"))
    for label, p in [
        ("🖥 云电脑桌面", user_home / "Desktop"),
        ("📥 下载目录", user_home / "Downloads"),
        ("📄 文档目录", user_home / "Documents"),
        ("📁 Workspace", Path(r"D:\YourWorkspace")),
    ]:
        if p.exists() and p.is_dir():
            shortcuts.append({"name": label, "path": str(p.resolve())})

    import string
    for letter in string.ascii_uppercase:
        drive = f"{letter}:\\"
        if os.path.exists(drive):
            shortcuts.append({"name": f"💾 本地磁盘 ({letter}:)", "path": drive})

    return {"shortcuts": shortcuts}


@app.get("/api/files")
def list_files(path: str = Query(...), _: None = Depends(verify_token)):
    p = Path(path)
    if not p.exists() or not p.is_dir():
        raise HTTPException(status_code=404, detail="目录不存在")
    resolved = p.resolve()
    parent_str = str(resolved.parent) if resolved.parent != resolved else ""
    entries = []
    try:
        children = sorted(resolved.iterdir(), key=lambda x: (not x.is_dir(), x.name.lower()))
    except PermissionError:
        raise HTTPException(status_code=403, detail="无权访问该系统目录")
    for child in children:
        if child.name.startswith(".") or child.name in ("$RECYCLE.BIN", "System Volume Information"):
            continue
        try:
            size = child.stat().st_size if child.is_file() else 0
            entries.append({
                "name": child.name,
                "path": str(child.resolve()),
                "isDir": child.is_dir(),
                "size": size,
            })
        except Exception:
            continue
    return {"path": str(resolved), "parent": parent_str, "entries": entries[:300]}


@app.get("/api/files/download")
def download_file(path: str = Query(...), _: None = Depends(verify_token)):
    p = Path(path)
    if not p.exists() or not p.is_file():
        raise HTTPException(status_code=404, detail="文件不存在")
    return FileResponse(path=str(p), filename=p.name)


@app.get("/gso")
def gso_root_redirect():
    return RedirectResponse(url="/gso/")


@app.api_route("/gso/{subpath:path}", methods=["GET", "POST"])
async def gso_proxy(subpath: str, request: Request):
    target_path = subpath if subpath else "mobile"
    url = f"http://127.0.0.1:8788/{target_path}"
    if request.url.query:
        url += f"?{request.url.query}"
    body = await request.body()
    headers = {"Content-Type": request.headers.get("content-type", "application/json")}
    req = urllib.request.Request(url, data=(body if body else None), method=request.method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=65) as resp:
            return Response(content=resp.read(), status_code=resp.status, media_type=resp.headers.get_content_type())
    except urllib.error.HTTPError as e:
        return Response(content=e.read(), status_code=e.code)
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"GSO 服务未启动或异常: {e}")


if __name__ == "__main__":
    import sys
    try:
        with open(BASE_DIR / "gateway.pid", "w", encoding="utf-8") as pf:
            pf.write(str(os.getpid()))
    except Exception:
        pass
    if sys.stdout is None or sys.stderr is None:
        log_path = BASE_DIR / "gateway.log"
        log_f = open(log_path, "a", encoding="utf-8", buffering=1)
        if sys.stdout is None:
            sys.stdout = log_f
        if sys.stderr is None:
            sys.stderr = log_f
    cfg = load_config()
    uvicorn.run(
        app,
        host=cfg.get("host", "0.0.0.0"),
        port=int(cfg.get("port", 18900)),
        log_config=None,
    )
