import { useLocation, useNavigationType } from "react-router-dom";

/** Renders where the router is and how it got there, so a test can assert on the URL a click produced. */
export function LocationProbe() {
  const location = useLocation();
  const navigationType = useNavigationType();
  return (
    <>
      <output data-testid="location">{`${location.pathname}${location.search}`}</output>
      <output data-testid="navigation">{navigationType}</output>
    </>
  );
}

/** The control next to a visible field label; the app's labels sit beside their inputs rather than naming them. */
export function fieldByLabel(label: string, index = 0): HTMLSelectElement | HTMLInputElement {
  const labels = Array.from(document.querySelectorAll("label")).filter((l) => l.textContent === label);
  const control = labels[index]?.parentElement?.querySelector("select, input");
  if (!control) throw new Error(`no control labelled "${label}"`);
  return control as HTMLSelectElement | HTMLInputElement;
}
