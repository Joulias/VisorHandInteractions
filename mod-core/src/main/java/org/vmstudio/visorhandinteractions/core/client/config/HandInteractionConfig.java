package org.vmstudio.visorhandinteractions.core.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.NotNull;
import org.vmstudio.visorhandinteractions.core.common.VisorHandInteractions;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Persistent client configuration for physical world interactions. */
public final class HandInteractionConfig {
    private static final int CONFIG_VERSION = 2;
    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("[a-z0-9_.-]+");
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static HandInteractionConfig instance;

    private final Path configFile;

    private boolean touchEnabled = true;
    private boolean grabEnabled = true;
    private boolean hapticsEnabled = true;
    private boolean requireEmptyHandForTouch = true;
    private boolean requireEmptyHandForGrab = true;
    private boolean suppressGripConflictsNearTarget = true;
    private double contactRadiusMetres = 0.11D;
    private double fingertipOffsetMetres = 0.08D;
    private int interactionCooldownTicks = 8;
    private int releaseHysteresisTicks = 3;

    private Set<String> touchNamespaces = defaultTouchNamespaces();
    private Set<String> touchBlocks = new LinkedHashSet<>(List.of("minecraft:lever"));

    private HandInteractionConfig() {
        this.configFile = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config")
                .resolve(VisorHandInteractions.MOD_ID + ".json");
        load();
    }

    public static synchronized @NotNull HandInteractionConfig getInstance() {
        if (instance == null) {
            instance = new HandInteractionConfig();
        }
        return instance;
    }

    public boolean touchEnabled() {
        return touchEnabled;
    }

    public boolean grabEnabled() {
        return grabEnabled;
    }

    public boolean hapticsEnabled() {
        return hapticsEnabled;
    }

    public boolean requireEmptyHandForTouch() {
        return requireEmptyHandForTouch;
    }

    public boolean requireEmptyHandForGrab() {
        return requireEmptyHandForGrab;
    }

    public boolean suppressGripConflictsNearTarget() {
        return suppressGripConflictsNearTarget;
    }

    public double contactRadiusMetres() {
        return contactRadiusMetres;
    }

    public double fingertipOffsetMetres() {
        return fingertipOffsetMetres;
    }

    public int interactionCooldownTicks() {
        return interactionCooldownTicks;
    }

    public int releaseHysteresisTicks() {
        return releaseHysteresisTicks;
    }

    public boolean isTouchNamespace(@NotNull String namespace) {
        return touchNamespaces.contains(namespace);
    }

    public boolean isTouchBlock(@NotNull String id) {
        return touchBlocks.contains(id);
    }

    private synchronized void load() {
        if (!Files.isRegularFile(configFile)) {
            save();
            return;
        }

        boolean rewrite = false;
        try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            StoredConfig stored = GSON.fromJson(reader, StoredConfig.class);
            if (stored == null) {
                rewrite = true;
            } else {
                touchEnabled = valueOr(stored.touchEnabled, touchEnabled);
                grabEnabled = valueOr(stored.grabEnabled, grabEnabled);
                hapticsEnabled = valueOr(stored.hapticsEnabled, hapticsEnabled);
                requireEmptyHandForTouch = valueOr(
                        stored.requireEmptyHandForTouch,
                        requireEmptyHandForTouch
                );
                requireEmptyHandForGrab = valueOr(
                        stored.requireEmptyHandForGrab,
                        requireEmptyHandForGrab
                );
                suppressGripConflictsNearTarget = valueOr(
                        stored.suppressGripConflictsNearTarget,
                        suppressGripConflictsNearTarget
                );
                contactRadiusMetres = clamp(
                        valueOr(stored.contactRadiusMetres, contactRadiusMetres),
                        0.03D,
                        0.35D
                );
                fingertipOffsetMetres = clamp(
                        valueOr(stored.fingertipOffsetMetres, fingertipOffsetMetres),
                        -0.10D,
                        0.35D
                );
                interactionCooldownTicks = clamp(
                        valueOr(stored.interactionCooldownTicks, interactionCooldownTicks),
                        1,
                        40
                );
                releaseHysteresisTicks = clamp(
                        valueOr(stored.releaseHysteresisTicks, releaseHysteresisTicks),
                        0,
                        10
                );
                touchNamespaces = sanitizeNamespaces(stored.touchNamespaces);
                if (stored.version < 2) {
                    // Version 2 introduced first-class Connected and
                    // Aeroworks interactions. Preserve existing choices while
                    // making those new compatibility paths available to
                    // upgrades as well as fresh installs.
                    touchNamespaces.add("create_connected");
                    touchNamespaces.add("aeroworks");
                }
                touchBlocks = sanitizeBlockIds(stored.touchBlocks);
                rewrite = stored.version != CONFIG_VERSION;
            }
        } catch (IOException | RuntimeException exception) {
            VisorHandInteractions.LOGGER.error(
                    "Could not load hand interaction settings from {}; using defaults",
                    configFile,
                    exception
            );
            return;
        }

