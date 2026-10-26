import { useRef, type PointerEvent, type ReactNode } from "react";
import { cn } from "@/lib/cn";

/** Small ⓘ affordance revealing a short explanation. Built on a native <details>, so it toggles on tap,
 *  click and keyboard with no JS; the panel floats over siblings, anchored to the icon's right edge.
 *  That anchor needs the icon to have a predictable place on its line — inside flowing prose it doesn't,
 *  so `anchor="container"` hands the panel to the nearest positioned ancestor (add `relative` to the <p>).
 *  `trigger` replaces the ⓘ (e.g. with a badge); `openOnHover` also opens it while a mouse rests on it;
 *  `panelClassName` adjusts the panel's placement (e.g. per breakpoint) when neither anchor fits. */
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
  const ref = useRef<HTMLDetailsElement>(null);
  // Mouse only: on touch, pointerenter fires right before the tap's toggle and would cancel it out.
  const hover = (open: boolean) => (e: PointerEvent) => {
    if (openOnHover && e.pointerType === "mouse" && ref.current) ref.current.open = open;
  };
  return (
    <details
      ref={ref}
      className={cn("inline-block align-middle", anchor === "icon" && "relative", className)}
      onPointerEnter={hover(true)}
      onPointerLeave={hover(false)}
    >
      <summary
        aria-label={label}
        className={cn(
          "inline-flex cursor-pointer select-none list-none items-center rounded-full leading-none outline-none focus-visible:ring-2 focus-visible:ring-primary [&::-webkit-details-marker]:hidden",
          !trigger && "text-gray-400 hover:text-gray-600 dark:text-gray-500 dark:hover:text-gray-300",
        )}
      >
        {trigger ?? <span aria-hidden="true">ⓘ</span>}
      </summary>
      <div
        role="tooltip"
        className={cn(
          "absolute z-20 mt-1 w-64 max-w-[min(16rem,80vw)] rounded-md border border-border-strong bg-overlay p-2 text-left text-xs font-normal leading-snug text-gray-600 shadow-lg dark:text-gray-300",
          anchor === "container" ? "left-0" : "right-0",
          panelClassName,
        )}
      >
        {children}
      </div>
    </details>
  );
}
