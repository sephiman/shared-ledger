import {
  useCallback,
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type FocusEvent,
  type PointerEvent,
  type ReactNode,
} from "react";
import { createPortal } from "react-dom";
import { cn } from "@/lib/cn";

const PANEL_GAP = 4;
const VIEWPORT_PADDING = 8;

type Placement = "bottom" | "top";

interface PanelPosition {
  wrapperTop: number;
  left: number;
  placement: Placement;
}

function computePanelPosition(
  triggerRect: DOMRect,
  panelRect: DOMRect,
  preferLeftAlign: boolean,
): PanelPosition {
  const vw = document.documentElement.clientWidth || window.innerWidth;
  const vh = document.documentElement.clientHeight || window.innerHeight;

  const spaceBelow = vh - VIEWPORT_PADDING - (triggerRect.bottom + PANEL_GAP);
  const spaceAbove = triggerRect.top - PANEL_GAP - VIEWPORT_PADDING;
  const fitsBelow = panelRect.height <= spaceBelow;
  const placement: Placement =
    !fitsBelow && (spaceAbove >= panelRect.height || spaceAbove > spaceBelow) ? "top" : "bottom";

  const rawTop =
    placement === "bottom"
      ? triggerRect.bottom + PANEL_GAP
      : triggerRect.top - PANEL_GAP - panelRect.height;
  const maxTop = Math.max(VIEWPORT_PADDING, vh - VIEWPORT_PADDING - panelRect.height);
  const clampedTop = Math.min(Math.max(rawTop, VIEWPORT_PADDING), maxTop);
  // Wrapper touches the trigger with 0px gap (using padding for visual separation) so mouse hover can
  // cross from trigger into the tooltip without passing through the page underneath.
  const wrapperTop = placement === "bottom" ? clampedTop - PANEL_GAP : clampedTop;

  const rightAligned = triggerRect.right - panelRect.width;
  const leftAligned = triggerRect.left;
  let left = preferLeftAlign ? leftAligned : rightAligned;
  if (preferLeftAlign && left + panelRect.width > vw - VIEWPORT_PADDING && rightAligned >= VIEWPORT_PADDING) {
    left = rightAligned;
  } else if (!preferLeftAlign && left < VIEWPORT_PADDING && leftAligned + panelRect.width <= vw - VIEWPORT_PADDING) {
    left = leftAligned;
  }
  const maxLeft = Math.max(VIEWPORT_PADDING, vw - VIEWPORT_PADDING - panelRect.width);
  left = Math.min(Math.max(left, VIEWPORT_PADDING), maxLeft);

  return { wrapperTop, left, placement };
}

