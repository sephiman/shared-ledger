import { YearOverYearTab } from "./YearOverYearTab";
import { YearByYearTab } from "./YearByYearTab";
import { TrailingTab } from "./TrailingTab";
import { ForecastTab } from "./ForecastTab";
import { useScrollToHash } from "./useScrollToHash";

export function TrendsTab() {
  useScrollToHash();
  return (
    <div className="space-y-6">
      <section id="yoy" className="scroll-mt-4">
        <YearOverYearTab />
      </section>
      <section id="yby" className="scroll-mt-4">
        <YearByYearTab />
      </section>
      <section id="trailing" className="scroll-mt-4">
        <TrailingTab />
      </section>
      <section id="forecast" className="scroll-mt-4">
        <ForecastTab />
      </section>
    </div>
  );
}
