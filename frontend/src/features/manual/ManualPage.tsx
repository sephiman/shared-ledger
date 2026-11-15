import { useEffect, useMemo, useRef, useState } from "react";
import { Link, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Badge, Button, Card, CardBody, CardHeader, Input, buttonClassName } from "@/components/ui/primitives";
import { MANUAL_CHAPTERS_EN } from "./content.en";
import { MANUAL_CHAPTERS_ES } from "./content.es";
import { SECTION_LINKS } from "./sectionLinks";
import type { ManualChapter, ManualSection } from "./manualTypes";

function normalizeSearch(value: string): string {
  return value
    .normalize("NFD")
    .replace(/\p{Diacritic}/gu, "")
    .toLowerCase()
    .trim();
}

function sectionMatches(section: ManualSection, chapter: ManualChapter, needle: string): boolean {
  const haystack = [
    chapter.title,
    chapter.summary,
    section.heading,
    ...section.paragraphs,
    ...(section.bullets ?? []).flatMap((b) => [b.term ?? "", b.text]),
    section.tip ?? "",
  ]
    .map(normalizeSearch)
    .join(" ");
  return haystack.includes(needle);
}

export function ManualPage() {
  const { t, i18n } = useTranslation();
  const { hash } = useLocation();
  const [query, setQuery] = useState("");
  const topRef = useRef<HTMLDivElement>(null);

  const chapters = i18n.language.startsWith("es") ? MANUAL_CHAPTERS_ES : MANUAL_CHAPTERS_EN;
  const needle = normalizeSearch(query);

  const filteredChapters = useMemo(() => {
    if (!needle) return chapters;
    return chapters
      .map((chapter) => ({
        ...chapter,
        sections: chapter.sections.filter((section) => sectionMatches(section, chapter, needle)),
      }))
      .filter((chapter) => chapter.sections.length > 0);
  }, [chapters, needle]);

  const matchingSectionCount = useMemo(
    () => filteredChapters.reduce((sum, chapter) => sum + chapter.sections.length, 0),
    [filteredChapters],
  );

  useEffect(() => {
    if (!hash) return;
    const target = document.getElementById(hash.slice(1));
    target?.scrollIntoView({ behavior: "smooth", block: "start" });
  }, [hash]);

  return (
    <div ref={topRef} className="scroll-mt-6 space-y-6">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <h1 className="text-xl font-semibold">{t("manual.title")}</h1>
          <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("manual.subtitle")}</p>
        </div>
        <div className="flex w-full items-center gap-2 sm:max-w-sm">
          <Input
            type="search"
            aria-label={t("manual.search_label")}
            placeholder={t("manual.search_placeholder")}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
          {query && (
            <Button variant="secondary" onClick={() => setQuery("")} className="shrink-0">
              {t("manual.clear_search")}
            </Button>
          )}
        </div>
      </div>

      {needle && (
        <p className="text-xs text-gray-500 dark:text-gray-400">
          {t("manual.results_count", { count: matchingSectionCount })}
        </p>
      )}

      {filteredChapters.length === 0 ? (
        <Card>
          <CardBody className="flex flex-col items-start gap-3 py-8">
            <p className="text-sm text-gray-600 dark:text-gray-300">
              {t("manual.no_results", { query: query.trim() })}
            </p>
            <Button variant="secondary" onClick={() => setQuery("")}>
              {t("manual.clear_search")}
            </Button>
          </CardBody>
        </Card>
      ) : (
        <div className="grid gap-6 lg:grid-cols-[240px_1fr] lg:items-start">
          <aside aria-label={t("manual.contents")} className="lg:sticky lg:top-6">
            <Card>
              <CardHeader className="py-3">
                <p className="text-xs font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">
                  {t("manual.contents")}
                </p>
              </CardHeader>
              <CardBody className="p-2">
                <nav aria-label={t("manual.jump_to")}>
                  <ul className="space-y-0.5">
                    {filteredChapters.map((chapter) => (
                      <li key={chapter.id}>
                        <a
                          href={`#${chapter.id}`}
                          className="block rounded-md px-2.5 py-1.5 text-sm text-gray-700 hover:bg-item-hover hover:text-primary dark:text-gray-200"
                        >
                          {chapter.title}
                        </a>
                      </li>
                    ))}
                  </ul>
                </nav>
              </CardBody>
            </Card>
          </aside>

          <div className="space-y-6">
            {filteredChapters.map((chapter) => (
              <Card key={chapter.id} id={chapter.id} className="scroll-mt-6">
                <CardHeader className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
                  <div>
                    <h2 className="text-lg font-semibold text-gray-900 dark:text-gray-100">{chapter.title}</h2>
                    <p className="mt-0.5 text-sm text-gray-500 dark:text-gray-400">{chapter.summary}</p>
                  </div>
                  {chapter.route && (
                    <Link
                      to={chapter.route.to}
                      className={buttonClassName("secondary", "shrink-0 px-3 py-1.5 text-xs")}
                    >
                      {t("manual.open_section", { name: t(chapter.route.labelKey) })}
                    </Link>
                  )}
                </CardHeader>

                <CardBody className="divide-y divide-border space-y-6">
                  {chapter.sections.map((section, idx) => (
                    <section
                      key={section.id}
                      id={section.id}
                      className={idx === 0 ? "scroll-mt-6 space-y-3" : "scroll-mt-6 space-y-3 pt-6"}
                    >
                      <h3 className="text-base font-medium text-gray-900 dark:text-gray-100">{section.heading}</h3>

                      {section.paragraphs.map((paragraph, pIdx) => (
                        <p key={pIdx} className="text-sm leading-relaxed text-gray-700 dark:text-gray-300">
                          {paragraph}
                        </p>
                      ))}

                      {section.bullets && section.bullets.length > 0 && (
                        <ul className="list-disc space-y-2 pl-5 text-sm leading-relaxed text-gray-700 dark:text-gray-300">
                          {section.bullets.map((bullet, bIdx) => (
                            <li key={bIdx}>
                              {bullet.term && (
                                <span className="font-medium text-gray-900 dark:text-gray-100">{bullet.term}: </span>
                              )}
                              <span>{bullet.text}</span>
                            </li>
                          ))}
                        </ul>
                      )}

                      {section.tip && (
                        <div className="flex items-start gap-2 rounded-md border border-border bg-raised px-3 py-2.5 text-xs leading-relaxed text-gray-700 dark:text-gray-300">
                          <Badge tone="sky" className="mt-0.5 shrink-0">
                            {t("manual.tip_label")}
                          </Badge>
                          <span>{section.tip}</span>
                        </div>
                      )}

                      {(() => {
                        const links = SECTION_LINKS[section.id];
                        if (!links || links.length === 0) return null;
                        return (
                          <div className="flex flex-wrap items-center gap-2 pt-1">
                            {links.map((link) => (
                              <Link
                                key={`${section.id}-${link.to}-${link.labelKey}`}
                                to={link.to}
                                className="inline-flex items-center rounded-md border border-border bg-raised px-2.5 py-1 text-xs font-medium text-primary hover:bg-raised-hover"
                              >
                                {t("manual.open_section", { name: t(link.labelKey) })}
                              </Link>
                            ))}
                          </div>
                        );
                      })()}
                    </section>
                  ))}
                </CardBody>
              </Card>
            ))}

            <div className="flex justify-end">
              <button
                type="button"
                onClick={() => topRef.current?.scrollIntoView({ behavior: "smooth", block: "start" })}
                className="text-xs font-medium text-primary hover:underline"
              >
                {t("manual.back_to_top")}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
