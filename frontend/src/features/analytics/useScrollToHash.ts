import { useEffect } from "react";
import { useLocation, useNavigate } from "react-router-dom";

const POLL_TIMEOUT_MS = 1500;

export function useScrollToHash() {
  const location = useLocation();
  const navigate = useNavigate();

  useEffect(() => {
    const hash = location.hash.replace(/^#/, "");
    if (!hash) return;

    let rafId = 0;
    let cancelled = false;
    const started = performance.now();
    const prefersReducedMotion =
      typeof window !== "undefined" &&
      window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;

    const tryScroll = () => {
      if (cancelled) return;
      const el = document.getElementById(hash);
      if (el && el.offsetHeight > 0) {
        el.scrollIntoView({
          block: "start",
          behavior: prefersReducedMotion ? "auto" : "smooth",
        });
        navigate(location.pathname + location.search, { replace: true });
        return;
      }
      if (performance.now() - started > POLL_TIMEOUT_MS) return;
      rafId = requestAnimationFrame(tryScroll);
    };

    rafId = requestAnimationFrame(tryScroll);
    return () => {
      cancelled = true;
      cancelAnimationFrame(rafId);
    };
  }, [location.hash, location.pathname, location.search, navigate]);
}
