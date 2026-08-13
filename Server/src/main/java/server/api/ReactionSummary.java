package server.api;

import java.util.List;

/**
 * How a message has been reacted to.
 *
 * Grouped by emoji rather than listed one per row: a client renders a count and
 * a tooltip of names, and doing that grouping in every client would duplicate
 * the same loop.
 *
 * @param emoji the reaction
 * @param count how many people used it
 * @param users who they were, in the order they reacted
 */
public record ReactionSummary(String emoji, int count, List<String> users) {
}
