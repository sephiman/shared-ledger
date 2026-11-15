import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { InfoTip } from "./InfoTip";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

function rect(left: number, top: number, width: number, height: number): DOMRect {
  return {
    x: left,
    y: top,
    left,
    top,
    width,
    height,
    right: left + width,
    bottom: top + height,
    toJSON: () => ({}),
  };
}

describe("InfoTip", () => {
  it("renders tooltip content in document.body portal rather than inside a clipped ancestor", async () => {
    const user = userEvent.setup();
    render(
      <div data-testid="clipped" className="overflow-hidden">
        <InfoTip label="Help">
          <p>Tooltip explanation</p>
        </InfoTip>
      </div>,
    );

    const clipped = screen.getByTestId("clipped");
    await user.click(screen.getByRole("button", { name: "Help" }));

    const tooltip = screen.getByRole("tooltip");
    expect(tooltip).toHaveTextContent("Tooltip explanation");
    expect(clipped.contains(tooltip)).toBe(false);
    expect(tooltip.parentElement?.parentElement).toBe(document.body);
    expect(tooltip.parentElement).toHaveClass("fixed", "z-popover");
  });

  it("opens on focus, toggles on tap/click, and closes on Escape, blur, and outside tap", async () => {
    const user = userEvent.setup();
    render(
      <div>
        <InfoTip label="Info">
          <p>Details</p>
        </InfoTip>
        <button type="button">Next control</button>
      </div>,
    );

    const trigger = screen.getByRole("button", { name: "Info" });

    // Keyboard focus opens, Escape closes.
    await user.tab();
    expect(trigger).toHaveFocus();
    expect(screen.getByRole("tooltip")).toHaveTextContent("Details");
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("tooltip")).not.toBeInTheDocument();

    // Tabbing away blurs and closes an open tooltip.
    await user.click(trigger);
    expect(screen.getByRole("tooltip")).toBeInTheDocument();
    await user.tab();
    expect(screen.getByRole("button", { name: "Next control" })).toHaveFocus();
    expect(screen.queryByRole("tooltip")).not.toBeInTheDocument();

    // Outside tap/pointerdown closes.
    await user.click(trigger);
    expect(screen.getByRole("tooltip")).toBeInTheDocument();
    fireEvent.pointerDown(document.body, { pointerType: "touch" });
    expect(screen.queryByRole("tooltip")).not.toBeInTheDocument();
  });

  it("opens on mouse hover when openOnHover is set and ignores touch pointerenter", async () => {
    const user = userEvent.setup();
    render(
      <InfoTip label="Hover info" openOnHover>
        <p>Hover body</p>
      </InfoTip>,
    );

    const trigger = screen.getByRole("button", { name: "Hover info" });

    fireEvent.pointerEnter(trigger, { pointerType: "touch" });
    expect(screen.queryByRole("tooltip")).not.toBeInTheDocument();

    await user.hover(trigger);
    expect(screen.getByRole("tooltip")).toHaveTextContent("Hover body");

    await user.unhover(trigger);
    expect(screen.queryByRole("tooltip")).not.toBeInTheDocument();
  });

  it("flips above and shifts inside the viewport when it would overflow bottom or right/left edges", async () => {
    const user = userEvent.setup();
    vi.spyOn(document.documentElement, "clientWidth", "get").mockReturnValue(390);
    vi.spyOn(document.documentElement, "clientHeight", "get").mockReturnValue(600);

    const origRect = HTMLElement.prototype.getBoundingClientRect;
    vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function (this: HTMLElement) {
      if (this.tagName === "BUTTON") return rect(360, 560, 16, 16);
      if (this.getAttribute("role") === "tooltip") return rect(0, 0, 256, 100);
      return origRect.call(this);
    });

    render(
      <InfoTip label="Edge tip" anchor="container">
        <p>Edge body</p>
      </InfoTip>,
    );

    await user.click(screen.getByRole("button", { name: "Edge tip" }));
    const tooltip = screen.getByRole("tooltip");
    const wrapper = tooltip.parentElement as HTMLElement;
    const top = Number.parseFloat(wrapper.style.top);
    const left = Number.parseFloat(wrapper.style.left);

    // Trigger is at y=560 in a 600px viewport -> must flip above (560 - 4 - 100 = 456).
    expect(top).toBe(456);
    // Trigger is at x=360 with anchor="container" and width=256 -> must shift/flip inside [8, 390 - 8 - 256 = 126].
    expect(left).toBeGreaterThanOrEqual(8);
    expect(left + 256).toBeLessThanOrEqual(390 - 8);
  });
});
