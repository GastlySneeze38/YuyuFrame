package com.yuyuframe.launcheragent.celeritas.chunk.terrain.material.parameters;

public enum AlphaCutoffParameter {
    ZERO(0.0f),
    ONE_TENTH(0.1f),
    HALF(0.5f),
    ONE(1.0f);

    private final float cutoff;

    AlphaCutoffParameter(float cutoff) {
        this.cutoff = cutoff;
    }

    public float cutoff() {
        return this.cutoff;
    }

    public static AlphaCutoffParameter valueOf(float val) {
        for (var param : AlphaCutoffParameter.values()) {
            if (Math.abs(param.cutoff() - val) < 0.001f) {
                return param;
            }
        }
        throw new IllegalArgumentException("No alpha cutoff option found for value: " + val);
    }
}
