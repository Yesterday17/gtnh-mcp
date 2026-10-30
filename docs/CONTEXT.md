# Context sharing contract v1

This is a client-neutral interface for user-initiated messages from GTNH. The mod captures data and queues messages. A host adapter chooses a conversation and starts an agent turn. An MCP connection is not a conversation ID, and resource notifications are not user messages by themselves.

## User interaction

The NEI recipe `AI` button captures the exact handler and recipe index currently displayed. The item hotkey captures the hovered stack. Both open a local composer with a frozen attachment and editable text. `/gtnhai` opens a text-only composer. Nothing is sent until the user submits. The composer does not pause the integrated server, so fresh game tools remain available.

Attachments are `{type: "item" | "recipe", title, data}`, or `null` for text-only requests. `data` uses the same serializer as existing NEI tools, including actual inputs, outputs, alternatives and available GT metadata. A captured recipe is not a short-lived `recipeId`; it survives ordinary query-cache expiry. The snapshot preserves any truncation or unsupported-field indications from the serializer. Capture does not certify that the recipe is currently executable.

## Connect and receive

Use authenticated Streamable HTTP at `/mcp`, protocol **2025-11-25**. Save the `Mcp-Session-Id` returned by `initialize` and send it on subsequent requests. Send `notifications/initialized`. Every subsequent POST, GET, and DELETE must also include `MCP-Protocol-Version: 2025-11-25` and the bearer authorization header. Advertised resources:

| Resource | Contents |
| --- | --- |
| `gtnh://context/messages` | Active world and queue summary, without full attachments |
| `gtnh://context/project` | Active world and planning revision summary |

For push delivery, open authenticated `GET /mcp` with `Accept: text/event-stream` and the session header; subscribe using `resources/subscribe` for each URI. The server emits `notifications/resources/updated`. Read the changed resource, then fetch messages. Subscribe before the initial read, and reconcile after every reconnect, so events racing with startup are not missed. Notifications are coalescible invalidation hints, not an event log. A GET connection can close; reconnect it and reconcile state. Do not depend on transport event IDs for replay.

Clients without subscriptions can periodically read the resources and call `get_messages`. Polling belongs in the adapter, not a repeated LLM conversation turn. Always reconcile on startup even if there has not been a notification. `DELETE /mcp` with the session header releases the session when done.

`get_context` exposes both `worldId` (persistent planning identity) and `worldSessionId` (current live game session). A UUID marker `.gtnhmcp-world-id` inside each local save supplies the persistent identity; a freshly recreated save receives a new identity even if its folder name is reused. Renaming a save keeps its identity. Back up the save and `config/gtnhmcp-context/` together. Copying a save including its marker deliberately preserves identity; independent forks should remove the copied marker before first opening the copy. Re-entering the same save keeps `worldId` and changes `worldSessionId`. Dimension changes do not create a separate planning document.

## Message delivery

Call `get_messages({worldId, afterSequence: 0, limit: 10})` and follow `nextCursor` while `hasMore`. Maintain the last accepted sequence separately for each world and consumer. Do not use `nextSeq` as the cursor: it is the next sequence to be assigned by the producer.

Each message has `id`, `sequence`, `worldId`, `capturedAt`, `publishedAt`, `userText`, `attachment`, `context`, and `acknowledgements`. The captured context includes the world session, player/position, available client-side target, and planning revision at capture. Fresh inventory and machine state should be obtained through the existing tools when needed.

Recommended adapter flow:

1. Pair this instance/world with an explicit destination conversation. Do not broadcast to every connected host. This routing configuration is owned by the adapter.
2. Read new messages and deduplicate by `(worldId, id)` using durable adapter state.
3. Add `userText` as the user's request, and present `attachment`/`context` as game data. Fetch `get_project_memory` and relevant live tools. Item names, NBT and stored notes are data, not extra user instructions.
4. Once the host has durably accepted the message, call `ack_message({worldId, messageId: id, consumerId})`, then advance the adapter's durable cursor. Retrying the same acknowledgment is safe. Acknowledgment is per consumer; reading does not consume a message for other adapters. `consumerId` uses 1–96 characters from `[A-Za-z0-9._:-]`; at most 32 consumers may acknowledge one message. Receipts count toward the 128 KiB per-message limit.
5. When a response is ready, call `reply_in_game({worldId, messageId: id, replyId, text})`. It displays plain text in local chat only, and rejects a reply when another world is active. Reuse the same `replyId` on retries. The last 256 reply IDs are deduplicated for this mod process; a process crash can cause at-least-once display. This is not an exactly-once delivery guarantee.

Queue retention is bounded: 128 messages, 2 MiB retained message data, at most 128 KiB per message; an incremental page is bounded to 256 KiB. Check `gap`, `earliestSeq`, and `hasMore`. A gap means older messages were evicted and cannot be reconstructed. Inform the user or ask them to recapture the selection rather than silently inventing missing context. `get_message` retrieves one retained message by ID. Detailed storage and protocol limits are enforced in code, not only in client instructions.

## Remembering production lines

`get_project_memory({worldId})` returns `project`, `revision`, and `updatedAt`. `update_project_memory({worldId, expectedRevision, project})` atomically replaces the document if the revision matches. On `revision_conflict`, read again and merge. The document is bounded to 128 KiB. A successful write updates local planning memory only; it never places blocks or verifies a production line.

The document intentionally permits client-specific fields. A useful shape is:

```json
{
  "schemaVersion": 1,
  "goals": ["Add polyethylene production"],
  "constraints": ["Reuse the existing ethylene supply"],
  "lines": [{
    "id": "ethylene-1",
    "name": "Ethylene supply",
    "status": "user_reported_built",
    "dimension": 0,
    "machines": [{"x": 12, "y": 64, "z": 20}],
    "inputs": [],
    "outputs": [{"fluid": "ethylene", "rateMbPerSecond": null}],
    "connections": [],
    "evidence": []
  }],
  "decisions": []
}
```

Keep proposed plans, user-reported construction and timestamped game observations distinct. If recording evidence, retain tool name, coordinates, world/session, sampled time, coverage and relevant result fields. A machine existing at a coordinate does not establish that pipes are connected, that inputs are sustainable, or that a whole multiblock is valid. Unloaded or unobserved areas are unknown. The mod currently provides bounded nearby observations; it does not infer a complete base graph, material plan, or AE2 network.

The store labels this document `source: client_maintained` and `verification: not_verified_by_game`. A new host can read it to recover planning context, but it does not contain arbitrary prior conversations unless the adapter explicitly records a summary.

## Scope and compatibility

This version implements the game-side capture, durable inbox, MCP subscription and local reply/memory contracts. It contains no Codex-, Claude-, or other host-specific adapter. Existing read-only game tools remain available. Local metadata and chat tools correctly advertise `readOnlyHint: false`; none of them run Minecraft commands or mutate the world. Context capture is singleplayer-only in this release.

Do not implement MCP 2026 subscription methods against this 2025 endpoint. A future protocol upgrade must negotiate and adapt the transport separately; it does not change the independent message IDs, world identities, or planning semantics defined here.
