package dev.supergamer2026.paradox;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.network.chat.Component;

/**
 * The Mod Menu settings screen.
 *
 * Client-only, and entirely optional: this class is never loaded unless Mod Menu asks for it, so
 * the mod still runs headless on a dedicated server with neither Mod Menu nor Cloth Config
 * present. Everything here writes straight into {@link ParadoxConfig}'s static fields, which the
 * running loop reads live, so changes take effect on the next death without a restart.
 */
public final class ParadoxModMenu implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            ConfigBuilder builder = ConfigBuilder.create()
                    .setParentScreen(parent)
                    .setTitle(Component.literal("Paradox"))
                    .setSavingRunnable(ParadoxConfig::save);

            ConfigEntryBuilder e = builder.entryBuilder();

            ConfigCategory loop = builder.getOrCreateCategory(Component.literal("The Loop"));
            loop.addEntry(e.startIntSlider(Component.literal("Recording window (seconds)"),
                            ParadoxConfig.recordSeconds, 10, 60)
                    .setDefaultValue(30)
                    .setTooltip(Component.literal("How much history is kept before a death."),
                            Component.literal("§8Also limits how far back the rewind goes."))
                    .setSaveConsumer(v -> ParadoxConfig.recordSeconds = v).build());
            loop.addEntry(e.startIntSlider(Component.literal("Time to fix it (seconds)"),
                            ParadoxConfig.interventionSeconds, 10, 180)
                    .setDefaultValue(30)
                    .setTooltip(Component.literal("How long you get as a ghost."),
                            Component.literal("§8If it runs out, you die for real."))
                    .setSaveConsumer(v -> ParadoxConfig.interventionSeconds = v).build());
            loop.addEntry(e.startDoubleField(Component.literal("Health on being saved"),
                            ParadoxConfig.returnHealth)
                    .setDefaultValue(12.0)
                    .setMin(1.0).setMax(20.0)
                    .setTooltip(Component.literal("Half-hearts you come back with."))
                    .setSaveConsumer(v -> ParadoxConfig.returnHealth = v.floatValue()).build());
            loop.addEntry(e.startBooleanToggle(Component.literal("Give the satchel"),
                            ParadoxConfig.useSatchel)
                    .setDefaultValue(true)
                    .setTooltip(Component.literal("A small ghost kit instead of full creative."))
                    .setSaveConsumer(v -> ParadoxConfig.useSatchel = v).build());
            loop.addEntry(e.startBooleanToggle(Component.literal("Single player only"),
                            ParadoxConfig.singlePlayerOnly)
                    .setDefaultValue(true)
                    .setTooltip(Component.literal("The rewind isn't safe with other players online."))
                    .setSaveConsumer(v -> ParadoxConfig.singlePlayerOnly = v).build());

            loop.addEntry(e.startBooleanToggle(Component.literal("Also work in survival worlds"),
                            ParadoxConfig.survivalWorlds)
                    .setDefaultValue(false)
                    .setTooltip(Component.literal("Made for hardcore. Off = in survival you just die normally."))
                    .setSaveConsumer(v -> ParadoxConfig.survivalWorlds = v).build());

            ConfigCategory replay = builder.getOrCreateCategory(Component.literal("The Replay"));
            replay.addEntry(e.startIntSlider(Component.literal("Replay length (seconds)"),
                            ParadoxConfig.replayWindowSeconds, 2, 20)
                    .setDefaultValue(6)
                    .setTooltip(Component.literal("How much of the lead-up gets replayed on loop."),
                            Component.literal("§8Just a recap, not your timer."))
                    .setSaveConsumer(v -> ParadoxConfig.replayWindowSeconds = v).build());
            replay.addEntry(e.startDoubleField(Component.literal("Replay speed"),
                            ParadoxConfig.replaySpeed)
                    .setDefaultValue(1.25)
                    .setMin(0.25).setMax(4.0)
                    .setTooltip(Component.literal("1.0 is real time. Higher is faster."))
                    .setSaveConsumer(v -> ParadoxConfig.replaySpeed = v).build());

            ConfigCategory rewind = builder.getOrCreateCategory(Component.literal("The Rewind"));
            rewind.addEntry(e.startBooleanToggle(Component.literal("Rewind and re-live"),
                            ParadoxConfig.rewindEnabled)
                    .setDefaultValue(true)
                    .setTooltip(Component.literal("Off: you're just saved where you stand."),
                            Component.literal("§8On: the world rewinds and you play it again."))
                    .setSaveConsumer(v -> ParadoxConfig.rewindEnabled = v).build());
            rewind.addEntry(e.startIntSlider(Component.literal("Rewind distance (seconds)"),
                            ParadoxConfig.rewindSeconds, 3, 30)
                    .setDefaultValue(12)
                    .setTooltip(Component.literal("How far back you go to carry on from."),
                            Component.literal("§8Can't be longer than the recording window."))
                    .setSaveConsumer(v -> ParadoxConfig.rewindSeconds = v).build());
            rewind.addEntry(e.startBooleanToggle(Component.literal("Invulnerable while re-living"),
                            ParadoxConfig.reliveInvulnerable)
                    .setDefaultValue(true)
                    .setTooltip(Component.literal("Off is harder, a stray mob can still get you."))
                    .setSaveConsumer(v -> ParadoxConfig.reliveInvulnerable = v).build());
            rewind.addEntry(e.startIntSlider(Component.literal("Block journal radius"),
                            ParadoxConfig.journalRadius, 6, 24)
                    .setDefaultValue(12)
                    .setTooltip(Component.literal("How far around you block changes are tracked."),
                            Component.literal("§8Bigger = slower."))
                    .setSaveConsumer(v -> ParadoxConfig.journalRadius = v).build());

            ConfigCategory remnant = builder.getOrCreateCategory(Component.literal("The Remnant"));
            remnant.addEntry(e.startBooleanToggle(Component.literal("Remnants can appear"),
                            ParadoxConfig.remnantEnabled)
                    .setDefaultValue(true)
                    .setSaveConsumer(v -> ParadoxConfig.remnantEnabled = v).build());
            remnant.addEntry(e.startDoubleField(Component.literal("Chance to see one"),
                            ParadoxConfig.remnantChance)
                    .setDefaultValue(0.08)
                    .setMin(0.0).setMax(1.0)
                    .setTooltip(Component.literal("Rolled after each successful rewind."),
                            Component.literal("§80.08 = 8%. Set 1.0 to test."))
                    .setSaveConsumer(v -> ParadoxConfig.remnantChance = v).build());
            remnant.addEntry(e.startIntSlider(Component.literal("Seconds before it fades"),
                            ParadoxConfig.remnantLingerSeconds, 10, 300)
                    .setDefaultValue(90)
                    .setTooltip(Component.literal("How long an unclaimed Remnant waits for you."))
                    .setSaveConsumer(v -> ParadoxConfig.remnantLingerSeconds = v).build());

            return builder.build();
        };
    }
}
