import { type ReactNode } from "react";
import { cn } from "@/lib/cn";

/** Small ⓘ affordance revealing a short explanation. Built on a native <details>, so it toggles on tap,
 *  click and keyboard with no JS; the panel floats over siblings, anchored to the icon's right edge.
 *  That anchor needs the icon to have a predictable place on its line — inside flowing prose it doesn't,
 *  so `anchor="container"` hands the panel to the nearest positioned ancestor (add `relative` to the <p>). */
export function InfoTip({
  label,
  children,
  className,
  anchor = "icon",
}: {
  label: string;
  children: ReactNode;
  className?: string;
  anchor?: "icon" | "container";
}) {
  return (
    <details className={cn("inline-block align-middle", anchor === "icon" && "relative", className)}>
      <summary
        aria-label={label}
        className="inline-flex cursor-pointer select-none list-none items-center rounded-full leading-none text-gray-400 outline-none hover:text-gray-600 focus-visible:ring-2 focus-visible:ring-primary dark:text-gray-500 dark:hover:text-gray-300 [&::-webkit-details-marker]:hidden"
      >
        <span aria-hidden="true">ⓘ</span>
      </summary>
      <div
        role="tooltip"
        className={cn(
          "absolute z-20 mt-1 w-64 max-w-[min(16rem,80vw)] rounded-md border border-border-strong bg-overlay p-2 text-left text-xs font-normal leading-snug text-gray-600 shadow-lg dark:text-gray-300",
          anchor === "container" ? "left-0" : "right-0",
        )}
      >
        {children}
      </div>
    </details>
  );
}
