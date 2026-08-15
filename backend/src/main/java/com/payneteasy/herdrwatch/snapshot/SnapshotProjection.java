package com.payneteasy.herdrwatch.snapshot;

import com.payneteasy.herdrwatch.model.Model.AgentInfo;
import com.payneteasy.herdrwatch.model.Model.Health;
import com.payneteasy.herdrwatch.model.Model.HostState;
import com.payneteasy.herdrwatch.model.Model.WorkspaceInfo;
import com.payneteasy.herdrwatch.model.Model.WorktreeInfo;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Чистая проекция состояния {@code Registry} в записи Snapshot API. Без побочных
 * эффектов и без блокирующих вызовов наружу — только чтение уже собранного состояния
 * (§3 контракта).
 *
 * <p>Проецируем сперва в полный профиль {@link SnapshotAgent}, затем при необходимости
 * сужаем до {@code compact}/{@code status}. Профили — отдельные типы (§8), не фильтрация.
 */
public final class SnapshotProjection {

    private SnapshotProjection() {}

    // Лимиты длины строк (§3.4).
    private static final int LIM_HOST = 64;
    private static final int LIM_PROJECT = 64;
    private static final int LIM_BRANCH = 128;
    private static final int LIM_AGENT_NAME = 32;
    private static final int LIM_AGENT_DISPLAY = 80;
    private static final int LIM_WORKTREE_PATH = 256;
    private static final int LIM_WORKTREE_LABEL = 64;
    private static final int SHA_SHORT = 7;

    /** statusCode desc, затем host asc (byte order), затем project asc (byte order) — §3.9. */
    private static final Comparator<SnapshotAgent> ORDER =
            Comparator.comparingInt(SnapshotAgent::statusCode).reversed()
                    .thenComparing(SnapshotAgent::host, SnapshotProjection::cmpBytes)
                    .thenComparing(SnapshotAgent::project, SnapshotProjection::cmpBytes);

    /**
     * Полный, отсортированный список агентов по всем хостам (профиль {@code full}).
     * limit ещё НЕ применён — сортировка идёт до усечения (§3.9).
     */
    public static List<SnapshotAgent> projectFull(List<HostState> hosts) {
        List<SnapshotAgent> out = new ArrayList<>();
        for (HostState h : hosts) {
            boolean hostStale = h.health() != Health.CONNECTED;
            String host = trunc(nz(h.id()), LIM_HOST);
            List<AgentInfo> agents = h.agents();
            if (agents == null) continue;
            for (AgentInfo a : agents) {
                out.add(projectAgent(h, host, hostStale, a));
            }
        }
        out.sort(ORDER);
        return out;
    }

    private static SnapshotAgent projectAgent(HostState h, String host, boolean hostStale, AgentInfo a) {
        WorktreeInfo wt = resolveWorktree(h, a.workspaceId());

        String agentName = trunc(nz(a.kind()), LIM_AGENT_NAME);
        // agentDisplay = копия agentName (§3.6, источник #2): herdr 0.7.4 не даёт display_agent.
        String agentDisplay = trunc(agentName, LIM_AGENT_DISPLAY);

        int statusCode = statusCode(a.status());
        String status = statusName(statusCode);

        boolean detached = wt != null && wt.detached();
        boolean prunable = wt != null && wt.prunable();
        boolean linked = wt != null && wt.linked();
        String worktreePath = truncStart(wt != null ? nz(wt.path()) : "", LIM_WORKTREE_PATH);
        String worktreeLabel = trunc(wt != null ? nz(wt.label()) : "", LIM_WORKTREE_LABEL);

        String branchRaw = wt != null ? nz(wt.branch()) : "";
        String branch = (detached && !branchRaw.isBlank())
                ? firstCodePoints(branchRaw, SHA_SHORT)   // detached HEAD → короткий SHA (§3.4)
                : trunc(branchRaw, LIM_BRANCH);

        String project;
        if (wt != null && !nz(wt.path()).isBlank()) {
            project = basename(wt.path());
        } else {
            project = basename(nz(a.cwd()));
        }
        project = trunc(project, LIM_PROJECT);

        return new SnapshotAgent(
                host, project, branch, agentName, agentDisplay,
                status, statusCode, hostStale,
                worktreePath, worktreeLabel, linked, detached, prunable);
    }

