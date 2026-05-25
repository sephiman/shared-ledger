import { TopMoversTab } from "./TopMoversTab";
import { HeatmapTab } from "./HeatmapTab";
import { useScrollToHash } from "./useScrollToHash";

export function ChangesTab() {
  useScrollToHash();
  return (
    <div className="space-y-6">
      <section id="movers" className="scroll-mt-4">
        <TopMoversTab />
      </section>
      <section id="heatmap" className="scroll-mt-4">
        <HeatmapTab />
      </section>
    </div>
  );
}
