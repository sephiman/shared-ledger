import { cn } from "@/lib/cn";
import { Select } from "@/components/ui/primitives";

export type TabBarItem = { value: string; label: string };

/** Sticky sub-page tab header: a row of tabs on desktop, collapsing to a dropdown on mobile. */
export function TabBar({
  items,
  value,
  onChange,
  ariaLabel,
}: {
  items: TabBarItem[];
  value: string;
  onChange: (value: string) => void;
  ariaLabel?: string;
}) {
  return (
    <div className="sticky top-0 z-20 -mx-4 border-b border-border bg-canvas px-4">
      <div className="py-2 md:hidden">
        <Select value={value} onChange={(e) => onChange(e.target.value)} aria-label={ariaLabel}>
          {items.map((it) => (
            <option key={it.value} value={it.value}>
              {it.label}
            </option>
          ))}
        </Select>
      </div>
      <div className="hidden gap-1 md:flex">
        {items.map((it) => (
          <button
            key={it.value}
            type="button"
            onClick={() => onChange(it.value)}
            className={cn(
              "whitespace-nowrap px-3 py-2 text-sm font-medium",
              value === it.value
                ? "border-b-2 border-primary text-primary"
                : "text-gray-600 dark:text-gray-300",
            )}
          >
            {it.label}
          </button>
        ))}
      </div>
    </div>
  );
}