    /** Провенанс агента через его воркспейс: worktree, открытый в этом воркспейсе (§8). */
    private static WorktreeInfo resolveWorktree(HostState h, String workspaceId) {
        if (workspaceId == null || h.workspaces() == null) return null;
        for (WorkspaceInfo w : h.workspaces()) {
            if (workspaceId.equals(w.id())) {
                List<WorktreeInfo> ts = w.worktrees();
                if (ts == null || ts.isEmpty()) return null;
                for (WorktreeInfo t : ts) {
                    if (workspaceId.equals(t.openWorkspaceId())) return t;
                }
                return ts.get(0);
            }
        }
        return null;
    }

    // --- проекции в узкие профили (§3.5) ---

    public static SnapshotAgentCompact toCompact(SnapshotAgent a) {
        return new SnapshotAgentCompact(
                a.host(), a.project(), a.branch(), a.agentName(),
                a.statusCode(), a.hostStale(), a.detachedHead());
    }

    public static SnapshotAgentStatus toStatus(SnapshotAgent a) {
        return new SnapshotAgentStatus(a.host(), a.project(), a.statusCode(), a.hostStale());
    }

    // --- статусы (§3.7) ---

    public static int statusCode(String agentStatus) {
        if (agentStatus == null) return 0;
        switch (agentStatus.toLowerCase()) {
            case "idle": return 1;
            case "working": return 2;
            case "done": return 3;
            case "blocked": return 4;
            default: return 0;   // unknown / нераспознанное
        }
    }

    public static String statusName(int code) {
        switch (code) {
            case 1: return "IDLE";
            case 2: return "WORKING";
            case 3: return "DONE";
            case 4: return "BLOCKED";
            default: return "UNKNOWN";
        }
    }

    // --- строковые утилиты ---

    static String nz(String s) {
        return s == null ? "" : s;
    }

    /** basename пути (учитывает / и \\), без завершающих разделителей. */
    static String basename(String path) {
        if (path == null) return "";
        String p = path;
        while (p.length() > 1 && (p.endsWith("/") || p.endsWith("\\"))) {
            p = p.substring(0, p.length() - 1);
        }
        int i = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        return i >= 0 ? p.substring(i + 1) : p;
    }

    /** Обрезка по границе кодовой точки до max код-поинтов (§3.4). */
    static String trunc(String s, int max) {
        if (s == null) return "";
        int[] cps = s.codePoints().toArray();
        if (cps.length <= max) return s;
        StringBuilder sb = new StringBuilder(max);
        for (int i = 0; i < max; i++) sb.appendCodePoint(cps[i]);
        return sb.toString();
    }

    /** Первые n код-поинтов (для короткого SHA). */
    static String firstCodePoints(String s, int n) {
        return trunc(s, n);
    }

    /**
     * Обрезка worktreePath с НАЧАЛА (§3.4): сохраняем хвост пути, в начало ставим «…».
     * Итоговая длина = max код-поинтов («…» — один код-поинт + (max-1) хвоста).
     */
    static String truncStart(String s, int max) {
        if (s == null) return "";
        int[] cps = s.codePoints().toArray();
        if (cps.length <= max) return s;
        int keep = max - 1;
        StringBuilder sb = new StringBuilder(max);
        sb.append('…');
        for (int i = cps.length - keep; i < cps.length; i++) sb.appendCodePoint(cps[i]);
        return sb.toString();
    }

    /** Лексикографическое сравнение по UTF-8 байтам (byte order, §3.9). */
    static int cmpBytes(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int d = (x[i] & 0xff) - (y[i] & 0xff);
            if (d != 0) return d;
        }
        return x.length - y.length;
    }
}
