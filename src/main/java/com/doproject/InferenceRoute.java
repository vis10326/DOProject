package com.doproject;

public record InferenceRoute(String name, String endpoint, double costPerRequest) {
    public InferenceRoute {
        if (name == null || name.isBlank() || endpoint == null || costPerRequest < 0) {
            throw new IllegalArgumentException("Inference route settings are invalid");
        }
    }
}
