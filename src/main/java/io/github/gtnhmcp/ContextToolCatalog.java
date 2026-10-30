package io.github.gtnhmcp;

import static io.github.gtnhmcp.game.Values.map;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import io.github.gtnhmcp.protocol.ToolDefinition;

/** Client-neutral message delivery and per-world planning memory. */
public final class ContextToolCatalog {

    private ContextToolCatalog() {}

    public static List<ToolDefinition> tools() {
        Map<String, Object> world = text("Persistent world ID from get_context or the context resources", 80);
        Map<String, Object> message = text("Message ID from get_messages", 80);
        return Arrays.asList(
            tool(
                "get_messages",
                "Read user-submitted game context messages after a per-world sequence. Messages contain frozen item/recipe attachments and user text. Client adapters must bind a conversation, deduplicate IDs, and explicitly start an agent turn; MCP notifications alone do not do this. Check gap/hasMore.",
                true,
                map("worldId", world, "afterSequence", number(0, Integer.MAX_VALUE), "limit", number(1, 20)),
                "worldId"),
            tool(
                "get_message",
                "Read one retained context message, including its exact captured attachment.",
                true,
                map("worldId", world, "messageId", message),
                "worldId",
                "messageId"),
            tool(
                "ack_message",
                "Record that a named client adapter accepted this message into its chosen conversation. This local metadata write does not mean the AI completed a response. Repeated acknowledgment by the same consumer is idempotent.",
                false,
                map(
                    "worldId",
                    world,
                    "messageId",
                    message,
                    "consumerId",
                    map(
                        "type",
                        "string",
                        "description",
                        "Stable adapter identity; not an authentication credential",
                        "minLength",
                        1,
                        "maxLength",
                        96,
                        "pattern",
                        "^[A-Za-z0-9._:-]{1,96}$")),
                "worldId",
                "messageId",
                "consumerId"),
            tool(
                "get_project_memory",
                "Read persistent planning memory for a world. This is client-maintained information, not an authoritative scan. Distinguish proposed, user-reported built, and recently observed production lines; check live tools before treating old evidence as current.",
                true,
                map("worldId", world),
                "worldId"),
            tool(
                "update_project_memory",
                "Replace the local planning document with optimistic revision checking. Store goals, constraints, production lines, connections and evidence here to retain context across conversations. Does not modify blocks or verify that a plan was built. Read and merge the current document first; retry conflicts only after rereading.",
                false,
                map(
                    "worldId",
                    world,
                    "expectedRevision",
                    number(0, Integer.MAX_VALUE),
                    "project",
                    map("type", "object", "additionalProperties", true)),
                "worldId",
                "expectedRevision",
                "project"),
            tool(
                "reply_in_game",
                "Display a plain-text reply to a retained context message in the local player's chat only. Requires that message's world to be active. Never broadcasts or runs commands. replyId deduplicates the last 256 replies for this mod process; delivery across process crashes is at-least-once.",
                false,
                map(
                    "worldId",
                    world,
                    "messageId",
                    message,
                    "replyId",
                    text("Stable ID for this reply, reused on retries", 128),
                    "text",
                    text("Plain text to show locally", 4096)),
                "worldId",
                "messageId",
                "replyId",
                "text"));
    }

    private static ToolDefinition tool(String name, String description, boolean readOnly,
        Map<String, Object> properties, String... required) {
        return new ToolDefinition(
            name,
            description,
            map(
                "type",
                "object",
                "properties",
                properties,
                "required",
                Arrays.asList(required),
                "additionalProperties",
                false),
            readOnly);
    }

    private static Map<String, Object> text(String description, int max) {
        return map("type", "string", "description", description, "minLength", 1, "maxLength", max);
    }

    private static Map<String, Object> number(int min, int max) {
        return map("type", "integer", "minimum", min, "maximum", max);
    }
}
