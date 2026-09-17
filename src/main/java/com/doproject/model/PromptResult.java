package com.doproject.model;

public record PromptResult(int index, String prompt, String output, String error) {
    public static PromptResult success(int index, String prompt, String output) {
        return new PromptResult(index, prompt, output, null);
    }

    public static PromptResult failure(int index, String prompt, Exception exception) {
        return new PromptResult(index, prompt, null, exception.getMessage());
    }

    public boolean successful() {
        return error == null;
    }
}
