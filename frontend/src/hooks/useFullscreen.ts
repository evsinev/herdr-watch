import { useCallback, useEffect, useState } from "react";

/**
 * Browser Fullscreen API wrapper.
 * - `isSupported` — whether fullscreen is available AND allowed in this context.
 *   `document.fullscreenEnabled` is false both when the API is missing and when the
 *   page context / permissions-policy forbids it — that's the "why we can't switch".
 * - `isFullscreen` — tracks the real state (also updates on Esc via `fullscreenchange`).
 * - `toggle` — enter/exit; must be called from a user gesture (a click).
 */
export function useFullscreen() {
  const isSupported =
    typeof document !== "undefined" &&
    document.fullscreenEnabled === true &&
    typeof document.documentElement.requestFullscreen === "function";

  const [isFullscreen, setIsFullscreen] = useState(
    () => typeof document !== "undefined" && !!document.fullscreenElement,
  );

  useEffect(() => {
    const onChange = () => setIsFullscreen(!!document.fullscreenElement);
    document.addEventListener("fullscreenchange", onChange);
    return () => document.removeEventListener("fullscreenchange", onChange);
  }, []);

  const toggle = useCallback(() => {
    if (document.fullscreenElement) {
      void document.exitFullscreen().catch(() => {});
    } else {
      void document.documentElement.requestFullscreen().catch(() => {});
    }
  }, []);

  return { isSupported, isFullscreen, toggle };
}
