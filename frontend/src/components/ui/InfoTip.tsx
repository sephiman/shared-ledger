import { type ReactNode } from "react";
import { cn } from "@/lib/cn";

/**
 * Small ⓘ affordance that reveals a short explanation. Built on a native <details> — the same
 * disclosure the app uses for "How is this calculated?" lines — so it toggles on tap (mobile,
 * where hover doesn't exist), click and keyboard, with no JS. The panel floats over siblings
 * (cards don't clip), anchored to the icon's right edge so it opens inward and never spills off
 * the right. Re-tap the icon to close.
 */
export function InfoTip({ label, children, className }: { label: string; children: ReactNode; className?: string }) {
  return (
    <details className={cn("relative inline-block align-middle", className)}>
      <summary
        aria-label={label}
        className="inline-flex cursor-pointer select-none list-none items-center rounded-full leading-none text-gray-400 outline-none hover:text-gray-600 focus-visible:ring-2 focus-visible:ring-primary dark:text-gray-500 dark:hover:text-gray-300 [&::-webkit-details-marker]:hidden"
      >
        <span aria-hidden="true">ⓘ</span>
      </summary>
      <div
        role="tooltip"
        className="absolute right-0 z-20 mt-1 w-64 max-w-[min(16rem,80vw)] rounded-md border border-border bg-white p-2 text-left text-xs font-normal leading-snug text-gray-600 shadow-lg dark:border-gray-600 dark:bg-gray-800 dark:text-gray-300"
      >
        {children}
      </div>
    </details>
  );
}
