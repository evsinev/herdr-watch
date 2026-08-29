import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { UsageGauge, UsageTile } from "@/components/UsageGauge";
import type { ClaudeUsage } from "@/lib/types";

function usage(p: Partial<ClaudeUsage> = {}): ClaudeUsage {
  return {
    state: "OK",
    source: "ACCOUNT_API",
    capturedAt: Math.round(Date.now() / 1000) - 120,
    error: null,
    windows: {
      fiveHour: { usedPercent: 10, resetsAt: Math.round(Date.now() / 1000) + 3600 },
      sevenDay: { usedPercent: 34, resetsAt: Math.round(Date.now() / 1000) + 86400 },
    },
    models: [],
    ...p,
  };
}

describe("UsageGauge", () => {
  it("names the source next to the age of the reading", () => {
    render(<UsageGauge usage={usage()} />);
    expect(screen.getByText(/account api · 2m ago/)).toBeTruthy();
  });

  it("says statusline when the figures came from the hook", () => {
    render(<UsageGauge usage={usage({ source: "STATUSLINE" })} />);
    expect(screen.getByText(/statusline · /)).toBeTruthy();
  });

  it("renders a row per model window", () => {
    render(
      <UsageGauge
        usage={usage({
          models: [
            { model: "Fable", usedPercent: 14, resetsAt: 1788206399 },
            { model: "Opus", usedPercent: 7, resetsAt: 1788206399 },
          ],
        })}
      />,
    );
    expect(screen.getByText("Fable")).toBeTruthy();
    expect(screen.getByText("14%")).toBeTruthy();
    expect(screen.getByText("Opus")).toBeTruthy();
  });

  it("carries an unrecognised model name through as received", () => {
    render(
      <UsageGauge
        usage={usage({ models: [{ model: "Мираж-9000", usedPercent: 3, resetsAt: 1788206399 }] })}
      />,
    );
    expect(screen.getByText("Мираж-9000")).toBeTruthy();
  });

  it("renders nothing extra when there are no model windows", () => {
    // push-дефолт обязан выглядеть ровно как раньше: пустой models — это не пустая строка.
    const { container } = render(<UsageGauge usage={usage({ source: "STATUSLINE" })} />);
    expect(container.textContent).not.toContain("% ");
    expect(screen.getByText("10%")).toBeTruthy();
    expect(screen.getByText("34%")).toBeTruthy();
    expect(screen.queryByText("14%")).toBeNull();
  });

  it("shows an unknown source verbatim rather than hiding it", () => {
    render(<UsageGauge usage={usage({ source: "SOME_NEW_SOURCE" })} />);
    expect(screen.getByText(/some new source · /)).toBeTruthy();
  });

  it("renders nothing at all when the quota was never configured", () => {
    const { container } = render(
      <UsageGauge
        usage={usage({ state: "NOT_CONFIGURED", source: "NONE", windows: { fiveHour: null, sevenDay: null } })}
      />,
    );
    expect(container.firstChild).toBeNull();
  });
});

describe("UsageTile (Compact)", () => {
  const tile = (u: ClaudeUsage) => (
    <UsageTile usage={u} full={false} fill={false} nameSize={26} hostSize={12} taskSize={11} />
  );

  it("names the models on one line instead of a list of bars", () => {
    render(
      tile(
        usage({
          models: [
            { model: "Fable", usedPercent: 14, resetsAt: 1788206399 },
            { model: "Opus", usedPercent: 3, resetsAt: 1788206399 },
          ],
        }),
      ),
    );
    expect(screen.getByText("fable 14% · opus 3%")).toBeTruthy();
  });

  it("renders nothing about models when there are none", () => {
    const { container } = render(tile(usage({ source: "STATUSLINE" })));
    expect(container.textContent).not.toContain("%  ");
    expect(screen.getByText("10%")).toBeTruthy();
    expect(container.textContent).not.toContain("fable");
  });

  it("still shows the source next to the age", () => {
    render(tile(usage()));
    expect(screen.getByText(/account api · 2m ago/)).toBeTruthy();
  });
});
