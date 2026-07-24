import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { SummaryBar } from "@/components/SummaryBar";
import type { HostState, AgentInfo } from "@/lib/types";

function agent(status: string): AgentInfo {
  return {
    title: null, kind: null, status, workspaceId: null,
    tabId: null, paneId: null, focused: false, cwd: null,
  };
}
function host(p: Partial<HostState>): HostState {
  return { id: "h", host: "h", health: "CONNECTED", lastUpdate: null, workspaces: [], agents: [], ...p };
}

describe("SummaryBar", () => {
  it("shows host count and only the nonzero chips", () => {
    const hosts: HostState[] = [
      host({ id: "a", agents: [agent("blocked"), agent("working"), agent("idle")] }),
      host({ id: "b", health: "UNREACHABLE" }),
    ];
    render(<SummaryBar hosts={hosts} />);

    expect(screen.getByText("2")).toBeInTheDocument(); // host count (chips are all "1")
    expect(screen.getByText("down")).toBeInTheDocument(); // 1 unreachable
    expect(screen.getByText("blocked")).toBeInTheDocument();
    expect(screen.getByText("working")).toBeInTheDocument();
    expect(screen.queryByText("idle")).toBeNull(); // idle is not a chip
  });
});
