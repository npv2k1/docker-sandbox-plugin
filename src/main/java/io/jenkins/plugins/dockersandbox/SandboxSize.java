package io.jenkins.plugins.dockersandbox;

import io.jenkins.plugins.dockersandbox.api.model.Resources;

/** Named sandbox sizes (CPU count / memory MiB). */
public enum SandboxSize {
    MICRO(1, 2048),
    SMALL(2, 4096),
    MEDIUM(4, 8192),
    LARGE(8, 16384),
    XL(16, 32768);

    private final int cpus;
    private final long memoryMib;

    SandboxSize(int cpus, long memoryMib) {
        this.cpus = cpus;
        this.memoryMib = memoryMib;
    }

    public Resources toResources() {
        return new Resources(cpus, memoryMib);
    }

    /** Case-insensitive lookup; blank means {@link #SMALL}. */
    public static SandboxSize parse(String name) {
        if (name == null || name.isBlank()) {
            return SMALL;
        }
        try {
            return valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown sandbox size '" + name + "'; use micro, small, medium, large or xl");
        }
    }
}
