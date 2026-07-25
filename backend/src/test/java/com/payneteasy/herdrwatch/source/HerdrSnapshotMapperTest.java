package com.payneteasy.herdrwatch.source;

import com.payneteasy.herdrwatch.model.Model.AgentInfo;
import com.payneteasy.herdrwatch.model.Model.WorkspaceInfo;
import com.payneteasy.herdrwatch.model.Model.WorktreeInfo;
import com.payneteasy.herdrwatch.source.HerdrProtocol.Snapshot;
import com.payneteasy.herdrwatch.source.HerdrProtocol.SnapshotResponse;
import com.payneteasy.herdrwatch.source.HerdrProtocol.WorktreeResult;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-тест: реальный JSON herdr socket API (0.7.4, protocol 16) → типизированные DTO
 * ({@link HerdrProtocol}) → доменная Model. Проверяем и десериализацию (snake_case-поля,
 * ignore-unknown), и record→record маппинг, и разбор error-конверта.
 */
class HerdrSnapshotMapperTest {

    // содержимое result.snapshot от session.snapshot (обрезанное, с реальными именами полей;
    // panes/tabs/layouts/focused_* специально присутствуют — должны игнорироваться)
    private static final String SNAPSHOT = """
            {
              "version":"0.7.4","protocol":16,
              "focused_workspace_id":"wA","focused_tab_id":"wA:t1","focused_pane_id":"wA:p1",
              "panes":[{"pane_id":"wA:p1"}], "tabs":[{"tab_id":"wA:t1"}], "layouts":[{"tab_id":"wA:t1"}],
              "workspaces":[
                {"workspace_id":"wA","label":"proj","number":1,"agent_status":"working",
                 "focused":true,"pane_count":2,"tab_count":1,"active_tab_id":"wA:t1"},
                {"workspace_id":"wB","label":"other","number":2,"agent_status":"idle",
                 "focused":false,"pane_count":1,"tab_count":1}
              ],
              "agents":[
                {"agent":"claude","agent_status":"working","cwd":"/x","focused":true,
                 "foreground_cwd":"/x","pane_id":"wA:p1","revision":7,"tab_id":"wA:t1",
                 "terminal_id":"term_1","terminal_title":"✳ fix bug",
                 "terminal_title_stripped":"fix bug","workspace_id":"wA"},
                {"agent":"codex","agent_status":"idle","cwd":"/y","focused":false,
                 "pane_id":"wB:p1","tab_id":"wB:t1","terminal_title_stripped":"","workspace_id":"wB"}
              ]
            }""";

    // result от worktree.list (source игнорируется, is_bare игнорируется)
    private static final String WORKTREE_WA = """
            {"type":"worktree_list","source":{"repo_name":"proj"},
             "worktrees":[
               {"path":"/x","branch":"main","is_bare":false,"is_detached":true,
                "is_prunable":false,"is_linked_worktree":true,"open_workspace_id":"wA","label":"proj"}
             ]}""";

    @Test
    void deserializesAndMapsAgents() throws Exception {
        Snapshot snap = HerdrProtocol.MAPPER.readValue(SNAPSHOT, Snapshot.class);
        List<AgentInfo> agents = snap.agents().stream().map(HerdrSnapshotMapper::toAgent).toList();

        assertEquals(2, agents.size());
        AgentInfo a = agents.get(0);
        assertEquals("fix bug", a.title());   // terminal_title_stripped
        assertEquals("claude", a.kind());
        assertEquals("working", a.status());
        assertEquals("wA", a.workspaceId());
        assertEquals("wA:t1", a.tabId());
        assertEquals("wA:p1", a.paneId());
        assertTrue(a.focused());
        assertEquals("/x", a.cwd());

        // пустой terminal_title_stripped → подпись падает на pane_id
        assertEquals("wB:p1", agents.get(1).title());
    }

    @Test
    void deserializesAndMapsWorktrees() throws Exception {
        WorktreeResult wr = HerdrProtocol.MAPPER.readValue(WORKTREE_WA, WorktreeResult.class);
        List<WorktreeInfo> ws = wr.worktrees().stream().map(HerdrSnapshotMapper::toWorktree).toList();

        assertEquals(1, ws.size());
        WorktreeInfo w = ws.get(0);
        assertEquals("main", w.branch());
        assertEquals("/x", w.path());
        assertEquals("proj", w.label());
        assertTrue(w.detached());     // is_detached:true → проверяет @JsonProperty на boolean is_-поле
        assertFalse(w.prunable());    // is_prunable:false
        assertTrue(w.linked());       // is_linked_worktree:true
        assertEquals("wA", w.openWorkspaceId());
    }

    @Test
    void mapsWorkspacesWithAttachedWorktrees() throws Exception {
        Snapshot snap = HerdrProtocol.MAPPER.readValue(SNAPSHOT, Snapshot.class);
        WorktreeResult wr = HerdrProtocol.MAPPER.readValue(WORKTREE_WA, WorktreeResult.class);
        Map<String, List<WorktreeInfo>> wtByWs =
                Map.of("wA", wr.worktrees().stream().map(HerdrSnapshotMapper::toWorktree).toList());

        List<WorkspaceInfo> wss = snap.workspaces().stream()
                .map(w -> HerdrSnapshotMapper.toWorkspace(w, wtByWs.getOrDefault(w.workspaceId(), List.of())))
                .toList();

        assertEquals(2, wss.size());
        WorkspaceInfo a = wss.get(0);
        assertEquals("wA", a.id());
        assertEquals("proj", a.label());
        assertEquals(Integer.valueOf(1), a.number());
        assertEquals("working", a.agentStatus());
        assertTrue(a.focused());
        assertEquals(2, a.paneCount());
        assertEquals(1, a.tabCount());
        assertEquals(1, a.worktrees().size());
        assertEquals("main", a.worktrees().get(0).branch());

        // без worktree'ов в карте → пустой список, не null
        assertTrue(wss.get(1).worktrees().isEmpty());
    }

    @Test
    void parsesFullSnapshotResponseEnvelope() throws Exception {
        String envelope = "{\"id\":\"hw-1\",\"result\":{\"type\":\"session_snapshot\",\"snapshot\":"
                + SNAPSHOT + "}}";
        SnapshotResponse resp = HerdrProtocol.MAPPER.readValue(envelope, SnapshotResponse.class);
        assertEquals("hw-1", resp.id());
        assertNull(resp.error());
        assertNotNull(resp.result());
        assertEquals(2, resp.result().snapshot().workspaces().size());
        assertEquals(2, resp.result().snapshot().agents().size());
    }

    @Test
    void parsesErrorEnvelope() throws Exception {
        String envelope = "{\"id\":\"\",\"error\":{\"code\":\"invalid_request\",\"message\":\"missing field\"}}";
        SnapshotResponse resp = HerdrProtocol.MAPPER.readValue(envelope, SnapshotResponse.class);
        assertNull(resp.result());
        assertNotNull(resp.error());
        assertEquals("invalid_request", resp.error().code());
        assertEquals("missing field", resp.error().message());
    }
}
