import { Component, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Button, Card, CardBody } from "@/components/ui/primitives";
import { isChunkReloadPending } from "@/lib/chunkRecovery";

interface Props {
  children: ReactNode;
  resetKey: string;
  pendingFallback: ReactNode;
  onReload?: () => void;
}

interface State {
  hasError: boolean;
}

function RouteLoadError({ onReload }: { onReload: () => void }) {
  const { t } = useTranslation();
  return (
    <Card className="mx-auto max-w-lg">
      <CardBody className="space-y-3">
        <h2 className="text-base font-semibold text-gray-900 dark:text-gray-100">{t("errors.ROUTE_LOAD_TITLE")}</h2>
        <p className="text-sm text-gray-600 dark:text-gray-300">{t("errors.ROUTE_LOAD_BODY")}</p>
        <div>
          <Button onClick={onReload}>{t("common.reload")}</Button>
        </div>
      </CardBody>
    </Card>
  );
}

/** Catches failed lazy-route imports or render errors so the shell stays navigable. */
export class RouteErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false };

  static getDerivedStateFromError(): State {
    return { hasError: true };
  }

  componentDidUpdate(prevProps: Props) {
    if (prevProps.resetKey !== this.props.resetKey && this.state.hasError) {
      this.setState({ hasError: false });
    }
  }

  render() {
    if (!this.state.hasError) return this.props.children;
    if (isChunkReloadPending()) return this.props.pendingFallback;
    return <RouteLoadError onReload={this.props.onReload ?? (() => window.location.reload())} />;
  }
}
