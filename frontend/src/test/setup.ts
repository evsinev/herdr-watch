import "@testing-library/jest-dom/vitest";

// jsdom lacks ResizeObserver (used by CompactView); provide a no-op stub so
// components that observe elements can render in tests.
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver = globalThis.ResizeObserver ?? (ResizeObserverStub as unknown as typeof ResizeObserver);
