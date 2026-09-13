package dev.supergamer2026.paradox;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Granting the mod's own advancements from code.
 *
 * Every criterion in {@code data/paradox/advancement/*.json} uses {@code minecraft:impossible} -
 * there is no vanilla trigger for "an Allay gave your Remnant a life back", so nothing here is
 * ever earned by the game itself. It is earned by calling {@link #grant} or {@link #grantCriterion}
 * from the exact line of code where the moment actually happens.
 */
public final class ParadoxAdvancements {

    private ParadoxAdvancements() {}

    /** Award every criterion still outstanding - for an advancement with one criterion, or
     *  several that are all true the moment this fires. */
    public static void grant(ServerPlayer player, String path) {
        AdvancementHolder holder = holder(player, path);
        if (holder == null) return;
        AdvancementProgress progress = player.getAdvancements().getOrStartProgress(holder);
        if (progress.isDone()) return;
        for (String criterion : progress.getRemainingCriteria()) {
            player.getAdvancements().award(holder, criterion);
        }
    }

    /** Award exactly one named criterion - for an advancement that tallies several separate
     *  moments (one per hazard kind) and only completes once every one of them has happened. */
    public static void grantCriterion(ServerPlayer player, String path, String criterion) {
        AdvancementHolder holder = holder(player, path);
        if (holder == null) return;
        player.getAdvancements().award(holder, criterion);
    }

    private static AdvancementHolder holder(ServerPlayer player, String path) {
        var server = player.level().getServer();
        if (server == null) return null;
        return server.getAdvancements().get(Identifier.fromNamespaceAndPath(Paradox.MOD_ID, path));
    }
}
