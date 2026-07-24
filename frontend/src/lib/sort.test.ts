import { describe, it, expect } from "vitest";
import { hostRollupPriority, sortHosts, compactCards } from "@/lib/sort";
import type { HostState, AgentInfo, WorkspaceInfo } from "@/lib/types";

function agent(p: Partial<AgentInfo>): AgentInfo {
  return {
    title: null, kind: null, status: null, workspaceId: null,
    tabId: null, paneId: null, focused: false, cwd: null, ...p,
  };
}
function ws(p: Partial<WorkspaceInfo>): WorkspaceInfo {
  return {
    id: "w1", label: null, number: null, agentStatus: null,
    focused: false, paneCount: 0, tabCount: 0, worktrees: [], ...p,
  };
}
function host(p: Partial<HostState>): HostState {
  return { id: "h", host: "h", health: "CONNECTED", lastUpdate: null, workspaces: [], agents: [], ...p };
}

describe("sort", () => {
  it("hostRollupPriority = max status priority across agents", () => {
    const h = host({ agents: [agent({ status: "idle" }), agent({ status: "blocked" })] });
    expect(hostRollupPriority(h)).toBe(5); // blocked
    expect(hostRollupPriority(host({}))).toBe(0); // no states
  });

  it("sortHosts: blocked host first, UNREACHABLE last", () => {
    const down = host({ id: "down", health: "UNREACHABLE" });
    const idle = host({ id: "idle", agents: [agent({ status: "idle" })] });
    const blocked = host({ id: "blk", agents: [agent({ status: "blocked" })] });
    expect(sortHosts([down, idle, blocked]).map((h) => h.id)).toEqual(["blk", "idle", "down"]);
  });

  it("compactCards: project=workspace label, blocked first, dims unreachable", () => {
    const h = host({
      id: "m3",
      workspaces: [ws({ id: "wF", label: "herdr-watch" })],
      agents: [
        agent({ status: "idle", title: "t-idle", workspaceId: "wF", paneId: "p1" }),
        agent({ status: "blocked", title: "t-blk", workspaceId: "wF", paneId: "p2" }),
      ],
    });
    const cards = compactCards([h]);
    expect(cards[0].project).toBe("herdr-watch"); // workspace label, NOT the worktree/title
    expect(cards[0].task).toBe("t-blk");
    expect(cards[0].priority).toBe(5); // blocked sorted first
    expect(cards[0].opacity).toBe(1);

    const down = host({ id: "d", health: "UNREACHABLE", agents: [agent({ status: "idle", paneId: "x" })] });
    expect(compactCards([down])[0].opacity).toBe(0.6);
  });
});
