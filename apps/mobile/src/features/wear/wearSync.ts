import {
  type CompanionRequest,
  createCompanionRequestCommand,
} from "@t3tools/client-runtime/state/companionProxy";
import { runAtomCommand, squashAtomCommandFailure } from "@t3tools/client-runtime/state/runtime";
import {
  CommandId,
  EnvironmentId,
  MessageId,
  ProviderApprovalDecision,
  RuntimeRequestId,
  ServerProvider,
  ThreadId,
} from "@t3tools/contracts";
import { requireOptionalNativeModule } from "expo";
import { AsyncResult } from "effect/reactivity";
import * as Schema from "effect/Schema";
import { AppRegistry, Platform } from "react-native";

import { environmentCatalog } from "../../connection/catalog";
import { DEFAULT_FOLLOW_UP_BEHAVIOR } from "../../lib/followUpBehavior";
import { uuidv4 } from "../../lib/uuid";
import { connectionAtomRuntime } from "../../connection/runtime";
import { appAtomRegistry } from "../../state/atom-registry";
import { mobilePreferencesAtom } from "../../state/preferences";
import { environmentPresentations } from "../../state/presentation";
import { serverEnvironment } from "../../state/server";
import { enqueueThreadOutboxMessage } from "../../state/thread-outbox";
import { environmentThreadShells, threadEnvironment } from "../../state/threads";
import { resolveThreadListV2Status } from "../threads/threadListV2";
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

/** Things the watch can do. Each runs through the same path the phone's own UI uses. */
type WatchAction =
  | { readonly kind: "send"; readonly threadId: string; readonly text: string }
  | {
      readonly kind: "approve";
      readonly threadId: string;
      readonly requestId: string;
      readonly decision: string;
    }
  | {
      readonly kind: "answer";
      readonly threadId: string;
      readonly requestId: string;
      readonly answers: Readonly<Record<string, string | ReadonlyArray<string>>>;
    };

type WatchAsk = (CompanionRequest | { readonly kind: "usage" } | WatchAction) & {
  readonly id: string;
  readonly environmentId: string;
};

type WatchReply = { status: number; body?: string; error?: string };

const PROTOCOL_VERSION = 1;
const KEEP_ALIVE_TASK = "T3WearKeepAlive";
const RESYNC_INTERVAL_MS = 60_000;
/** Status the watch reads as "this phone is not connected to that environment right now". */
const STATUS_NOT_CONNECTED = 0;

const companionRequest = createCompanionRequestCommand(connectionAtomRuntime);
const encodeProviders = Schema.encodeSync(Schema.Array(ServerProvider));
const isApprovalDecision = Schema.is(ProviderApprovalDecision);

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

function commandReply(
  result: Awaited<ReturnType<typeof runAtomCommand>>,
  fallback: string,
): WatchReply {
  if (result._tag === "Success") return { status: 200, body: "{}" };
  const failure = squashAtomCommandFailure(result);
  return { status: 500, error: failure instanceof Error ? failure.message : fallback };
}

/**
 * The composer's choice for a message sent while the thread may be busy (see
 * use-thread-composer-state.ts): steering is "auto", so a turn that ended meanwhile still runs it,
 * and a thread still setting up queues it.
 */
function followUpDispatchMode(environmentId: EnvironmentId, threadId: ThreadId): "auto" | "queue" {
  const preferences = appAtomRegistry.get(mobilePreferencesAtom);
  const followUpBehavior = AsyncResult.isSuccess(preferences)
    ? (preferences.value.followUpBehavior ?? DEFAULT_FOLLOW_UP_BEHAVIOR)
    : DEFAULT_FOLLOW_UP_BEHAVIOR;
  const thread = appAtomRegistry
    .get(environmentThreadShells.threadShellsAtom)
    .find((shell) => shell.environmentId === environmentId && shell.id === threadId);
  const settingUp =
    thread?.runtime?.status === "preparing" || thread?.runtime?.status === "starting";
  return settingUp || followUpBehavior === "queue" ? "queue" : "auto";
}

async function act(environmentId: EnvironmentId, action: WatchAction): Promise<WatchReply> {
  const threadId = ThreadId.make(action.threadId);
  switch (action.kind) {
    case "send": {
      const text = action.text.trim();
      if (text.length === 0) return { status: 400, error: "Nothing to send" };
      // Sent like the phone's own composer: through the outbox, which retries until the
      // environment accepts it, and steering a running turn unless follow-ups are set to queue.
      await enqueueThreadOutboxMessage({
        environmentId,
        threadId,
        messageId: MessageId.make(uuidv4()),
        commandId: CommandId.make(uuidv4()),
        text,
        attachments: [],
        dispatchMode: followUpDispatchMode(environmentId, threadId),
        createdAt: new Date().toISOString(),
      });
      return { status: 200, body: "{}" };
    }
    case "approve": {
      if (!isApprovalDecision(action.decision)) return { status: 400, error: "Unknown choice" };
      const result = await runAtomCommand(
        appAtomRegistry,
        threadEnvironment.respondToApproval,
        {
          environmentId,
          input: {
            threadId,
            requestId: RuntimeRequestId.make(action.requestId),
            decision: action.decision,
          },
        },
        { reportFailure: false },
      );
      return commandReply(result, "Could not submit the approval");
    }
    case "answer": {
      const result = await runAtomCommand(
        appAtomRegistry,
        threadEnvironment.respondToUserInput,
        {
          environmentId,
          input: {
            threadId,
            requestId: RuntimeRequestId.make(action.requestId),
            answers: action.answers,
          },
        },
        { reportFailure: false },
      );
      return commandReply(result, "Could not submit the answer");
    }
  }
}

async function answer(ask: WatchAsk): Promise<WatchReply> {
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
  if (ask.kind === "send" || ask.kind === "approve" || ask.kind === "answer") {
    return act(environmentId, ask);
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
    // Working threads drive the watch face's ongoing-activity indicator, so the watch learns of
    // them without its app open. Threads of a disconnected environment may be stale, so they drop.
    const connected = new Set(environments.filter((e) => e.connected).map((e) => e.environmentId));
    const working = appAtomRegistry
      .get(environmentThreadShells.navigationThreadShellsAtom)
      .filter(
        (thread) =>
          connected.has(thread.environmentId) && resolveThreadListV2Status(thread) === "working",
      )
      .map((thread) => ({
        environmentId: thread.environmentId,
        threadId: thread.id,
        title: thread.title,
      }));
    const json = JSON.stringify({ version: PROTOCOL_VERSION, environments, working });
    if (json === lastPublished) return;
    await bridge.publishEnvironments(json);
    lastPublished = json;
  };

  // Thread lists change many times a second while agents stream; publishes never overlap, and
  // one more runs after the current one when anything changed meanwhile.
  let publishing = false;
  let changedMeanwhile = false;
  const sync = () => {
    if (publishing) {
      changedMeanwhile = true;
      return;
    }
    publishing = true;
    void (async () => {
      try {
        do {
          changedMeanwhile = false;
          await publish();
        } while (changedMeanwhile);
      } catch (error) {
        console.warn("Could not share environments with the watch", error);
      } finally {
        publishing = false;
      }
    })();
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
  const unsubscribeThreads = appAtomRegistry.subscribe(
    environmentThreadShells.navigationThreadShellsAtom,
    sync,
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
    unsubscribeThreads();
    clearInterval(timer);
    if (keepingAlive) bridge.stopKeepAlive();
  };
}
