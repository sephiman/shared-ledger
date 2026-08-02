import { useRef } from "react";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/primitives";

interface Props {
  file: File | null;
  onChange: (file: File | null) => void;
  accept?: string;
  // Wording overrides for callers outside the CSV importers (e.g. the bank key file).
  chooseLabel?: string;
  changeLabel?: string;
  emptyLabel?: string;
}

export function FilePicker({
  file,
  onChange,
  accept = ".csv,text/csv",
  chooseLabel,
  changeLabel,
  emptyLabel,
}: Props) {
  const { t } = useTranslation();
  const inputRef = useRef<HTMLInputElement>(null);

  return (
    <div className="flex flex-wrap items-center gap-3">
      <input
        ref={inputRef}
        type="file"
        accept={accept}
        className="hidden"
        onChange={(e) => onChange(e.target.files?.[0] ?? null)}
      />
      <Button
        type="button"
        variant="secondary"
        onClick={() => inputRef.current?.click()}
      >
        {file ? (changeLabel ?? t("import.change_file")) : (chooseLabel ?? t("import.choose_file"))}
      </Button>
      <span className="truncate text-sm text-gray-700 dark:text-gray-200">
        {file ? file.name : <span className="text-gray-400 italic">{emptyLabel ?? t("import.no_file")}</span>}
      </span>
      {file && (
        <Button
          type="button"
          variant="ghost"
          onClick={() => {
            if (inputRef.current) inputRef.current.value = "";
            onChange(null);
          }}
        >
          {t("common.clear")}
        </Button>
      )}
    </div>
  );
}
