import { describe, it, expect, beforeEach } from "vitest";
import { loadCompactLabel, saveCompactLabel } from "@/lib/prefs";

describe("prefs", () => {
  beforeEach(() => localStorage.clear());

  it("defaults to 'project'", () => {
    expect(loadCompactLabel()).toBe("project");
  });

  it("roundtrips a saved value through localStorage", () => {
    saveCompactLabel("both");
    expect(loadCompactLabel()).toBe("both");
  });

  it("ignores an invalid stored value", () => {
    localStorage.setItem("herdr-watch.compactLabel", "garbage");
    expect(loadCompactLabel()).toBe("project");
  });
});
