package com.dji.Detector;

public class Ability {
    private static final int DURATION = 3000; // duration is 3 seconds

    private static long activatedAt = Long.MAX_VALUE;

    public static void activate() {
        activatedAt = System.currentTimeMillis();
    }

    public static boolean isActive() {
        long activeFor = System.currentTimeMillis() - activatedAt;

        return activeFor >= 0 && activeFor <= DURATION;
    }
}