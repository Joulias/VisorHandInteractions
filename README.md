# Visor Hand Interactions

Physical hand interaction for Visor VR on Minecraft 1.21.1. Version 0.3.0 targets the workspace's Visor `0.5.0-snapshot#4` development build.

Move a tracked hand into a supported control to touch it, or hold a dedicated per-hand grab action and move the controller to pull, press, or turn it. While held, the rendered hand attaches to the contacted part of the control. Levers use a natural handle pose; wheels retain the actual rim/spoke position and radius where they were grabbed.

## Interaction coverage

### Physical controls

| Mod/family | Implemented interaction | Notes |
| --- | --- | --- |
| Minecraft and conventional modded levers | Touch or physically pull to toggle | Recognizes vanilla lever blocks and safe `lever`/`*_lever` registry names in enabled namespaces. |
| Create Hand Crank and Copper Valve Handle | Grab and turn through physical rotary detents | Uses Create's normal server-authoritative interaction. |
| Create train controls | Physically move either assembled speed or steering lever | Dedicated grab only. Reuses Create's own key polling, packets, keepalives, and animation. This path is experimental until tested in a live headset session. |
| Create Peculiar Bell, Powered Toggle Latch, Powered Latch, Pulse Timer, Pulse Extender, and Pulse Repeater | Push with physical hand travel | The hand follows the pressed surface. |
| Create: Connected Crank Wheel and Large Crank Wheel | Grab and turn through physical rotary detents | The contacted wheel point is preserved. |
| Create: Simulated Physics Assembler | Grab and move the animated lever | A deliberate grip release performs the native assemble/disassemble action. Tracking loss, menu opening, or cancellation safely releases it without triggering assembly. |
| Create: Simulated Iron Handle and dyed handles | Grab and move through the native handle protocol | Uses the block entity's real grab center and axis. |
| Create: Simulated/Aeronautics throttle and steering wheel | Continuous native motion | The steering direction is corrected and no longer reverses the vehicle's intended travel. Wheel grabs retain the exact contacted rim/spoke point. Sable sublevels are supported. |
| Create: Aeroworks Joystick | Two-axis physical movement | Uses Aeroworks' native console channel and `[-15, 15]` control range. Clean release springs the joystick back. |
| Moving Create contraptions | Grab supported registered interactors | All contact state remains contraption-local so the hand follows translation and rotation. |

Generic handling is intentionally conservative. Powered machinery such as flywheels, cogwheels, water wheels, crushing wheels, and arbitrary controls with private hold protocols are not treated as hand wheels merely because they rotate.

### Menus and setting boards

When an interaction opens a normal Minecraft screen, Visor's existing screen pointer takes over: aim either hand as a mouse pointer, use the primary pointer action to click, and use Visor's normal scroll binding where applicable.

On NeoForge, the addon also discovers every static Create `ValueSettingsBehaviour` at runtime. Grabbing the exact rendered setting box opens its native setting board and assigns the opening hand as the cursor. This contract-based path applies to Create and addon blocks that use Create's public setting behavior, including many of the requested bearings, controllers, sensors, servos, clutches, and timing controls, without maintaining a brittle hardcoded block list. Wrench-only setting boxes work when the wrench is held in the same interacting hand.

This does not promise menu support for a third-party block that uses a private packet, attack/scroll-only gesture, moving-contraption setting box, or a custom screen that bypasses Minecraft's normal screen input. The lower-priority mods from the original request - Absolute Kinematics, Gadgets & Gizmos, Automated Logistics, Create Propulsion, and Create: New Age - were not available in this workspace and are therefore not claimed as protocol-verified integrations.

## Platform support

| Loader | Coverage |
| --- | --- |
| NeoForge | Full exact adapters for Create 6.0.10, Create: Simulated/Aeronautics 1.3.0, Sable 2.0.3, and Aeroworks 1.4.0, plus generic controls. |
| Fabric | Generic static lever, press, and safe rotary behavior. The NeoForge-only Create-family dependencies and native adapters are not bundled. |

