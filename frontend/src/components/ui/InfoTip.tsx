import { type ReactNode } from "react";
import { cn } from "@/lib/cn";

/**
 * Small ⓘ affordance that reveals a short explanation. Built on a native <details> — the same
 * disclosure the app uses for "How is this calculated?" lines — so it toggles on tap (mobile,
 * where hover doesn't exist), click and keyboard, with no JS. The panel floats over siblings
 * (cards don't clip), anchored to the icon's right edge so it opens inward and never spills off
 * the right. Re-tap the icon to close.
 *
 * That anchor only works while the icon has a predictable place on its line. For an icon inside
 * flowing prose it doesn't: wrapping moves it, and a translated sentence moves it again, so one
 * width spills off the left and another off the right. `anchor="container"` hands the panel to
 * the nearest positioned ancestor instead — make that the paragraph (add `relative` to it) and
 * the panel opens from the text's own left edge at every width and in every language.
 */
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
          "absolute z-20 mt-1 w-64 max-w-[min(16rem,80vw)] rounded-md border border-border bg-white p-2 text-left text-xs font-normal leading-snug text-gray-600 shadow-lg dark:border-gray-600 dark:bg-gray-800 dark:text-gray-300",
          anchor === "container" ? "left-0" : "right-0",
        )}
      >
        {children}
      </div>
    </details>
  );
}
