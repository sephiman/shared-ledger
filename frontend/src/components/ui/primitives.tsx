import { forwardRef, useEffect, useRef, useState, type ButtonHTMLAttributes, type HTMLAttributes, type InputHTMLAttributes, type LabelHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from "react";
import { cn } from "@/lib/cn";

/** Small non-interactive status/label pill. */
export function Badge({ children, tone = "neutral", className }: { children: ReactNode; tone?: "neutral" | "amber" | "sky" | "green" | "red"; className?: string }) {
  const tones = {
    neutral: "bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-300",
    amber: "bg-amber-100 text-amber-800 dark:bg-amber-900/50 dark:text-amber-200",
    sky: "bg-sky-100 text-sky-800 dark:bg-sky-900/50 dark:text-sky-200",
    green: "bg-emerald-100 text-emerald-800 dark:bg-emerald-900/50 dark:text-emerald-200",
    red: "bg-red-100 text-red-800 dark:bg-red-900/50 dark:text-red-200",
  };
  return <span className={cn("inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-xs font-medium", tones[tone], className)}>{children}</span>;
}

export const Button = forwardRef<HTMLButtonElement, ButtonHTMLAttributes<HTMLButtonElement> & { variant?: "primary" | "secondary" | "danger" | "ghost" }>(
  ({ className, variant = "primary", type = "button", ...props }, ref) => {
    const variants = {
      primary: "bg-primary text-primary-foreground hover:bg-sky-600",
      secondary: "bg-white border border-border text-gray-900 hover:bg-gray-50 dark:bg-gray-700 dark:text-gray-100 dark:border-gray-600 dark:hover:bg-gray-600",
      danger: "bg-red-600 text-white hover:bg-red-700",
      ghost: "bg-transparent text-gray-700 hover:bg-gray-100 dark:text-gray-200 dark:hover:bg-gray-700",
    };
    return (
      <button
        ref={ref}
        type={type}
        className={cn(
          "inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-primary focus:ring-offset-1 disabled:opacity-50 disabled:cursor-not-allowed",
          variants[variant],
          className,
        )}
        {...props}
      />
    );
  },
);
Button.displayName = "Button";

const invalidRing = "border-red-500 ring-1 ring-red-500 focus:border-red-500 focus:ring-red-500 dark:border-red-500";

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement> & { invalid?: boolean }>(
  ({ className, invalid, ...props }, ref) => (
    <input
      ref={ref}
      aria-invalid={invalid || undefined}
      className={cn(
        "block w-full rounded-md border border-border bg-white px-3 py-2 text-sm shadow-sm placeholder:text-gray-400 focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary disabled:bg-gray-100 dark:bg-gray-800 dark:text-gray-100 dark:border-gray-600 dark:placeholder:text-gray-500 dark:disabled:bg-gray-700",
        invalid && invalidRing,
        className,
      )}
      {...props}
    />
  ),
);
Input.displayName = "Input";

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement> & { invalid?: boolean }>(
  ({ className, invalid, ...props }, ref) => (
    <textarea
      ref={ref}
      aria-invalid={invalid || undefined}
      className={cn(
        "block w-full rounded-md border border-border bg-white px-3 py-2 text-sm shadow-sm focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary dark:bg-gray-800 dark:text-gray-100 dark:border-gray-600",
        invalid && invalidRing,
        className,
      )}
      {...props}
    />
  ),
);
Textarea.displayName = "Textarea";

export const Select = forwardRef<HTMLSelectElement, SelectHTMLAttributes<HTMLSelectElement> & { invalid?: boolean }>(
  ({ className, invalid, ...props }, ref) => (
    <select
      ref={ref}
      aria-invalid={invalid || undefined}
      className={cn(
        "block w-full rounded-md border border-border bg-white px-3 py-2 text-sm shadow-sm focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary dark:bg-gray-800 dark:text-gray-100 dark:border-gray-600",
        invalid && invalidRing,
        className,
      )}
      {...props}
    />
  ),
);
Select.displayName = "Select";

export function Card({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("rounded-lg border border-border bg-white shadow-sm dark:bg-gray-800 dark:border-gray-700", className)} {...props} />;
}

export function CardHeader({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("border-b border-border p-4 dark:border-gray-700", className)} {...props} />;
}

export function CardBody({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("p-4", className)} {...props} />;
}

export function Label({ className, ...props }: LabelHTMLAttributes<HTMLLabelElement>) {
  return <label className={cn("block text-sm font-medium text-gray-700 mb-1 dark:text-gray-300", className)} {...props} />;
}

export function FieldError({ message }: { message?: string | null }) {
  if (!message) return null;
  return <p className="mt-1 text-sm text-red-600">{message}</p>;
}

/** Native checkbox with optional tri-state `indeterminate` support and consistent styling. */
export function Checkbox({ indeterminate, className, ...props }: InputHTMLAttributes<HTMLInputElement> & { indeterminate?: boolean }) {
  const ref = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (ref.current) ref.current.indeterminate = Boolean(indeterminate);
  }, [indeterminate]);
  return (
    <input
      ref={ref}
      type="checkbox"
      className={cn(
        "h-4 w-4 shrink-0 rounded border-border text-primary focus:ring-2 focus:ring-primary focus:ring-offset-0 dark:border-gray-600 dark:bg-gray-700",
        className,
      )}
      {...props}
    />
  );
}

