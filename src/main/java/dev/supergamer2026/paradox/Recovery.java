package dev.supergamer2026.paradox;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.List;

/**
 * While a loop runs, the player's real inventory exists only in memory: they are a creative-mode ghost with
 * an empty inventory, and the world file says exactly that. If the game dies in that window (crash, freeze,
 * power cut), the next load would hand back an empty creative player. So the held state is also written onto
 * the player's saved data for the length of the loop, and put back on the next join if the loop never finished.
 */
public final class Recovery {
    private Recovery() {}

    /** Touching the class registers the attachments; this must run at startup so a saved stash is readable on the first join. */
    public static void init() {}

    public record Stash(String gameMode, List<ItemStack> items, int food, int xpLevel, float xpProgress) {
        static final Codec<Stash> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("mode").forGetter(Stash::gameMode),
                ItemStack.OPTIONAL_CODEC.listOf().fieldOf("items").forGetter(Stash::items),
                Codec.INT.fieldOf("food").forGetter(Stash::food),
                Codec.INT.fieldOf("xp").forGetter(Stash::xpLevel),
                Codec.FLOAT.fieldOf("xpp").forGetter(Stash::xpProgress)).apply(i, Stash::new));
    }

    public static final AttachmentType<Stash> STASH = AttachmentRegistry.createPersistent(
            Identifier.fromNamespaceAndPath(Paradox.MOD_ID, "held_stash"), Stash.CODEC);
    public static final AttachmentType<Boolean> IN_LOOP = AttachmentRegistry.createPersistent(
            Identifier.fromNamespaceAndPath(Paradox.MOD_ID, "in_loop"), Codec.BOOL);

    /** Called the moment a loop starts, before the inventory is emptied. */
    public static void hold(ServerPlayer p, GameType mode, ItemStack[] items, int food, int xpLevel, float xpProgress) {
        List<ItemStack> copy = new ArrayList<>(items.length);
        for (ItemStack s : items) copy.add(s.copy());
        AttachmentTarget t = (AttachmentTarget) p;
        t.setAttached(STASH, new Stash(mode.getName(), copy, food, xpLevel, xpProgress));
        t.setAttached(IN_LOOP, true);
    }

    /** The inventory is back in the player's hands, so the saved copy is no longer needed. */
    public static void releaseStash(ServerPlayer p) {
        ((AttachmentTarget) p).removeAttached(STASH);
    }

    /** The loop is over for good. */
    public static void releaseAll(ServerPlayer p) {
        AttachmentTarget t = (AttachmentTarget) p;
        t.removeAttached(STASH);
        t.removeAttached(IN_LOOP);
    }

    /** On join: if the last session ended in the middle of a loop, undo the ghost and give everything back. */
    public static void recoverOnJoin(ServerPlayer p) {
        AttachmentTarget t = (AttachmentTarget) p;
        if (!Boolean.TRUE.equals(t.getAttached(IN_LOOP))) return;
        if (Paradox.SESSIONS.containsKey(p.getUUID())) return;      // a live loop, not a leftover one
        Stash s = t.getAttached(STASH);
        p.removeEffect(MobEffects.INVISIBILITY);
        p.setInvisible(false);
        p.setInvulnerable(false);
        if (s != null) {
            p.getInventory().clearContent();
            int n = Math.min(s.items().size(), p.getInventory().getContainerSize());
            for (int i = 0; i < n; i++) p.getInventory().setItem(i, s.items().get(i));
            p.getFoodData().setFoodLevel(s.food());
            p.experienceLevel = s.xpLevel();
            p.experienceProgress = s.xpProgress();
            GameType gt = GameType.byName(s.gameMode(), GameType.SURVIVAL);
            p.setGameMode(gt == GameType.CREATIVE ? GameType.SURVIVAL : gt);
        } else {
            p.setGameMode(GameType.SURVIVAL);
        }
        p.getAbilities().flying = false;
        p.getAbilities().mayfly = false;
        p.onUpdateAbilities();
        p.setHealth(Math.min(Math.max(ParadoxConfig.returnHealth, 2.0f), p.getMaxHealth()));
        p.setRemainingFireTicks(0);
        p.fallDistance = 0;
        releaseAll(p);
        Paradox.LOG.info("[paradox] {} was mid-loop when the game closed; gear and game mode restored", p.getGameProfile().name());
        p.sendSystemMessage(Component.literal("§e[Paradox] §7The game closed mid-loop, so you got your gear and game mode back."));
    }
}
