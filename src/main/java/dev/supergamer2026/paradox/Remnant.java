package dev.supergamer2026.paradox;

import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.illager.Evoker;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.Items;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The thing that saved you, kept.
 *
 * Rarely, a successful re-live leaves something behind. Bind it and it follows you, and it will
 * spend itself to stop your next death outright - before any loop starts, the way a totem does.
 * It carries {@link ParadoxConfig#remnantLives} lives; when the last one goes it poofs.
 *
 * Both its identity and its remaining lives live in the entity's custom name (one star per life),
 * so a bound Remnant survives a world reload without any save format of our own.
 */
public final class Remnant {

    private Remnant() {}

    public static final String GLIMPSE_NAME = "✧ something pale";
    public static final String BOUND_NAME = "Remnant of the Loop";
    private static final char PIP = '✦';

    /** How this one died, kept on the entity so it survives a reload. */
    public static final AttachmentType<String> ORIGIN = AttachmentRegistry.createPersistent(
            Identifier.fromNamespaceAndPath(Paradox.MOD_ID, "remnant_origin"), Codec.STRING);

    /** Set on an evoker that has taken a Remnant. Killing it gives the Remnant back. */
    public static final AttachmentType<String> HELD_ORIGIN = AttachmentRegistry.createPersistent(
            Identifier.fromNamespaceAndPath(Paradox.MOD_ID, "held_origin"), Codec.STRING);
    /** How many lives a glimpse should be worth when bound. One, if it was just torn loose. */
    public static final AttachmentType<Integer> BIND_LIVES = AttachmentRegistry.createPersistent(
            Identifier.fromNamespaceAndPath(Paradox.MOD_ID, "bind_lives"), Codec.INT);

    /**
     * Set on the PLAYER while an evoker is holding their Remnant. While this is set no new one
     * will ever come loose - otherwise dying a few more times is a cheaper, safer way to get a
     * fresh three-life Remnant than walking into a mansion to rescue the one you had.
     */
    public static final AttachmentType<Boolean> AWAITING_RESCUE = AttachmentRegistry.createPersistent(
            Identifier.fromNamespaceAndPath(Paradox.MOD_ID, "awaiting_rescue"), Codec.BOOL);

    /**
     * Set on the PLAYER for as long as one is theirs. One Remnant each, ever - and unlike the
     * in-memory map this survives a relog, a dimension change, or the thing drifting out of
     * re-find range, none of which should hand somebody a second one.
     */
    public static final AttachmentType<Boolean> HAS_REMNANT = AttachmentRegistry.createPersistent(
            Identifier.fromNamespaceAndPath(Paradox.MOD_ID, "has_remnant"), Codec.BOOL);

    /** Set on a caged Remnant, with the cage centre and the owner it was taken from. */
    public static final AttachmentType<Boolean> TRAPPED = AttachmentRegistry.createPersistent(
            Identifier.fromNamespaceAndPath(Paradox.MOD_ID, "trapped"), Codec.BOOL);
    public static final AttachmentType<String> CAGE_AT = AttachmentRegistry.createPersistent(
            Identifier.fromNamespaceAndPath(Paradox.MOD_ID, "cage_at"), Codec.STRING);
    public static final AttachmentType<String> OWNER_ID = AttachmentRegistry.createPersistent(
            Identifier.fromNamespaceAndPath(Paradox.MOD_ID, "owner_id"), Codec.STRING);

    private static final Map<UUID, Vex> BOUND = new HashMap<>();
    private static final Map<Vex, Integer> LINGER = new HashMap<>();
    private static final Map<UUID, Mood> MOODS = new HashMap<>();

    /** Per-owner cooldowns, so the little thing chirps rather than screams. */
    private static final class Mood {
        int lastAmbient, lastFret, lastWarn, lastCheer;
        /** While set, it is darting out to point at something and will not orbit. */
        int dartUntil;
        double dx, dy, dz;
        /** Whatever last drew blood, and when. Animals count as threats but are never harmed. */
        LivingEntity grudge;
        int grudgeAt;
        int lastSwing;
        int delightUntil;        // reading its letter
        int letterAt;            // when to hand the letter over
        int lastDread;
        int bindPressure;        // an evoker trying to take it back
        Evoker caster;
        int wardenUntil;         // being eaten
        Warden warden;
    }

    // ---------------------------------------------------------------------------------

    /** Something just hurt the player. The Remnant takes that personally for a while. */
    public static void rememberAttacker(ServerPlayer player, LivingEntity attacker, int tick) {
        if (attacker == null || attacker == player) return;
        Mood mood = MOODS.get(player.getUUID());
        if (mood == null) return;          // only bother if they actually have one
        mood.grudge = attacker;
        mood.grudgeAt = tick;
    }

    /**
     * A Vex is a Monster, so a naive nearby-hostiles scan finds the Remnant itself at range zero
     * and it spends the rest of its existence duelling its own reflection. Rule out itself, any
     * other Remnant, and the player's own ghost body - everything of ours is invulnerable.
     */
    private static boolean isThreat(LivingEntity candidate, Vex self) {
        return candidate != self
                && !candidate.isInvulnerable()
                && !isBound(candidate)
                && !isGlimpse(candidate);
    }

    /** It will draw a sword on anything, but it will not put a scratch on an animal. */
    private static boolean mayHarm(LivingEntity target) {
        // Not animals, and never its own kind: a Vex is a conscripted Remnant and an Allay is one
        // that got out. It will not draw on either.
        if (target instanceof Vex || target instanceof Allay) return false;
        return target instanceof Enemy && !(target instanceof net.minecraft.world.entity.animal.Animal);
    }

    public static boolean isGlimpse(net.minecraft.world.entity.Entity e) {
        return e instanceof Vex && e.getCustomName() != null
                && e.getCustomName().getString().contains(GLIMPSE_NAME);
    }

    public static boolean isBound(net.minecraft.world.entity.Entity e) {
        return e instanceof Vex && e.getCustomName() != null
                && e.getCustomName().getString().contains(BOUND_NAME);
    }

    /** Lives are counted straight off the nameplate, which is also how they survive a reload. */
    public static int livesOf(Vex vex) {
        if (vex.getCustomName() == null) return 0;
        String s = vex.getCustomName().getString();
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == PIP) n++;
        return n;
    }

    private static void setLives(Vex vex, int lives) {
        vex.setCustomName(Component.literal("§d" + String.valueOf(PIP).repeat(Math.max(0, lives))
                + " §7" + BOUND_NAME));
        vex.setCustomNameVisible(true);
    }

    // ---------------------------------------------------------------------------------

    /** It steps out of the loop and stays. Untamed, and it will drift off again. */
    public static Vex spawnGlimpse(ServerLevel level, ServerPlayer player, int expiryTick, String origin) {
        Vex vex = EntityTypes.VEX.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (vex == null) return null;
        vex.snapTo(player.getX() + 2.0, player.getY() + 1.5, player.getZ() + 2.0);
        vex.setNoAi(true);
        vex.setInvulnerable(true);
        vex.setSilent(true);
        vex.setNoGravity(true);
        vex.setPersistenceRequired();
        vex.setGlowingTag(true);
        vex.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        vex.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        vex.setCustomName(Component.literal("§d" + GLIMPSE_NAME));
        vex.setCustomNameVisible(true);
        ((net.fabricmc.fabric.api.attachment.v1.AttachmentTarget) vex).setAttached(ORIGIN, origin);
        level.addFreshEntity(vex);
        LINGER.put(vex, expiryTick);
        level.playSound(null, vex.getX(), vex.getY(), vex.getZ(),
                ParadoxSounds.REMNANT_APPEAR, SoundSource.AMBIENT, 0.8F, 1.0F);
        level.sendParticles(ParticleTypes.END_ROD, vex.getX(), vex.getY(), vex.getZ(),
                12, 0.3, 0.3, 0.3, 0.02);
        return vex;
    }

    // --- the cage --------------------------------------------------------------------

    /**
     * Illagers keep allays in cages. They keep these the same way.
     *
     * The Remnant is not destroyed and it is not carried around invisibly - it is shut in a box
     * of iron bars near the evoker that took it, where it stays, visible and glowing, until
     * somebody either breaks the bars or kills the thing that put it there.
     */
    private static void imprison(ServerLevel sl, ServerPlayer player, Vex vex, LivingEntity caster) {
        BlockPos spot = findCageSpot(sl, caster.blockPosition());
        buildCage(sl, spot);

        vex.snapTo(spot.getX() + 0.5, spot.getY() + 0.1, spot.getZ() + 0.5);
        vex.setDeltaMovement(0, 0, 0);
        vex.setGlowingTag(true);
        vex.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        vex.setCustomName(Component.literal("§5✦ caged"));
        vex.setCustomNameVisible(true);
        ((net.fabricmc.fabric.api.attachment.v1.AttachmentTarget) vex).setAttached(TRAPPED, true);
        ((net.fabricmc.fabric.api.attachment.v1.AttachmentTarget) vex).setAttached(CAGE_AT, spot.getX() + "," + spot.getY() + "," + spot.getZ());
        ((net.fabricmc.fabric.api.attachment.v1.AttachmentTarget) vex).setAttached(OWNER_ID, player.getUUID().toString());
        LINGER.remove(vex);

        release(player);
        ((net.fabricmc.fabric.api.attachment.v1.AttachmentTarget) player).setAttached(AWAITING_RESCUE, true);

        sl.playSound(null, spot.getX(), spot.getY(), spot.getZ(),
                SoundEvents.EVOKER_CAST_SPELL, SoundSource.HOSTILE, 1.2F, 0.7F);
        player.sendSystemMessage(Component.literal(
                "§7It is caged at §f" + spot.getX() + " " + spot.getY() + " " + spot.getZ()
                        + "§7. Break the bars, or kill the one that put it there."));
    }

    /** Somewhere with room for a box, as near the evoker as we can manage. */
    private static BlockPos findCageSpot(ServerLevel sl, BlockPos near) {
        for (int r = 1; r <= 4; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos c = near.offset(dx, 0, dz);
                    if (sl.getBlockState(c).isAir() && sl.getBlockState(c.above()).isAir()) return c;
                }
            }
        }
        return near.above();
    }

    /** Ten positions: four walls at each of two levels, a floor and a lid. */
    private static List<BlockPos> cageShell(BlockPos c) {
        List<BlockPos> out = new java.util.ArrayList<>();
        out.add(c.below());
        out.add(c.above(2));
        for (int y = 0; y <= 1; y++) {
            out.add(c.offset(1, y, 0));
            out.add(c.offset(-1, y, 0));
            out.add(c.offset(0, y, 1));
            out.add(c.offset(0, y, -1));
        }
        return out;
    }

    private static void buildCage(ServerLevel sl, BlockPos c) {
        for (BlockPos p : cageShell(c)) {
            // Never chew through the mansion: only fill what is empty.
            if (sl.getBlockState(p).isAir()) {
                sl.setBlockAndUpdate(p, Blocks.IRON_BARS.defaultBlockState());
            }
        }
    }

    /** Any gap at all and it is out. */
    private static boolean cageIntact(ServerLevel sl, BlockPos c) {
        for (BlockPos p : cageShell(c)) {
            BlockState st = sl.getBlockState(p);
            if (st.isAir() || (!st.is(Blocks.IRON_BARS) && !st.blocksMotion())) return false;
        }
        return true;
    }

    /**
     * Watch every caged Remnant near the player: if the bars are broken it goes free, and while
     * they are not, it makes the occasional small noise so you can find it.
     */
    public static void tickCaged(ServerLevel sl, ServerPlayer player, int tick) {
        for (Vex vex : sl.getEntitiesOfClass(Vex.class,
                player.getBoundingBox().inflate(64.0),
                v -> v.isAlive() && Boolean.TRUE.equals(((net.fabricmc.fabric.api.attachment.v1.AttachmentTarget) v).getAttached(TRAPPED)))) {

            String at = ((net.fabricmc.fabric.api.attachment.v1.AttachmentTarget) vex).getAttached(CAGE_AT);
            if (at == null) { setFree(sl, vex); continue; }
            String[] xyz = at.split(",");
            BlockPos c = new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));

            // Held in place: it does not drift out through the bars.
            vex.snapTo(c.getX() + 0.5, c.getY() + 0.1, c.getZ() + 0.5);
            vex.setDeltaMovement(0, 0, 0);

            if (!cageIntact(sl, c)) {
                setFree(sl, vex);
                continue;
            }
            if (tick % 160 == 0) {
                sl.playSound(null, c.getX(), c.getY(), c.getZ(),
                        ParadoxSounds.REMNANT_FRET, SoundSource.NEUTRAL, 0.4F, 0.8F);
                sl.sendParticles(ParticleTypes.SCULK_SOUL, c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5,
                        3, 0.15, 0.2, 0.15, 0.0);
            }
        }
    }

    /** Out of the box, back to being claimable - thin, but claimable. */
    private static void setFree(ServerLevel sl, Vex vex) {
        ((net.fabricmc.fabric.api.attachment.v1.AttachmentTarget) vex).setAttached(TRAPPED, null);
        ((net.fabricmc.fabric.api.attachment.v1.AttachmentTarget) vex).setAttached(CAGE_AT, null);
        ((net.fabricmc.fabric.api.attachment.v1.AttachmentTarget) vex).setAttached(BIND_LIVES, Math.max(1, ParadoxConfig.remnantFreedLives));
        vex.setCustomName(Component.literal("§d" + GLIMPSE_NAME));
        vex.setCustomNameVisible(true);
        LINGER.put(vex, sl.getServer().getTickCount() + ParadoxConfig.remnantLingerSeconds * 20);

        sl.playSound(null, vex.getX(), vex.getY(), vex.getZ(),
                ParadoxSounds.REMNANT_APPEAR, SoundSource.NEUTRAL, 1.0F, 0.9F);
        sl.sendParticles(ParticleTypes.END_ROD, vex.getX(), vex.getY() + 0.3, vex.getZ(),
                24, 0.3, 0.3, 0.3, 0.03);

        String owner = ((net.fabricmc.fabric.api.attachment.v1.AttachmentTarget) vex).getAttached(OWNER_ID);
        if (owner != null) {
            ServerPlayer p = sl.getServer().getPlayerList().getPlayer(UUID.fromString(owner));
            if (p != null) {
                ((net.fabricmc.fabric.api.attachment.v1.AttachmentTarget) p).setAttached(AWAITING_RESCUE, null);
                p.sendSystemMessage(Component.literal("§d§l✦ IT IS OUT."));
                p.sendSystemMessage(Component.literal(
                        "§7Thin, and down to " + Math.max(1, ParadoxConfig.remnantFreedLives)
                                + ". Take it back before it drifts off."));
            }
        }
        ((net.fabricmc.fabric.api.attachment.v1.AttachmentTarget) vex).setAttached(OWNER_ID, null);
    }

    /** One each, ever. True from binding until the thing is spent, taken or eaten. */
    public static boolean hasAny(ServerPlayer player) {
        return Boolean.TRUE.equals(((net.fabricmc.fabric.api.attachment.v1.AttachmentTarget) player).getAttached(HAS_REMNANT));
    }

    /** True while an evoker still has theirs caged. No new Remnant appears until it is free. */
    public static boolean awaitingRescue(ServerPlayer player) {
        return Boolean.TRUE.equals(((net.fabricmc.fabric.api.attachment.v1.AttachmentTarget) player).getAttached(AWAITING_RESCUE));
    }

    /** Give up the claim, so a new one may eventually come loose. */
    private static void release(ServerPlayer player) {
        ((net.fabricmc.fabric.api.attachment.v1.AttachmentTarget) player).setAttached(HAS_REMNANT, null);
        BOUND.remove(player.getUUID());
        MOODS.remove(player.getUUID());
    }

    /** Right-clicked. It belongs to them now. */
    public static void bind(ServerPlayer player, Vex vex) {
        if (hasAny(player)) {
            player.sendSystemMessage(Component.literal(
                    "§8It will not come to you. One is already yours."));
            return;
        }
        Integer forced = ((net.fabricmc.fabric.api.attachment.v1.AttachmentTarget) vex).getAttached(BIND_LIVES);
        setLives(vex, forced != null ? Math.max(1, forced) : Math.max(1, ParadoxConfig.remnantLives));
        // It carries the letter over first; the sword comes out once the letter is delivered.
        vex.setItemSlot(EquipmentSlot.MAINHAND, RemnantLetter.create(originOf(vex)));
        vex.setPersistenceRequired();
        ((net.fabricmc.fabric.api.attachment.v1.AttachmentTarget) player).setAttached(AWAITING_RESCUE, null);
        BOUND.put(player.getUUID(), vex);
        Mood fresh = new Mood();
        fresh.letterAt = -1;              // set on the first follow tick, when we know the clock
        MOODS.put(player.getUUID(), fresh);
        LINGER.remove(vex);

        if (player.level() instanceof ServerLevel sl) {
            sl.playSound(null, player.getX(), player.getY(), player.getZ(),
                    ParadoxSounds.REMNANT_BIND, SoundSource.PLAYERS, 1.0F, 1.0F);
            sl.sendParticles(ParticleTypes.HAPPY_VILLAGER, vex.getX(), vex.getY() + 0.4, vex.getZ(),
                    16, 0.4, 0.4, 0.4, 0.05);
        }
        player.sendSystemMessage(Component.literal("§d§l✦ THE REMNANT STAYS WITH YOU."));
        player.sendSystemMessage(Component.literal("§7It will stop your next §f"
                + ParadoxConfig.remnantLives + "§7 death" + (ParadoxConfig.remnantLives == 1 ? "" : "s")
                + " outright. No loop, no ghost - it simply will not let it happen."));
    }

    // ---------------------------------------------------------------------------------

    /**
     * A totem, not a fallback: this runs before any loop is opened, so a player carrying a
     * Remnant never becomes a ghost at all. Returns true if a life was spent.
     */
    public static boolean consume(ServerPlayer player) {
        Vex vex = BOUND.get(player.getUUID());
        if (vex == null || !vex.isAlive() || vex.isRemoved()) {
            BOUND.remove(player.getUUID());
            return false;
        }

        int left = livesOf(vex) - 1;

        player.setHealth(player.getMaxHealth());
        player.setRemainingFireTicks(0);
        player.setAirSupply(300);
        player.fallDistance = 0;
        player.getFoodData().setFoodLevel(Math.max(6, player.getFoodData().getFoodLevel()));

        if (player.level() instanceof ServerLevel sl) {
            sl.playSound(null, player.getX(), player.getY(), player.getZ(),
                    ParadoxSounds.REMNANT_SAVE, SoundSource.PLAYERS, 1.0F, 1.0F);
            // Vex colours - pale white and cold blue - rather than the totem's yellow and green.
            sl.sendParticles(ParticleTypes.END_ROD,
                    player.getX(), player.getY() + 1.0, player.getZ(), 70, 0.4, 0.7, 0.4, 0.28);
            sl.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                    player.getX(), player.getY() + 1.0, player.getZ(), 30, 0.5, 0.6, 0.5, 0.12);
            sl.sendParticles(ParticleTypes.SNOWFLAKE,
                    player.getX(), player.getY() + 1.2, player.getZ(), 25, 0.6, 0.5, 0.6, 0.05);
        }

        if (left > 0) {
            setLives(vex, left);
            player.sendSystemMessage(Component.literal("§d§l✦ THE REMNANT TAKES IT FOR YOU."));
            player.sendSystemMessage(Component.literal(
                    "§7It dims a little. §f" + left + "§7 left."));
        } else {
            poof(vex);
            release(player);
            player.sendSystemMessage(Component.literal("§d§l✦ THE REMNANT SPENDS THE LAST OF ITSELF."));
            player.sendSystemMessage(Component.literal(
                    "§8It came out of a loop you closed once. Now it is gone, and you are still here."));
        }
        return true;
    }

    /** The vanilla going-away: a puff of smoke and a small sad noise. */
    private static void poof(Vex vex) {
        if (vex.level() instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.POOF, vex.getX(), vex.getY() + 0.3, vex.getZ(),
                    30, 0.3, 0.3, 0.3, 0.05);
            sl.playSound(null, vex.getX(), vex.getY(), vex.getZ(),
                    ParadoxSounds.REMNANT_FADE, SoundSource.NEUTRAL, 0.9F, 1.0F);
        }
        vex.discard();
    }

    // ---------------------------------------------------------------------------------

    /**
     * Carry it along and let it have opinions. It has no AI of its own, so everything it
     * appears to feel is done here: it bobs, it sparkles, it frets when something hostile is
     * close, and it gets loud when the player is nearly dead.
     */
    public static void follow(ServerPlayer player, int tick) {
        Vex vex = BOUND.get(player.getUUID());
        if (vex == null) return;
        if (!vex.isAlive() || vex.isRemoved()) {
            release(player);          // destroyed somehow: they are free to find another
            return;
        }
        if (vex.level() != player.level()) {
            BOUND.remove(player.getUUID());   // left behind in another dimension, still theirs
            return;
        }
        if (!(player.level() instanceof ServerLevel sl)) return;

        Mood mood = MOODS.computeIfAbsent(player.getUUID(), k -> new Mood());

        // The letter: carried over, then dropped, then the sword comes out.
        if (mood.letterAt == -1) mood.letterAt = tick + 45;
        if (mood.letterAt > 0 && tick >= mood.letterAt) {
            mood.letterAt = 0;
            RemnantLetter.deliver(sl, player, vex);
            vex.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        }
        RemnantLetter.tick(player, tick);

        // Being eaten by a Warden overrides everything else it might have been doing.
        if (mood.wardenUntil > 0) {
            consumeByWarden(sl, player, vex, mood, tick);
            return;
        }

        if (tick % 10 == 0) kinAndPredators(sl, player, vex, mood, tick);

        // Anything that hurt the player in the last 12 seconds outranks a merely nearby hostile,
        // so a polar bear that mauled you gets dealt with even though it is not a Monster.
        LivingEntity nearest = null;
        if (mood.grudge != null) {
            boolean stale = tick - mood.grudgeAt > 240;
            if (stale || !mood.grudge.isAlive() || mood.grudge.isRemoved()
                    || !isThreat(mood.grudge, vex)
                    || mood.grudge.distanceToSqr(player) > 400) {
                mood.grudge = null;
            } else {
                nearest = mood.grudge;
            }
        }
        if (nearest == null) {
            double best = Double.MAX_VALUE;
            for (Monster m : sl.getEntitiesOfClass(Monster.class,
                    player.getBoundingBox().inflate(10.0), m -> m.isAlive() && isThreat(m, vex))) {
                double d = m.distanceToSqr(player);
                if (d < best) { best = d; nearest = m; }
            }
        }
        boolean danger = nearest != null;
        boolean hurt = player.getHealth() <= 6.0F;

        boolean alarm = danger || hurt;
        double rad = Math.toRadians(player.getYRot());
        double fx = -Math.sin(rad), fz = Math.cos(rad);      // the way the player is facing

        Vec3 dread = dreadSource(sl, player, vex);

        double tx, ty, tz;
        if (dread != null) {
            // It will not orbit while that is nearby. It gets in the way of it instead.
            Vec3 away = new Vec3(dread.x - player.getX(), 0, dread.z - player.getZ());
            double len = Math.max(0.001, away.horizontalDistance());
            tx = player.getX() + away.x / len * 1.3;
            tz = player.getZ() + away.z / len * 1.3;
            ty = player.getY() + 1.4 + Math.sin(tick * 0.12) * 0.10;
            if (tick - mood.lastDread > 60) {
                mood.lastDread = tick;
                sl.playSound(null, vex.getX(), vex.getY(), vex.getZ(),
                        ParadoxSounds.REMNANT_WARN, SoundSource.NEUTRAL, 0.5F, 0.7F);
            }
        } else if (tick < mood.dartUntil) {
            // Out pointing at whatever it just noticed - halfway there, hovering high.
            tx = (player.getX() + mood.dx) * 0.5;
            ty = Math.max(player.getY(), mood.dy) + 1.6;
            tz = (player.getZ() + mood.dz) * 0.5;
        } else if (alarm) {
            // Get in front of their face. A warning you cannot see is not a warning.
            tx = player.getX() + fx * 1.5;
            tz = player.getZ() + fz * 1.5;
            ty = player.getY() + 1.5 + Math.sin(tick * 0.15) * 0.09;      // agitated flutter
        } else {
            // Drift in a slow circle rather than sitting behind the head, so it keeps passing
            // through view and can actually be looked at.
            double orbit = tick * 0.025;
            tx = player.getX() + Math.sin(orbit) * 1.9;
            tz = player.getZ() + Math.cos(orbit) * 1.9;
            ty = player.getY() + 1.55 + Math.sin(tick * 0.08) * 0.18;     // idle bob
        }

        if (vex.distanceToSqr(tx, ty, tz) > 1024) {
            vex.snapTo(tx, ty, tz);
        } else {
            double ease = alarm ? 0.25 : 0.10;
            vex.snapTo(vex.getX() + (tx - vex.getX()) * ease,
                       vex.getY() + (ty - vex.getY()) * ease,
                       vex.getZ() + (tz - vex.getZ()) * ease);
        }
        // Look where it is going, not at you. Constant eye contact was unnerving; it only
        // turns to face its owner when it has something to say.
        boolean facePlayer = (alarm || tick < mood.dartUntil) && dread == null;
        double lx = facePlayer ? player.getX() - vex.getX() : tx - vex.getX();
        double lz = facePlayer ? player.getZ() - vex.getZ() : tz - vex.getZ();
        if (lx * lx + lz * lz > 1.0E-4) {
            float lookYaw = (float) (Math.toDegrees(Math.atan2(lz, lx)) - 90.0);
            vex.setYRot(lookYaw);
            vex.setYHeadRot(lookYaw);
        }

        // While it is out there, it swings. Hostiles bleed for it; animals get shouted at.
        if (dread == null && tick < mood.dartUntil && nearest != null && mayHarm(nearest)
                && tick - mood.lastSwing > 8 && vex.distanceToSqr(nearest) < 16.0) {
            mood.lastSwing = tick;
            sl.playSound(null, nearest.getX(), nearest.getY(), nearest.getZ(),
                    SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.NEUTRAL, 0.7F, 1.6F);
            sl.sendParticles(ParticleTypes.SWEEP_ATTACK,
                    nearest.getX(), nearest.getY() + nearest.getBbHeight() * 0.6, nearest.getZ(),
                    1, 0.0, 0.0, 0.0, 0.0);
            nearest.hurtServer(sl, sl.damageSources().magic(), 3.0F);
        }

        // A faint trail, so you can see it out of the corner of your eye.
        if (tick % 6 == 0) {
            sl.sendParticles(originParticle(vex), vex.getX(), vex.getY() + 0.2, vex.getZ(),
                    dread != null ? 3 : 1, 0.08, 0.08, 0.08, 0.0);
        }
        if (tick < mood.delightUntil) {
            if (tick % 8 == 0) {
                sl.sendParticles(ParticleTypes.HAPPY_VILLAGER, vex.getX(), vex.getY() + 0.4, vex.getZ(),
                        3, 0.25, 0.25, 0.25, 0.02);
            }
            if (tick % 40 == 0) {
                sl.playSound(null, vex.getX(), vex.getY(), vex.getZ(),
                        ParadoxSounds.REMNANT_CHEER, SoundSource.NEUTRAL, 0.5F, 1.8F);
            }
        }

        if (hurt && tick - mood.lastWarn > 50) {
            mood.lastWarn = tick;
            sl.playSound(null, vex.getX(), vex.getY(), vex.getZ(),
                    ParadoxSounds.REMNANT_WARN, SoundSource.NEUTRAL, 0.8F, 1.9F);
            sl.sendParticles(ParticleTypes.SOUL, vex.getX(), vex.getY() + 0.3, vex.getZ(),
                    4, 0.2, 0.2, 0.2, 0.01);
        } else if (danger && tick - mood.lastFret > 70) {
            mood.lastFret = tick;
            // Dart out and hang over the threat for a moment. This is the behaviour you are
            // meant to notice: it spots things before you do.
            // It only goes out for things it is willing to fight. Faced with an animal it
            // simply will not: it stays put and frets, and that is the whole reaction.
            if (mayHarm(nearest) && dread == null) {
                mood.dartUntil = tick + 40;
                mood.dx = nearest.getX(); mood.dy = nearest.getY(); mood.dz = nearest.getZ();
            }
            sl.playSound(null, vex.getX(), vex.getY(), vex.getZ(),
                    ParadoxSounds.REMNANT_FRET, SoundSource.NEUTRAL, 0.55F, 1.7F);
            if (mayHarm(nearest)) {
                sl.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, nearest.getX(),
                        nearest.getY() + nearest.getBbHeight() + 0.4, nearest.getZ(), 6, 0.2, 0.2, 0.2, 0.01);
            }
        } else if (tick - mood.lastAmbient > 120 && sl.getRandom().nextInt(90) == 0) {
            mood.lastAmbient = tick;
            sl.playSound(null, vex.getX(), vex.getY(), vex.getZ(),
                    ParadoxSounds.REMNANT_CHIRP, SoundSource.NEUTRAL, 0.5F, 1.2F);
            sl.sendParticles(ParticleTypes.GLOW, vex.getX(), vex.getY() + 0.3, vex.getZ(),
                    3, 0.2, 0.2, 0.2, 0.0);
        }
    }

    // --- dread: the one death it cannot be calm about ---------------------------------

    private static String originOf(Vex vex) {
        return ((net.fabricmc.fabric.api.attachment.v1.AttachmentTarget) vex).getAttachedOrElse(ORIGIN, "GENERIC");
    }

    private static ParticleOptions originParticle(Vex vex) {
        String o = originOf(vex);
        if (o.startsWith("MOB")) return ParticleTypes.SOUL_FIRE_FLAME;
        return switch (o) {
            case "LAVA", "FIRE" -> ParticleTypes.FLAME;
            case "DROWN" -> ParticleTypes.BUBBLE;
            case "FALL" -> ParticleTypes.CLOUD;
            default -> ParticleTypes.END_ROD;
        };
    }

    /**
     * Where the thing that made it is, if it is nearby. Duty is unconditional - it takes any
     * death - but dread is specific, and this is the only thing it reacts to like this.
     */
    private static Vec3 dreadSource(ServerLevel sl, ServerPlayer player, Vex vex) {
        String origin = originOf(vex);
        BlockPos at = player.blockPosition();

        if (origin.startsWith("MOB:")) {
            String key = origin.substring(4);
            LivingEntity found = null;
            double best = Double.MAX_VALUE;
            for (LivingEntity e : sl.getEntitiesOfClass(LivingEntity.class,
                    player.getBoundingBox().inflate(14.0), LivingEntity::isAlive)) {
                if (!BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals(key)) continue;
                double d = e.distanceToSqr(player);
                if (d < best) { best = d; found = e; }
            }
            return found == null ? null : found.position();
        }

        return switch (origin) {
            case "LAVA", "FIRE", "CONTACT", "GENERIC" -> nearestBadBlock(sl, at, origin);
            case "DROWN" -> sl.getBlockState(at.above()).getFluidState().getType() == Fluids.WATER
                    ? Vec3.atCenterOf(at.above()) : null;
            case "FALL" -> nearestLedge(sl, at);
            default -> null;
        };
    }

    private static Vec3 nearestBadBlock(ServerLevel sl, BlockPos at, String origin) {
        boolean fire = origin.equals("LAVA") || origin.equals("FIRE");
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-4, -3, -4), at.offset(4, 3, 4))) {
            BlockState st = sl.getBlockState(p);
            boolean match = fire
                    ? (st.getFluidState().getType() == Fluids.LAVA
                       || st.getFluidState().getType() == Fluids.FLOWING_LAVA
                       || st.is(Blocks.FIRE) || st.is(Blocks.SOUL_FIRE) || st.is(Blocks.MAGMA_BLOCK))
                    : (st.is(Blocks.CACTUS) || st.is(Blocks.SWEET_BERRY_BUSH)
                       || st.is(Blocks.POINTED_DRIPSTONE) || st.is(Blocks.WITHER_ROSE)
                       || st.is(Blocks.POWDER_SNOW));
            if (match) return Vec3.atCenterOf(p);
        }
        return null;
    }

    /** A drop of more than five blocks within a couple of steps counts as the thing it fears. */
    private static Vec3 nearestLedge(ServerLevel sl, BlockPos at) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos edge = at.offset(dx, -1, dz);
                if (!sl.getBlockState(edge).isAir()) continue;
                int drop = 0;
                BlockPos scan = edge;
                while (drop < 8 && sl.getBlockState(scan).isAir()) { scan = scan.below(); drop++; }
                if (drop > 5) return Vec3.atCenterOf(edge);
            }
        }
        return null;
    }

    // --- its own kind, and the thing that eats it -------------------------------------

    /**
     * Vexes will not fight it and it will not fight them. Evokers - the ones who bind Vexes in
     * the first place - try to take it back. Allays, which got out clean, can give it a life back.
     */
    private static void kinAndPredators(ServerLevel sl, ServerPlayer player, Vex vex, Mood mood, int tick) {
        var box = player.getBoundingBox().inflate(16.0);

        // Truce. A swarm that should be shredding you simply stops.
        for (Vex other : sl.getEntitiesOfClass(Vex.class, box, v -> v != vex && v.isAlive() && !isBound(v))) {
            if (other.getTarget() != null) {
                other.setTarget(null);
                sl.sendParticles(ParticleTypes.GLOW, other.getX(), other.getY() + 0.5, other.getZ(),
                        2, 0.2, 0.2, 0.2, 0.0);
            }
        }

        // An Allay will give back what an Evoker took, for the price of an amethyst shard.
        int lives = livesOf(vex);
        if (lives < ParadoxConfig.remnantLives) {
            for (Allay allay : sl.getEntitiesOfClass(Allay.class, vex.getBoundingBox().inflate(6.0), Allay::isAlive)) {
                for (ItemEntity item : sl.getEntitiesOfClass(ItemEntity.class,
                        allay.getBoundingBox().inflate(6.0),
                        e -> e.isAlive() && e.getItem().is(Items.AMETHYST_SHARD))) {
                    item.getItem().shrink(1);
                    if (item.getItem().isEmpty()) item.discard();
                    setLives(vex, lives + 1);
                    sl.playSound(null, vex.getX(), vex.getY(), vex.getZ(),
                            ParadoxSounds.REMNANT_BIND, SoundSource.NEUTRAL, 0.9F, 1.3F);
                    sl.sendParticles(ParticleTypes.HAPPY_VILLAGER, vex.getX(), vex.getY() + 0.4, vex.getZ(),
                            20, 0.4, 0.4, 0.4, 0.05);
                    player.sendSystemMessage(Component.literal(
                            "§d✦ §7The allay gives something back. §f" + (lives + 1) + "§7 now."));
                    return;
                }
            }
        }

        // The one who binds them wants it back - but it has to actually see it and cast at it.
        // Scanning a 32-block cube meant an evoker three rooms away, through a wall, with no idea
        // you existed, quietly drained the thing. It has to be in the open and in range now.
        Evoker caster = null;
        for (Evoker e : sl.getEntitiesOfClass(Evoker.class,
                vex.getBoundingBox().inflate(12.0), Evoker::isAlive)) {
            if (e.hasLineOfSight(vex)) { caster = e; break; }
        }

        if (caster == null) {
            mood.bindPressure = 0;                 // out of sight, out of danger
            mood.caster = null;
            return;
        }

        if (mood.bindPressure == 0) {
            player.sendSystemMessage(Component.literal(
                    "§5It knows this one. It has been held before."));
            sl.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                    SoundEvents.EVOKER_PREPARE_ATTACK, SoundSource.HOSTILE, 1.0F, 1.0F);
        }
        mood.caster = caster;
        mood.bindPressure += 10;

        // Telegraph: a visible line drawn from the evoker to the Remnant while it winds up.
        for (int i = 0; i <= 6; i++) {
            double t = i / 6.0;
            sl.sendParticles(ParticleTypes.SCULK_SOUL,
                    caster.getX() + (vex.getX() - caster.getX()) * t,
                    caster.getY() + 1.2 + (vex.getY() - caster.getY() - 1.2) * t,
                    caster.getZ() + (vex.getZ() - caster.getZ()) * t, 1, 0.03, 0.03, 0.03, 0.0);
        }

        if (mood.bindPressure >= 60) {             // ~6s of being seen and cast at
            mood.bindPressure = 0;
            int left = livesOf(vex) - 1;
            sl.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                    SoundEvents.EVOKER_CAST_SPELL, SoundSource.HOSTILE, 1.2F, 0.8F);
            sl.playSound(null, vex.getX(), vex.getY(), vex.getZ(),
                    ParadoxSounds.REMNANT_FRET, SoundSource.NEUTRAL, 1.0F, 0.6F);
            sl.sendParticles(ParticleTypes.SCULK_SOUL, vex.getX(), vex.getY() + 0.4, vex.getZ(),
                    24, 0.3, 0.3, 0.3, 0.05);
            if (left > 0) {
                setLives(vex, left);
                player.sendSystemMessage(Component.literal(
                        "§5The evoker pulls a piece of it away. §f" + left + "§5 left."));
            } else {
                // Not destroyed - taken. The evoker is carrying it now, and it shows.
                ((net.fabricmc.fabric.api.attachment.v1.AttachmentTarget) caster).setAttached(HELD_ORIGIN, originOf(vex));
                caster.setGlowingTag(true);
                caster.setCustomName(Component.literal("§5✦ Binder"));
                caster.setCustomNameVisible(true);
                player.sendSystemMessage(Component.literal("§5§lIT IS TAKEN AND CAGED."));
                player.sendSystemMessage(Component.literal(
                        "§8While it is caged, no other will come to you."));
                imprison(sl, player, vex, caster);
                return;   // it is caged and no longer theirs; nothing below applies to it
            }
        }

        // And the thing that eats what leaks between timelines.
        for (Warden warden : sl.getEntitiesOfClass(Warden.class, box, Warden::isAlive)) {
            mood.warden = warden;
            mood.wardenUntil = tick + 70;
            player.sendSystemMessage(Component.literal("§8Something below has noticed it."));
            sl.playSound(null, vex.getX(), vex.getY(), vex.getZ(),
                    ParadoxSounds.REMNANT_WARN, SoundSource.NEUTRAL, 1.0F, 0.5F);
            return;
        }
    }

    /** Dragged in and drained. Nothing stops this: the Deep Dark is where the net does not reach. */
    private static void consumeByWarden(ServerLevel sl, ServerPlayer player, Vex vex, Mood mood, int tick) {
        Warden w = mood.warden;
        if (w == null || !w.isAlive()) { mood.wardenUntil = 0; mood.warden = null; return; }

        double ease = 0.10;
        vex.snapTo(vex.getX() + (w.getX() - vex.getX()) * ease,
                   vex.getY() + (w.getY() + 1.2 - vex.getY()) * ease,
                   vex.getZ() + (w.getZ() - vex.getZ()) * ease);

        // A thread of it pulled out and into the warden.
        for (int i = 0; i < 4; i++) {
            double t = i / 4.0;
            sl.sendParticles(ParticleTypes.SOUL,
                    vex.getX() + (w.getX() - vex.getX()) * t,
                    vex.getY() + (w.getY() + 1.2 - vex.getY()) * t,
                    vex.getZ() + (w.getZ() - vex.getZ()) * t, 1, 0.05, 0.05, 0.05, 0.0);
        }
        if (tick % 10 == 0) {
            sl.playSound(null, vex.getX(), vex.getY(), vex.getZ(), ParadoxSounds.REMNANT_FRET,
                    SoundSource.NEUTRAL, 0.8F, 0.6F);
        }

        if (tick >= mood.wardenUntil) {
            sl.sendParticles(ParticleTypes.SCULK_SOUL, w.getX(), w.getY() + 1.4, w.getZ(),
                    50, 0.6, 0.8, 0.6, 0.05);
            sl.playSound(null, w.getX(), w.getY(), w.getZ(), SoundEvents.WARDEN_HEARTBEAT,
                    SoundSource.HOSTILE, 1.4F, 0.6F);
            vex.discard();
            release(player);
            player.sendSystemMessage(Component.literal("§8§lIT IS EATEN."));
            player.sendSystemMessage(Component.literal(
                    "§8Every star at once. The warden does not take pieces."));
        }
    }

    /**
     * An evoker that took a Remnant has been killed. What it was holding pulls free where it
     * fell - weakened down to a single life, but yours again if you claim it.
     */
    public static void onBinderKilled(ServerLevel sl, LivingEntity dead) {
        if (((net.fabricmc.fabric.api.attachment.v1.AttachmentTarget) dead).getAttached(HELD_ORIGIN) == null) return;
        ((net.fabricmc.fabric.api.attachment.v1.AttachmentTarget) dead).setAttached(HELD_ORIGIN, null);

        // Whatever it locked up nearby comes loose with it.
        for (Vex vex : sl.getEntitiesOfClass(Vex.class, dead.getBoundingBox().inflate(48.0),
                v -> v.isAlive() && Boolean.TRUE.equals(((net.fabricmc.fabric.api.attachment.v1.AttachmentTarget) v).getAttached(TRAPPED)))) {
            String at = ((net.fabricmc.fabric.api.attachment.v1.AttachmentTarget) vex).getAttached(CAGE_AT);
            if (at != null) {
                String[] xyz = at.split(",");
                BlockPos c = new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
                for (BlockPos b : cageShell(c)) {
                    if (sl.getBlockState(b).is(Blocks.IRON_BARS)) {
                        sl.setBlockAndUpdate(b, Blocks.AIR.defaultBlockState());
                    }
                }
            }
            setFree(sl, vex);
        }
    }

    /** It is pleased that you are reading its letter. */
    public static void delight(ServerPlayer player, int tick) {
        Mood mood = MOODS.get(player.getUUID());
        if (mood != null) mood.delightUntil = tick + 220;
    }

    /** A small cheer when its owner wins a fight. */
    public static void cheer(ServerPlayer player, int tick) {
        Vex vex = BOUND.get(player.getUUID());
        if (vex == null || !vex.isAlive()) return;
        Mood mood = MOODS.computeIfAbsent(player.getUUID(), k -> new Mood());
        if (tick - mood.lastCheer < 60) return;
        mood.lastCheer = tick;
        if (player.level() instanceof ServerLevel sl) {
            sl.playSound(null, vex.getX(), vex.getY(), vex.getZ(),
                    ParadoxSounds.REMNANT_CHEER, SoundSource.NEUTRAL, 0.6F, 1.5F);
            sl.sendParticles(ParticleTypes.HAPPY_VILLAGER, vex.getX(), vex.getY() + 0.4, vex.getZ(),
                    5, 0.25, 0.25, 0.25, 0.02);
        }
    }

    // ---------------------------------------------------------------------------------

    public static void expireGlimpses(int nowTick) {
        if (LINGER.isEmpty()) return;
        LINGER.entrySet().removeIf(e -> {
            Vex v = e.getKey();
            if (!v.isAlive() || v.isRemoved()) return true;
            if (nowTick < e.getValue()) return false;
            if (isGlimpse(v) && v.level() instanceof ServerLevel sl) {
                sl.sendParticles(ParticleTypes.POOF, v.getX(), v.getY() + 0.3, v.getZ(),
                        20, 0.3, 0.3, 0.3, 0.04);
                sl.playSound(null, v.getX(), v.getY(), v.getZ(),
                        ParadoxSounds.REMNANT_FADE, SoundSource.AMBIENT, 0.6F, 1.1F);
                v.discard();
            }
            return true;
        });
    }

    /** Right-clicked by its owner: it answers, and tells you how much of it is left. */
    public static void pet(ServerPlayer player, Vex vex) {
        if (player.level() instanceof ServerLevel sl) {
            sl.playSound(null, vex.getX(), vex.getY(), vex.getZ(),
                    ParadoxSounds.REMNANT_CHEER, SoundSource.NEUTRAL, 0.8F, 1.7F);
            sl.sendParticles(ParticleTypes.HAPPY_VILLAGER, vex.getX(), vex.getY() + 0.4, vex.getZ(),
                    10, 0.3, 0.3, 0.3, 0.03);
            sl.sendParticles(ParticleTypes.END_ROD, vex.getX(), vex.getY() + 0.3, vex.getZ(),
                    8, 0.25, 0.25, 0.25, 0.02);
        }
        int lives = livesOf(vex);
        player.sendSystemMessage(Component.literal("§d✦ §7It hums. §f" + lives
                + "§7 death" + (lives == 1 ? "" : "s") + " it will still take for you."));
        Mood mood = MOODS.computeIfAbsent(player.getUUID(), k -> new Mood());
        mood.dartUntil = 0;
    }

    public static boolean has(ServerPlayer player) {
        Vex vex = BOUND.get(player.getUUID());
        return vex != null && vex.isAlive() && !vex.isRemoved();
    }

    public static int lives(ServerPlayer player) {
        Vex vex = BOUND.get(player.getUUID());
        return vex == null || !vex.isAlive() ? 0 : livesOf(vex);
    }

    /** After a relog the map is empty; look around for a Remnant that was already bound. */
    public static void reacquire(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel sl)) return;
        AABB box = player.getBoundingBox().inflate(48.0);
        List<Vex> nearby = sl.getEntitiesOfClass(Vex.class, box, Remnant::isBound);
        if (!nearby.isEmpty()) {
            BOUND.put(player.getUUID(), nearby.get(0));
            MOODS.put(player.getUUID(), new Mood());
        }
    }

    public static void forget(UUID playerId) {
        BOUND.remove(playerId);
        MOODS.remove(playerId);
    }
}
