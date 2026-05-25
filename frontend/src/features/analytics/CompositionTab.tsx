import { AllocationTab } from "./AllocationTab";
import { CostOfLivingTab } from "./CostOfLivingTab";
import { RecurringShareTab } from "./RecurringShareTab";
import { useScrollToHash } from "./useScrollToHash";

export function CompositionTab() {
  useScrollToHash();
  return (
    <div className="space-y-6">
      <section id="allocation" className="scroll-mt-4">
        <AllocationTab />
      </section>
      <section id="cost_of_living" className="scroll-mt-4">
        <CostOfLivingTab />
      </section>
      <section id="recurring" className="scroll-mt-4">
        <RecurringShareTab />
      </section>
    </div>
  );
}
