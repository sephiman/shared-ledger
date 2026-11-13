import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useFirePreview, useFireTemplateSuggestions, type FireCashFlowEvent, type FireProjection } from "@/api/fire";
import { Button } from "@/components/ui/primitives";
import { FireEventTemplatePanel } from "./FireEventTemplatePanel";

function sameEvents(a: FireCashFlowEvent[], b: FireCashFlowEvent[]): boolean {
  return (
    a.length === b.length &&
    a.every((e, i) => e.year === b[i].year && e.kind === b[i].kind && Number(e.amount) === Number(b[i].amount))
  );
}

/** "Add from template": a button until opened, so the closed state needs no data at all. */
export function FireEventTemplates(props: {
  householdId: string;
  projection: FireProjection;
  events: FireCashFlowEvent[];
  currency: string;
  disabled: boolean;
  onAdd: (events: FireCashFlowEvent[]) => void;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  if (!open) {
    return (
      <Button variant="secondary" disabled={props.disabled} onClick={() => setOpen(true)}>
        {t("fire.template_add")}
      </Button>
    );
  }
  return <OpenTemplates {...props} onClose={() => setOpen(false)} />;
}

/** Wires the template panel to the sandbox: "before" is the photo unless the form's events differ from the saved ones. */
function OpenTemplates({
  householdId,
  projection,
  events,
  currency,
  onAdd,
  onClose,
}: {
  householdId: string;
  projection: FireProjection;
  events: FireCashFlowEvent[];
  currency: string;
  onAdd: (events: FireCashFlowEvent[]) => void;
  onClose: () => void;
}) {
  const suggestions = useFireTemplateSuggestions(householdId, true);
  const after = useFirePreview(householdId);
  const before = useFirePreview(householdId);
  const unsavedEdits = !sameEvents(events, projection.settings.cashFlowEvents);

  return (
    <FireEventTemplatePanel
      baseline={projection}
      currentEvents={events}
      suggestions={suggestions.data}
      currency={currency}
      onPreview={(withTemplate) => {
        after.mutate({ cashFlowEvents: withTemplate });
        if (unsavedEdits && !before.data && !before.isPending) before.mutate({ cashFlowEvents: events });
      }}
      before={unsavedEdits ? before.data : projection}
      after={after.data}
      previewPending={after.isPending || (unsavedEdits && before.isPending)}
      onAdd={(added) => {
        onAdd(added);
        onClose();
      }}
      onCancel={onClose}
    />
  );
}
