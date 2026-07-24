import { describe, it, expect } from "vitest";
import { statusOf, healthOf, hex, badgeStyle, STATUS } from "@/lib/theme";

describe("theme", () => {
  it("statusOf lowercases input and falls back to unknown", () => {
    expect(statusOf("BLOCKED").priority).toBe(5);
    expect(statusOf("blocked").color).toBe("#E24B4A");
    expect(statusOf(null)).toBe(STATUS.unknown);
    expect(statusOf("nope")).toBe(STATUS.unknown);
  });

  it("healthOf maps uppercase health names, defaults to unreachable", () => {
    expect(healthOf("CONNECTED").label).toBe("connected");
    expect(healthOf(null).label).toBe("unreachable");
  });

  it("hex converts #rrggbb + alpha to rgba()", () => {
    expect(hex("#378ADD", 0.1)).toBe("rgba(55, 138, 221, 0.1)");
  });

  it("badgeStyle builds background/border from a color", () => {
    const s = badgeStyle("#639922");
    expect(s.color).toBe("#639922");
    expect(s.background).toBe(hex("#639922", 0.1));
    expect(s.border).toBe(`1px solid ${hex("#639922", 0.28)}`);
  });
});
