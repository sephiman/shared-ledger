import { describe, expect, it } from "vitest";
import { MANUAL_CHAPTERS_ES } from "@/features/manual/content.es";
import en from "./en.json";
import es from "./es.json";

/** Second-person-plural (vosotros) forms; the app speaks to the reader as "tú". */
function secondPersonPlural(text: string): string[] {
  const words = text.replace(/\{\{[^}]*}}/g, " ").match(/[\p{L}]+/gu) ?? [];
  return words.filter((word) => {
    const w = word.toLowerCase();
    if (/^vuestr|^vosotr/.test(w) || w === "os") return true;
    // Present/future/conditional/subjunctive endings: guardáis, habéis, invertís, ganéis, hubierais, hubieseis.
    if (/(áis|éis)$/.test(w) || (/ís$/.test(w) && w !== "país")) return true;
    if (/(ais|eis)$/.test(w) && w !== "seis") return true;
    // Plural imperatives, alone or with a pronoun: rellenad, abrid, movedlo. Nouns in -dad/-tad are not verbs.
    return /(ad|ed|id)(lo|la|los|las|le|les)?$/.test(w) && !/(dad|tad)$/.test(w) && !["red", "sed", "madrid"].includes(w);
  });
}

/** Words ending like a first-person-plural verb that are not verbs. Add here only after checking the word. */
const NOT_FIRST_PERSON_PLURAL = new Set(["últimos", "tramos", "préstamos", "próximos"]);

/** First-person-plural (nosotros) verb forms: ahorramos, gastamos, dejamos, proyectamos, retiramos. */
function firstPersonPlural(text: string): string[] {
  const words = text.replace(/\{\{[^}]*}}/g, " ").match(/[\p{L}]+/gu) ?? [];
  return words.filter((word) => /(amos|emos|imos)$/i.test(word) && !NOT_FIRST_PERSON_PLURAL.has(word.toLowerCase()));
}

function collectStrings(node: unknown, prefix = ""): Array<[string, string]> {
  if (typeof node === "string") return [[prefix, node]];
  if (!node || typeof node !== "object") return [];
  return Object.entries(node).flatMap(([k, v]) => collectStrings(v, prefix ? `${prefix}.${k}` : k));
}

describe("Spanish FIRE copy speaks in the second person singular", () => {
  it("the check recognises the plural forms it guards against, and leaves look-alikes alone", () => {
    expect(secondPersonPlural("Cómo os ha ido")).toEqual(["os"]);
    expect(secondPersonPlural("Habéis ganado 3 puntos porque necesitáis menos")).toEqual(["Habéis", "necesitáis"]);
    expect(secondPersonPlural("Ahorráis pero invertís; guardáis")).toEqual(["Ahorráis", "invertís", "guardáis"]);
    expect(secondPersonPlural("como si la hubierais mirado, mientras lo ganéis")).toEqual(["hubierais", "ganéis"]);
    expect(secondPersonPlural("Rellenad el año. Movedlo. Abrid una fila")).toEqual(["Rellenad", "Movedlo", "Abrid"]);
    expect(secondPersonPlural("vuestro plan, vuestras cifras, no depende de vosotros")).toEqual(["vuestro", "vuestras", "vosotros"]);
    expect(secondPersonPlural("Seis cambios: la mitad, la probabilidad, la rentabilidad, un país, {{os}}")).toEqual([]);
  });

  it("the check recognises first-person-plural verbs and lets the allowlisted words through", () => {
    expect(firstPersonPlural("¿Y si ahorramos {{amount}} más? Si dejamos de aportar, vemos y decidimos")).toEqual([
      "ahorramos",
      "dejamos",
      "vemos",
      "decidimos",
    ]);
    expect(firstPersonPlural("Los últimos 12 meses, en 5 tramos, con {{gastamos}}")).toEqual([]);
  });

  it("no fire.* string in the es bundle uses nosotros verb forms", () => {
    const offending = collectStrings(es.fire)
      .map(([key, value]) => [key, firstPersonPlural(value)] as const)
      .filter(([, hits]) => hits.length > 0);
    expect(offending).toEqual([]);
  });

  it("no fire.* string in the es bundle uses vosotros forms", () => {
    const offending = collectStrings(es.fire)
      .map(([key, value]) => [key, secondPersonPlural(value)] as const)
      .filter(([, hits]) => hits.length > 0);
    expect(offending).toEqual([]);
  });

  it("no analytics.review.* string in the es bundle uses vosotros or nosotros forms", () => {
    const pluralHits = collectStrings(es.analytics.review)
      .map(([key, value]) => [key, secondPersonPlural(value)] as const)
      .filter(([, hits]) => hits.length > 0);
    const weHits = collectStrings(es.analytics.review)
      .map(([key, value]) => [key, firstPersonPlural(value)] as const)
      .filter(([, hits]) => hits.length > 0);
    expect(pluralHits).toEqual([]);
    expect(weHits).toEqual([]);
  });
});

/** "retiro"/"retiros" as a word; "retirada", "retirado" and "retirarías" are other words and pass. */
function retiroWords(text: string): string[] {
  return text.match(/\bretiros?\b/giu) ?? [];
}

/** The only strings where "retiro" means retirement (FIRE's drawdown years), not withdrawing money. Add here only
 *  after checking the English says retirement. */
const RETIREMENT_KEYS = new Set([
  "fire.drawdown_years",
  "fire.view_drawdown",
  "fire.settings_drawdown_fat_title",
  "fire.ai.cite.drawdown_years",
  "fire.ai.cite.drawdown_survival",
]);

describe("Spanish says retirada for withdrawing money", () => {
  it("the check finds retiro and retiros and leaves retirada, retirado and retirarías alone", () => {
    expect(retiroWords("Retiro de Cripto y dos retiros")).toEqual(["Retiro", "retiros"]);
    expect(retiroWords("Una retirada, dos retiradas; Retirado de Cripto; retirarías {{gross}}")).toEqual([]);
  });

  it("no es string uses retiro except where it means retirement", () => {
    const offending = collectStrings(es)
      .filter(([key, value]) => !RETIREMENT_KEYS.has(key) && retiroWords(value).length > 0)
      .map(([key, value]) => `${key}: ${value}`);
    expect(offending).toEqual([]);
  });

  it("every retirement exception still exists and still says retiro, so the allowlist cannot go stale", () => {
    const byKey = new Map(collectStrings(es));
    for (const key of RETIREMENT_KEYS) {
      expect(retiroWords(byKey.get(key) ?? ""), key).not.toEqual([]);
    }
  });

  it("the Spanish manual never uses retiro", () => {
    const offending = collectStrings(MANUAL_CHAPTERS_ES).filter(([, value]) => retiroWords(value).length > 0);
    expect(offending).toEqual([]);
  });
});

describe("Money flow's per-direction movement labels", () => {
  const labels = ["money_flow_contributed_to_wealth", "money_flow_withdrawn_from_wealth"] as const;

  it("never say portfolio, cartera, investments or retiro, in either language", () => {
    for (const key of labels) {
      expect(en.analytics[key], key).not.toMatch(/portfolio|invest/i);
      expect(es.analytics[key], key).not.toMatch(/cartera|inversi/i);
      expect(retiroWords(es.analytics[key]), key).toEqual([]);
    }
  });

  it("name wealth, the app's own word for what movements change", () => {
    expect(labels.map((key) => en.analytics[key])).toEqual(["Contributed to wealth", "Withdrawn from wealth"]);
    expect(labels.map((key) => es.analytics[key])).toEqual(["Aportado a patrimonio", "Retirado de patrimonio"]);
  });
});
