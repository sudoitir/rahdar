package com.acme.notes;

import java.util.List;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * An assistant tool, offered on Rahdar's MCP endpoint while the plugin runs. Its name starts with
 * the plugin's id in snake_case and is declared in plugin.json with its posture ({@code read} or
 * {@code write}), its scope and its permission. Rahdar checks that permission before the tool
 * runs, on the subscription named by {@code subscription} on the cluster named by {@code clusterId} because the
 * scope is {@code resource}, so the tool itself does not. A key that may not read the subscription is told it does not exist,
 * and the tool never runs. A {@code read} tool is annotated read-only.
 */
@Component
public class NotesTools {

    private final NotesService notes;

    public NotesTools(NotesService notes) {
        this.notes = notes;
    }

    @McpTool(
            name = "acme_notes_list",
            description = "Lists the notes operators left on a subscription, newest first.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public List<NotesController.NoteView> list(
            @McpToolParam(required = true, description = "The cluster's id") String clusterId,
            @McpToolParam(required = true, description = "The subscription's name") String subscription) {
        return notes.list(UUID.fromString(clusterId), subscription).stream().map(NotesController.NoteView::of).toList();
    }
}
