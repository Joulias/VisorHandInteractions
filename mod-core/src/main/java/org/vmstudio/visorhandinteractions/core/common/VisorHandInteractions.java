package org.vmstudio.visorhandinteractions.core.common;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class VisorHandInteractions {
    public static final String MOD_ID = "visor_hand_interactions";
    public static final String MOD_NAME = "Visor Hand Interactions";
    public static final Logger LOGGER = LogManager.getLogger(MOD_NAME);

    private VisorHandInteractions() {
        throw new UnsupportedOperationException("Utility class");
    }
}

