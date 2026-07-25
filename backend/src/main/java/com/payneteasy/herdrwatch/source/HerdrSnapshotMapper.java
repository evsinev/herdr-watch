package com.payneteasy.herdrwatch.source;

import com.payneteasy.herdrwatch.model.Model.AgentInfo;
import com.payneteasy.herdrwatch.model.Model.WorkspaceInfo;
import com.payneteasy.herdrwatch.model.Model.WorktreeInfo;
import com.payneteasy.herdrwatch.source.HerdrProtocol.AgentRec;
import com.payneteasy.herdrwatch.source.HerdrProtocol.WorkspaceRec;
import com.payneteasy.herdrwatch.source.HerdrProtocol.WorktreeRec;

import java.util.List;

/**
 * Маппинг DTO herdr socket API ({@link HerdrProtocol}) в доменную модель — record→record,
 * без обхода JSON-дерева.
 */
final class HerdrSnapshotMapper {

    private HerdrSnapshotMapper() {}

    /** WorkspaceRec (+ уже собранные worktree'ы этого воркспейса) → WorkspaceInfo. */
    static WorkspaceInfo toWorkspace(WorkspaceRec w, List<WorktreeInfo> worktrees) {
        return new WorkspaceInfo(
                w.workspaceId(),
                w.label(),
                w.number(),
                w.agentStatus(),
                w.focused(),
                w.paneCount(),
                w.tabCount(),
                worktrees != null ? worktrees : List.of()
        );
    }

    /** AgentRec → AgentInfo. Подпись: terminal_title_stripped, иначе pane_id. */
    static AgentInfo toAgent(AgentRec a) {
        String stripped = a.terminalTitleStripped();
        String title = (stripped != null && !stripped.isBlank()) ? stripped : a.paneId();
        return new AgentInfo(
                title,
                a.agent(),
                a.agentStatus(),
                a.workspaceId(),
                a.tabId(),
                a.paneId(),
                a.focused(),
                a.cwd()
        );
    }

    /** WorktreeRec → WorktreeInfo. */
    static WorktreeInfo toWorktree(WorktreeRec t) {
        return new WorktreeInfo(
                t.branch(),
                t.path(),
                t.label(),
                t.isDetached(),
                t.isPrunable(),
                t.isLinkedWorktree(),
                t.openWorkspaceId()
        );
    }
}