/** Small ⓘ affordance revealing a short explanation in a viewport-clamped portal above page content. */
export function InfoTip({
  label,
  children,
  className,
  anchor = "icon",
  trigger,
  openOnHover = false,
  panelClassName,
}: {
  label: string;
  children: ReactNode;
  className?: string;
  anchor?: "icon" | "container";
  trigger?: ReactNode;
  openOnHover?: boolean;
  panelClassName?: string;
}) {
  const [pinnedOpen, setPinnedOpen] = useState(false);
  const [hoverOpen, setHoverOpen] = useState(false);
  const [pos, setPos] = useState<PanelPosition | null>(null);
  const open = pinnedOpen || hoverOpen;

  const tooltipId = useId();
  const triggerRef = useRef<HTMLButtonElement>(null);
  const wrapperRef = useRef<HTMLDivElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const pointerDownAtRef = useRef(0);

  const closeAll = useCallback(() => {
    setPinnedOpen(false);
    setHoverOpen(false);
  }, []);

  const updatePosition = useCallback(() => {
    if (!triggerRef.current || !panelRef.current) return;
    setPos(
      computePanelPosition(
        triggerRef.current.getBoundingClientRect(),
        panelRef.current.getBoundingClientRect(),
        anchor === "container",
      ),
    );
  }, [anchor]);

  useLayoutEffect(() => {
    if (!open) {
      setPos(null);
      return;
    }
    updatePosition();
    window.addEventListener("resize", updatePosition);
    window.addEventListener("scroll", updatePosition, true);
    return () => {
      window.removeEventListener("resize", updatePosition);
      window.removeEventListener("scroll", updatePosition, true);
    };
  }, [open, updatePosition]);

  useEffect(() => {
    if (!open) return;
    const onPointerDown = (e: globalThis.PointerEvent) => {
      const target = e.target;
      if (
        target instanceof Node &&
        (triggerRef.current?.contains(target) || wrapperRef.current?.contains(target))
      ) {
        return;
      }
      closeAll();
    };
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") closeAll();
    };
    document.addEventListener("pointerdown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("pointerdown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [open, closeAll]);

  // Distinguish pointer presses from keyboard focus so a click/tap (including delayed touch
  // compatibility focus/click events) does not open on focus and immediately toggle shut on click.
  const handleTriggerPointerDown = () => {
    pointerDownAtRef.current = Date.now();
  };

  const handleTriggerClick = () => {
    pointerDownAtRef.current = 0;
    if (pinnedOpen) {
      closeAll();
    } else {
      setPinnedOpen(true);
    }
  };

  const handleTriggerFocus = () => {
    if (Date.now() - pointerDownAtRef.current < 500) return;
    setPinnedOpen(true);
  };

  const handleBlur = (e: FocusEvent<HTMLElement>) => {
    const next = e.relatedTarget;
    if (
      next instanceof Node &&
      (triggerRef.current?.contains(next) || wrapperRef.current?.contains(next))
    ) {
      return;
    }
    closeAll();
  };

  const handlePointerEnter = (e: PointerEvent) => {
    if (openOnHover && e.pointerType === "mouse") setHoverOpen(true);
  };

  const handlePointerLeave = (e: PointerEvent) => {
    if (!openOnHover || e.pointerType !== "mouse") return;
    const next = e.relatedTarget;
    if (
      next instanceof Node &&
      (triggerRef.current?.contains(next) || wrapperRef.current?.contains(next))
    ) {
      return;
    }
    setHoverOpen(false);
  };

  return (
    <span className={cn("inline-block align-middle", className)}>
      <button
        ref={triggerRef}
        type="button"
        aria-label={label}
        aria-expanded={open}
        aria-describedby={open ? tooltipId : undefined}
        onPointerDown={handleTriggerPointerDown}
        onClick={handleTriggerClick}
        onFocus={handleTriggerFocus}
        onBlur={handleBlur}
        onPointerEnter={handlePointerEnter}
        onPointerLeave={handlePointerLeave}
        className={cn(
          "inline-flex cursor-pointer select-none items-center rounded-full leading-none outline-none focus-visible:ring-2 focus-visible:ring-primary",
          !trigger && "text-gray-400 hover:text-gray-600 dark:text-gray-500 dark:hover:text-gray-300",
        )}
      >
        {trigger ?? <span aria-hidden="true">ⓘ</span>}
      </button>
      {open &&
        createPortal(
          <div
            ref={wrapperRef}
            onPointerEnter={handlePointerEnter}
            onPointerLeave={handlePointerLeave}
            style={
              pos
                ? {
                    top: pos.wrapperTop,
                    left: pos.left,
                    paddingTop: pos.placement === "bottom" ? PANEL_GAP : 0,
                    paddingBottom: pos.placement === "top" ? PANEL_GAP : 0,
                  }
                : { top: 0, left: 0, visibility: "hidden" }
            }
            className="fixed z-popover"
          >
            <div
              ref={panelRef}
              id={tooltipId}
              role="tooltip"
              tabIndex={-1}
              onBlur={handleBlur}
              className={cn(
                "w-64 max-w-[min(16rem,calc(100vw-1rem))] rounded-md border border-border-strong bg-overlay p-2 text-left text-xs font-normal leading-snug text-gray-600 shadow-lg outline-none dark:text-gray-300",
                panelClassName,
              )}
            >
              {children}
            </div>
          </div>,
          document.body,
        )}
    </span>
  );
}
