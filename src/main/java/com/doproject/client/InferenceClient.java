package com.doproject.client;

public interface InferenceClient {
    String evaluate(String prompt);

    default String evaluate(String prompt, String routeName) {
        return evaluate(prompt);
    }
}
