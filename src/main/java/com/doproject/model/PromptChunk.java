package com.doproject.model;

import java.util.List;

public record PromptChunk(List<String> prompts, int startIndex, boolean terminal) {
    public static PromptChunk data(List<String> prompts, int startIndex) {
        return new PromptChunk(List.copyOf(prompts), startIndex, false);
    }

    public static PromptChunk end() {
        return new PromptChunk(List.of(), 0, true);
    }
}
