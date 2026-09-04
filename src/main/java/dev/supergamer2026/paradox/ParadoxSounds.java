package dev.supergamer2026.paradox;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/**
 * Paradox's own sound events.
 *
 * The Remnant used to play vanilla allay sounds directly, which meant the subtitle line read
 * "Allay hurts" - naming a mob that is not in the game and is not what you are looking at. These
 * are real registered events under the paradox namespace, so the subtitle is ours. The audio
 * itself is still vanilla: {@code assets/paradox/sounds.json} points each event at an existing
 * one with {@code "type": "event"}, so the mod ships no audio files.
 */
public final class ParadoxSounds {

    private ParadoxSounds() {}

    public static final SoundEvent REMNANT_CHIRP  = create("remnant.chirp");
    public static final SoundEvent REMNANT_FRET   = create("remnant.fret");
    public static final SoundEvent REMNANT_WARN   = create("remnant.warn");
    public static final SoundEvent REMNANT_CHEER  = create("remnant.cheer");
    public static final SoundEvent REMNANT_BIND   = create("remnant.bind");
    public static final SoundEvent REMNANT_SAVE   = create("remnant.save");
    public static final SoundEvent REMNANT_FADE   = create("remnant.fade");
    public static final SoundEvent REMNANT_APPEAR = create("remnant.appear");

    private static SoundEvent create(String path) {
        Identifier id = Identifier.fromNamespaceAndPath(Paradox.MOD_ID, path);
        return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
    }

    /** Touching the class is enough to run the static initialisers. */
    public static void init() {
        Paradox.LOG.info("[paradox] registered {} sounds", 8);
    }
}
