package org.vmstudio.visorhandinteractions.core.client.input;

import me.phoenixra.atumvr.api.input.profile.VRInteractionProfileType;
import me.phoenixra.atumvr.api.input.profile.types.OculusTouchProfile;
import me.phoenixra.atumvr.api.input.profile.types.ValveIndexProfile;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.vmstudio.visor.api.client.input.action.ActionBinding;
import org.vmstudio.visor.api.client.input.action.VRActionSet;
import org.vmstudio.visor.api.client.input.action.framework.VRActionKey;
import org.vmstudio.visor.api.common.HandType;

import java.util.Map;

/** A native VR grip action which is consumed by the physical interaction task. */
public final class GrabAction extends VRActionKey {
    public static final String ID_MAIN = "visor_hand_interactions_grab_main";
    public static final String ID_OFFHAND = "visor_hand_interactions_grab_offhand";

    private final HandType hand;

    public GrabAction(@NotNull VRActionSet actionSet, @NotNull HandType hand) {
        // VRActionButton's constructor asks for defaults before subclass fields
        // are initialized, so getDefaultBindings intentionally keys off getId().
        super(
                hand == HandType.MAIN ? ID_MAIN : ID_OFFHAND,
                actionSet,
                hand == HandType.MAIN ? '\uE000' : '\uE001',
                hand == HandType.MAIN
                        ? "visor_hand_interactions.action.grab_main"
                        : "visor_hand_interactions.action.grab_offhand"
        );
        this.hand = hand;
    }

    public @NotNull HandType getHand() {
        return hand;
    }

    @Override
    protected void onPress() {
        // The task reads isPressed() every player tick. No keyboard event is
        // forwarded, despite this using VRActionKey as Visor's public hook for
        // adding actions to the existing gameplay action set.
    }

    @Override
    protected void onRelease() {
    }

    @Override
    protected void onClear() {
    }

    @Override
    public @NotNull Component getName() {
        return Component.translatable(
                getId().equals(ID_MAIN)
                        ? "visor_hand_interactions.action.grab_main"
                        : "visor_hand_interactions.action.grab_offhand"
        );
    }

    @Override
    public @NotNull Map<VRInteractionProfileType, ActionBinding> getDefaultBindings() {
        boolean main = getId().equals(ID_MAIN);
        return Map.of(
                VRInteractionProfileType.OCULUS_TOUCH,
                main
                        ? new ActionBinding(
                                OculusTouchProfile.BUTTON_GRIP_RIGHT,
                                OculusTouchProfile.BUTTON_GRIP_LEFT
                        )
                        : new ActionBinding(
                                OculusTouchProfile.BUTTON_GRIP_LEFT,
                                OculusTouchProfile.BUTTON_GRIP_RIGHT
                        ),
                VRInteractionProfileType.VALVE_INDEX,
                main
                        ? new ActionBinding(
                                ValveIndexProfile.BUTTON_GRIP_FORCE_RIGHT,
                                ValveIndexProfile.BUTTON_GRIP_FORCE_LEFT
                        )
                        : new ActionBinding(
                                ValveIndexProfile.BUTTON_GRIP_FORCE_LEFT,
                                ValveIndexProfile.BUTTON_GRIP_FORCE_RIGHT
                        )
        );
    }
}

