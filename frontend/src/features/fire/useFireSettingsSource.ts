import { useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useFireFormPreview, type FireProjection, type FireSettings } from "@/api/fire";
import { fireFormErrors } from "./fireFormValidation";

const FORM_PREVIEW_DEBOUNCE_MS = 400;

export interface FireSettingsSource {
  /** The projection every derived figure in the settings card — and the text describing it — is read from. */
  projection: FireProjection | undefined;
  /** True while that projection was not computed from the form as it stands, so its figures are shown dimmed. */
  pending: boolean;
}

export const sameSettings = (a: FireSettings | undefined, b: FireSettings | undefined) =>
  a !== undefined && b !== undefined && JSON.stringify(a) === JSON.stringify(b);

/** The saved projection while the form is saved; otherwise the preview of the form, once it is valid and settled. */
export function useFireSettingsSource(
  householdId: string,
  form: FireSettings,
  saved: FireSettings | undefined,
  savedProjection: { data: FireProjection | undefined; isFetching: boolean },
): FireSettingsSource {
  const { t } = useTranslation();
  const [settledForm, setSettledForm] = useState(form);
  useEffect(() => {
    const timer = window.setTimeout(() => setSettledForm(form), FORM_PREVIEW_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [form]);

  const dirty = !sameSettings(form, saved);
  const settled = settledForm === form;
  const startYear = savedProjection.data?.startYear;
  const valid = useMemo(() => Object.keys(fireFormErrors(form, startYear, t)).length === 0, [form, startYear, t]);
  const preview = useFireFormPreview(householdId, settledForm, dirty && settled && valid);

  if (!dirty) return { projection: savedProjection.data, pending: savedProjection.isFetching };
  const fresh = settled && preview.isSuccess && !preview.isPlaceholderData && !preview.isFetching;
  return { projection: preview.data ?? savedProjection.data, pending: !fresh };
}
