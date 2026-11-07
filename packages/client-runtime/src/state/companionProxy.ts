/**
 * Read-only requests a companion device, such as a watch, makes through this client. The
 * companion never connects to the environment itself: it sees what this client can reach, over
 * whichever route this client uses (LAN, Tailscale or T3 Connect), with this client's session.
 */
import { ThreadId } from "@t3tools/contracts";
import * as Effect from "effect/Effect";
import { HttpClient } from "effect/http";
import type { Atom } from "effect/reactivity";

import type { makeEnvironmentHttpApiUrlBuilder } from "../rpc/http.ts";
import type { EnvironmentRegistry } from "../connection/registry.ts";
import {
  executeConnectedEnvironmentHttpRequest,
  withOrchestrationProtocolHeader,
} from "./environmentHttpAuth.ts";
import { createEnvironmentCommand } from "./runtime.ts";

export type CompanionRequest =
  | { readonly kind: "shell" }
  | { readonly kind: "thread"; readonly threadId: string }
  | { readonly kind: "history"; readonly threadId: string; readonly cursor: string };

/** The environment's response as sent, so the companion decodes the wire format itself. */
export interface CompanionResponse {
  readonly status: number;
  readonly body: string;
}

const COMPANION_REQUEST_TIMEOUT_MS = 15_000;

function companionUrl(
  urls: ReturnType<typeof makeEnvironmentHttpApiUrlBuilder>["orchestration"],
  request: CompanionRequest,
): string {
  switch (request.kind) {
    case "shell":
      return urls.shellSnapshot({ query: { omitIdleSubagents: "1", omitOlderSettled: "1" } });
    case "thread":
      return urls.threadBoundedSnapshot({
        params: { threadId: ThreadId.make(request.threadId) },
        query: {},
      });
    case "history":
      return urls.threadHistoryPage({
        params: { threadId: ThreadId.make(request.threadId) },
        query: { cursor: request.cursor, view: "conversation" },
      });
  }
}

export function createCompanionRequestCommand<R, E>(
  runtime: Atom.AtomRuntime<EnvironmentRegistry | HttpClient.HttpClient | R, E>,
) {
  return createEnvironmentCommand(runtime, {
    label: "environment-data:companion:request",
    execute: Effect.fn(function* (request: CompanionRequest) {
      const httpClient = yield* HttpClient.HttpClient;
      // The URL is signed for DPoP before the request runs, so the request reuses it verbatim.
      let requestUrl = "";
      return yield* executeConnectedEnvironmentHttpRequest({
        group: "orchestration",
        method: "GET",
        timeoutMs: COMPANION_REQUEST_TIMEOUT_MS,
        url: (urls) => (requestUrl = companionUrl(urls, request)),
        request: ({ headers }) =>
          httpClient
            .get(requestUrl, {
              headers: Object.fromEntries(
                Object.entries(withOrchestrationProtocolHeader(headers)).filter(
                  (entry): entry is [string, string] => entry[1] !== undefined,
                ),
              ),
            })
            .pipe(
              Effect.flatMap((response) =>
                Effect.map(response.text, (body): CompanionResponse => ({
                  status: response.status,
                  body,
                })),
              ),
            ),
      });
    }),
  });
}