export interface CheckboxTreeLeaf {
  value: string;
  label: ReactNode;
  checked: boolean;
}

export interface CheckboxTreeGroup {
  value: string;
  label: ReactNode;
  checked: boolean;
  /** Renders the group checkbox as partially selected (some, but not all, children picked). */
  indeterminate?: boolean;
  children: CheckboxTreeLeaf[];
}

/**
 * Always-visible, scrollable checkbox tree: each group is a header row with a
 * collapse/expand caret and a (possibly tri-state) checkbox; its children are
 * nested beneath with their own checkboxes. Collapse state is managed internally;
 * checked/indeterminate state and toggles are controlled by the caller so the
 * selection semantics stay with the consumer.
 */
export function CheckboxTree({
  groups,
  onToggleGroup,
  onToggleLeaf,
  className,
}: {
  groups: CheckboxTreeGroup[];
  onToggleGroup: (groupValue: string) => void;
  onToggleLeaf: (groupValue: string, leafValue: string) => void;
  className?: string;
}) {
  const [collapsed, setCollapsed] = useState<Set<string>>(() => new Set());
  const toggleCollapse = (value: string) =>
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (next.has(value)) next.delete(value);
      else next.add(value);
      return next;
    });

  return (
    <div
      className={cn(
        "max-h-[28rem] divide-y divide-border overflow-auto rounded-md border border-border bg-white dark:divide-gray-700 dark:bg-gray-800 dark:border-gray-600",
        className,
      )}
    >
      {groups.map((g) => {
        const isCollapsed = collapsed.has(g.value);
        const hasChildren = g.children.length > 0;
        return (
          <div key={g.value}>
            {/* Group header */}
            <div className="flex items-center gap-1 bg-gray-50 px-1.5 py-1.5 dark:bg-gray-800/60">
              {hasChildren ? (
                <button
                  type="button"
                  onClick={() => toggleCollapse(g.value)}
                  aria-expanded={!isCollapsed}
                  aria-label={isCollapsed ? "expand" : "collapse"}
                  className="flex h-5 w-5 shrink-0 items-center justify-center rounded text-xs text-gray-400 hover:text-gray-600 focus:outline-none focus:ring-2 focus:ring-primary dark:hover:text-gray-200"
                >
                  <span className={cn("inline-block transition-transform", isCollapsed ? "" : "rotate-90")}>▸</span>
                </button>
              ) : (
                <span className="h-5 w-5 shrink-0" aria-hidden />
              )}
              <label className="flex flex-1 cursor-pointer items-center gap-2 text-sm font-semibold text-gray-900 dark:text-gray-100">
                <Checkbox checked={g.checked} indeterminate={g.indeterminate} onChange={() => onToggleGroup(g.value)} />
                <span className="truncate">{g.label}</span>
              </label>
            </div>

            {/* Categories nested beneath, with branch connectors */}
            {!isCollapsed && hasChildren && (
              <div className="ml-[1.05rem] py-0.5">
                {g.children.map((c, i) => {
                  const isLast = i === g.children.length - 1;
                  return (
                    <label
                      key={c.value}
                      className="flex cursor-pointer items-stretch rounded text-sm hover:bg-gray-100 dark:hover:bg-gray-700"
                    >
                      <span className="relative w-4 shrink-0" aria-hidden>
                        <span
                          className={cn(
                            "absolute left-1/2 top-0 border-l border-border dark:border-gray-600",
                            isLast ? "h-1/2" : "h-full",
                          )}
                        />
                        <span className="absolute left-1/2 right-0 top-1/2 border-t border-border dark:border-gray-600" />
                      </span>
                      <span className="flex flex-1 items-center gap-2 py-1 pr-1.5">
                        <Checkbox checked={c.checked} onChange={() => onToggleLeaf(g.value, c.value)} />
                        <span className="truncate">{c.label}</span>
                      </span>
                    </label>
                  );
                })}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

/** Labelled on/off row: label on the left, checkbox on the right. */
export function Toggle({ checked, disabled, onChange, label }: { checked: boolean; disabled?: boolean; onChange: (v: boolean) => void; label: ReactNode }) {
  return (
    <label className="flex items-center justify-between gap-3 py-1.5 text-sm">
      <span className={disabled ? "text-gray-400 dark:text-gray-500" : ""}>{label}</span>
      <input
        type="checkbox"
        checked={checked}
        disabled={disabled}
        onChange={(e) => onChange(e.target.checked)}
        className="h-4 w-4 rounded border-border text-primary focus:ring-primary disabled:opacity-50"
      />
    </label>
  );
}

export function Chip({ children, onClick, active }: { children: React.ReactNode; onClick?: () => void; active?: boolean }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        "inline-flex items-center rounded-full border px-3 py-1 text-sm transition-colors",
        active
          ? "border-primary bg-sky-50 text-primary dark:bg-sky-900/40 dark:text-sky-300"
          : "border-border bg-white text-gray-700 hover:bg-gray-50 dark:bg-gray-700 dark:text-gray-200 dark:border-gray-600 dark:hover:bg-gray-600",
      )}
    >
      {children}
    </button>
  );
}