The addon is client-side. It sends the same normal interaction or mod packet that the supported control uses; a multiplayer server still needs the content mods whose blocks are being operated.

## Default controls

| Controller profile | Main hand | Off hand |
| --- | --- | --- |
| Oculus Touch | Right grip | Left grip |
| Valve Index | Right grip force | Left grip force |

The actions appear in Visor's **Game** action set as **Grab (Main Hand)** and **Grab (Off Hand)**. Other controller profiles can be bound through Visor's action-binding UI.

Some profiles already map those physical inputs to hotbar or middle-mouse actions. Near a valid physical target, this addon suppresses the conflicting press and release events for that hand. Away from a target, Visor's original binding continues to work.

## Installation

1. Install Minecraft 1.21.1, Java 21, and Visor `0.5.0-snapshot#4` for the same loader.
2. Put the matching `VisorHandInteractions-0.3.0` Fabric or NeoForge jar in the client's `mods` folder.
3. For the exact Create-family integrations, use NeoForge and install the matching supported versions listed above.

## Configuration

The first client start creates `config/visor_hand_interactions.json`:

```json
{
  "version": 2,
  "touchEnabled": true,
  "grabEnabled": true,
  "hapticsEnabled": true,
  "requireEmptyHandForTouch": true,
  "requireEmptyHandForGrab": true,
  "suppressGripConflictsNearTarget": true,
  "contactRadiusMetres": 0.11,
  "fingertipOffsetMetres": 0.08,
  "interactionCooldownTicks": 8,
  "releaseHysteresisTicks": 3,
  "touchNamespaces": [
    "create",
    "create_connected",
    "aeroworks",
    "aeronautics",
    "aeronautics_bundled",
    "simulated",
    "offroad",
    "sable",
    "sablecompanion"
  ],
  "touchBlocks": [
    "minecraft:lever"
  ]
}
```

Existing version 1 files are migrated automatically, preserving current choices while adding the `create_connected` and `aeroworks` namespaces.

`contactRadiusMetres` is the hand probe radius. `fingertipOffsetMetres` moves the probe forward from the controller grip pose. Touch fires only when entering a target and re-arms after leaving it, so a stationary hand does not repeatedly toggle a lever.

A held grab remains attached after the probe leaves a thin collision shape, allowing a full pull or turn. It cancels safely when tracking is lost, a menu or overlay interrupts world interaction, the target becomes invalid, or the VR/client session ends.

## Building

The checked-in `gradle.properties` points at this workspace's local Visor snapshot #4 and optional-mod jars. For another checkout, override the paths with `-Plocal_visor_fabric_jar=<path>`, `-Plocal_visor_neoforge_jar=<path>`, and the corresponding `-Plocal_*_neoforge_jar=<path>` properties for Create, Aeronautics (`aero`), Sable, and Aeroworks. `VISOR_FABRIC_JAR` and `VISOR_NEOFORGE_JAR` are fallbacks only when their Gradle properties are absent.

```powershell
.\gradlew.bat :mod-core:contactGeometryTest :mod-core:grabPoseFrameTest :mod-core:controlDirectionTest build --rerun-tasks --no-daemon
```

Use a Java 21 JDK. Finished jars are written to `mod-fabric/build/libs` and `mod-forge/build/libs`. Optional compatibility dependencies are compile-only and are not bundled into either output.

## Known limits

- Create setting-board discovery currently supports static block entities, not setting boxes attached to an assembled contraption.
- Simulated exposes one global held-control manager, so its controls use first-grab-wins ownership rather than pretending both hands can hold separate native controls.
- Two-handed steering is not implemented.
- Hand attachment is a local render effect and does not alter networked controller poses, so other players do not currently see it.
- There is no physical force feedback or hand collision; haptics confirm successful interactions.
- Automated geometry and packaging tests cannot replace an in-game VR/headset pass, especially for third-party render transforms.

## License

LGPL-3.0-or-later. See `LICENCE.md`.
