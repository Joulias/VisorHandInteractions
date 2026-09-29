package org.vmstudio.visorhandinteractions.core.client.interaction;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InteractionBridgeRegistry {
    private static final CopyOnWriteArrayList<InteractionBridge> BRIDGES =
            new CopyOnWriteArrayList<>();

    private InteractionBridgeRegistry() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static void register(@NotNull InteractionBridge bridge) {
        if (!BRIDGES.contains(bridge)) {
            BRIDGES.add(bridge);
        }
    }

    public static @NotNull List<InteractionBridge> bridges() {
        return List.copyOf(BRIDGES);
    }
}