        if (rewrite) {
            save();
        }
    }

    private synchronized void save() {
        StoredConfig stored = new StoredConfig();
        stored.version = CONFIG_VERSION;
        stored.touchEnabled = touchEnabled;
        stored.grabEnabled = grabEnabled;
        stored.hapticsEnabled = hapticsEnabled;
        stored.requireEmptyHandForTouch = requireEmptyHandForTouch;
        stored.requireEmptyHandForGrab = requireEmptyHandForGrab;
        stored.suppressGripConflictsNearTarget = suppressGripConflictsNearTarget;
        stored.contactRadiusMetres = contactRadiusMetres;
        stored.fingertipOffsetMetres = fingertipOffsetMetres;
        stored.interactionCooldownTicks = interactionCooldownTicks;
        stored.releaseHysteresisTicks = releaseHysteresisTicks;
        stored.touchNamespaces = List.copyOf(touchNamespaces);
        stored.touchBlocks = List.copyOf(touchBlocks);

        Path parent = configFile.getParent();
        Path temporary = configFile.resolveSibling(configFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(parent);
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(stored, writer);
            }
            try {
                Files.move(
                        temporary,
                        configFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            VisorHandInteractions.LOGGER.error(
                    "Could not save hand interaction settings to {}",
                    configFile,
                    exception
            );
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Preserve the original failure.
            }
        }
    }

    private static @NotNull Set<String> sanitizeNamespaces(List<String> values) {
        Set<String> result = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value == null) {
                    continue;
                }
                String normalized = value.trim().toLowerCase(Locale.ROOT);
                if (NAMESPACE_PATTERN.matcher(normalized).matches()) {
                    result.add(normalized);
                }
            }
        }
        return result.isEmpty() ? defaultTouchNamespaces() : result;
    }

    private static @NotNull Set<String> sanitizeBlockIds(List<String> values) {
        Set<String> result = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value == null) {
                    continue;
                }
                String normalized = value.trim().toLowerCase(Locale.ROOT);
                int separator = normalized.indexOf(':');
                if (separator > 0 && separator < normalized.length() - 1) {
                    result.add(normalized);
                }
            }
        }
        if (result.isEmpty()) {
            result.add("minecraft:lever");
        }
        return result;
    }

    private static @NotNull Set<String> defaultTouchNamespaces() {
        return new LinkedHashSet<>(List.of(
                "create",
                "create_connected",
                "aeroworks",
                "aeronautics",
                "aeronautics_bundled",
                "simulated",
                "offroad",
                "sable",
                "sablecompanion"
        ));
    }

    private static boolean valueOr(Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }

    private static double valueOr(Double value, double fallback) {
        return value == null || !Double.isFinite(value) ? fallback : value;
    }

    private static int valueOr(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class StoredConfig {
        private int version;
        private Boolean touchEnabled;
        private Boolean grabEnabled;
        private Boolean hapticsEnabled;
        private Boolean requireEmptyHandForTouch;
        private Boolean requireEmptyHandForGrab;
        private Boolean suppressGripConflictsNearTarget;
        private Double contactRadiusMetres;
        private Double fingertipOffsetMetres;
        private Integer interactionCooldownTicks;
        private Integer releaseHysteresisTicks;
        private List<String> touchNamespaces;
        private List<String> touchBlocks;
    }
}
