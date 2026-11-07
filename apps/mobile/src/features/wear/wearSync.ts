import {
  type CompanionRequest,
  createCompanionRequestCommand,
} from "@t3tools/client-runtime/state/companionProxy";
import { EnvironmentId, ServerProvider } from "@t3tools/contracts";
import { requireOptionalNativeModule } from "expo";
import * as Schema from "effect/Schema";
import { AppRegistry, Platform } from "react-native";

import { environmentCatalog } from "../../connection/catalog";
import { connectionAtomRuntime } from "../../connection/runtime";
import { appAtomRegistry } from "../../state/atom-registry";
import { environmentPresentations } from "../../state/presentation";
import { serverEnvironment } from "../../state/server";
import { refreshEnvironmentLimits } from "../usage/usageLimitsRefresh";

/** apps/mobile/modules/t3-wear-bridge; payloads mirror apps/wear's LinkProtocol.kt. */
interface WearBridge {
  watchNodeIds(): Promise<ReadonlyArray<string>>;
  publishEnvironments(json: string): Promise<void>;
  respond(nodeId: string, json: string): Promise<void>;
  startKeepAlive(): void;
  stopKeepAlive(): void;
  addListener(
    event: "onWatchRequest",
    listener: (event: { readonly nodeId: string; readonly json: string }) => void,
  ): { remove(): void };
}

type WatchAsk = (CompanionRequest | { readonly kind: "usage" }) & {
  readonly id: string;
  readonly environmentId: string;
};

const PROTOCOL_VERSION = 1;
const KEEP_ALIVE_TASK = "T3WearKeepAlive";
const RESYNC_INTERVAL_MS = 60_000;
/** Status the watch reads as "this phone is not connected to that environment right now". */
const STATUS_NOT_CONNECTED = 0;

const companionRequest = createCompanionRequestCommand(connectionAtomRuntime);
const encodeProviders = Schema.encodeSync(Schema.Array(ServerProvider));

// While the keep-alive task is open, React Native keeps running timers in the background.
AppRegistry.registerHeadlessTask(KEEP_ALIVE_TASK, () => () => new Promise<void>(() => {}));

function parseAsk(json: string): WatchAsk | null {
  try {
    const value = JSON.parse(json) as Partial<WatchAsk> | null;
    if (typeof value?.id !== "string" || typeof value.environmentId !== "string") return null;
    return value as WatchAsk;
  } catch {
    return null;
  }
}

async function answer(ask: WatchAsk): Promise<{ status: number; body?: string; error?: string }> {
  const environmentId = EnvironmentId.make(ask.environmentId);
  const presentation = appAtomRegistry
    .get(environmentPresentations.presentationsAtom)
    .get(environmentId);
  if (presentation?.connection.phase !== "connected") {
    return { status: STATUS_NOT_CONNECTED, error: "Your phone is not connected to it right now" };
  }
  if (ask.kind === "usage") {
    // Throttled to one probe every few minutes per environment, shared with the phone's own views.
    await refreshEnvironmentLimits(appAtomRegistry, environmentId, { automatic: true });
    const providers =
      appAtomRegistry.get(serverEnvironment.configValueAtom(environmentId))?.providers ?? [];
    return { status: 200, body: JSON.stringify({ providers: encodeProviders(providers) }) };
  }
  const request: CompanionRequest =
    ask.kind === "shell"
      ? { kind: "shell" }
      : ask.kind === "thread"
        ? { kind: "thread", threadId: ask.threadId }
        : { kind: "history", threadId: ask.threadId, cursor: ask.cursor };
  const result = await companionRequest.run(appAtomRegistry, { environmentId, input: request });
  if (result._tag !== "Success") {
    return { status: STATUS_NOT_CONNECTED, error: "Your phone could not reach it" };
  }
  return { status: result.value.status, body: result.value.body };
}

/**
 * Mirrors this phone's environments onto the T3 Code watch app. The watch never connects to an
 * environment itself: it asks, and the phone answers over its own connection, whichever route
 * that uses. A foreground service keeps the phone answering while the app is in the background.
 */
export function installWearSync(): () => void {
  const bridge =
    Platform.OS === "android" ? requireOptionalNativeModule<WearBridge>("T3WearBridge") : null;
  if (bridge === null) return () => {};

  let keepingAlive = false;
  let lastPublished = "";

  const publish = async () => {
    const catalog = appAtomRegistry.get(environmentCatalog.catalogValueAtom);
    if (!catalog.isReady) return;
    const watches = await bridge.watchNodeIds();
    if (watches.length === 0) return;
    if (!keepingAlive) {
      bridge.startKeepAlive();
      keepingAlive = true;
    }
    const environments = [...appAtomRegistry.get(environmentPresentations.presentationsAtom)]
      .filter(([, presentation]) => presentation.entry.enabled)
      .map(([environmentId, presentation]) => ({
        environmentId,
        label: presentation.entry.target.label,
        connected: presentation.connection.phase === "connected",
      }));
    const json = JSON.stringify({ version: PROTOCOL_VERSION, environments });
    if (json === lastPublished) return;
    await bridge.publishEnvironments(json);
    lastPublished = json;
  };

  const sync = () => {
    publish().catch((error: unknown) =>
      console.warn("Could not share environments with the watch", error),
    );
  };

  const requests = bridge.addListener("onWatchRequest", ({ nodeId, json }) => {
    const ask = parseAsk(json);
    if (ask === null) return;
    void answer(ask)
      .catch((error: unknown) => ({
        status: STATUS_NOT_CONNECTED,
        error: error instanceof Error ? error.message : "Your phone could not answer",
      }))
      .then((reply) =>
        bridge.respond(nodeId, JSON.stringify({ version: PROTOCOL_VERSION, id: ask.id, ...reply })),
      )
      .catch((error: unknown) => console.warn("Could not answer the watch", error));
  });

  const unsubscribeCatalog = appAtomRegistry.subscribe(environmentCatalog.catalogValueAtom, sync);
  const unsubscribePresentations = appAtomRegistry.subscribe(
    environmentPresentations.presentationsAtom,
    sync,
    { immediate: true },
  );
  // A watch paired after launch, or one that missed an update, catches up here.
  const timer = setInterval(() => {
    lastPublished = "";
    sync();
  }, RESYNC_INTERVAL_MS);
  return () => {
    requests.remove();
    unsubscribeCatalog();
    unsubscribePresentations();
    clearInterval(timer);
    if (keepingAlive) bridge.stopKeepAlive();
  };
}
