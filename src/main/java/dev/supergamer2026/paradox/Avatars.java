package dev.supergamer2026.paradox;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;

import java.lang.reflect.Field;

/** The bodies we put on screen: past-you wearing your own skin, and the vex you fly around as. */
public final class Avatars {

    private Avatars() {}

    /** Where a hidden replica is parked - far enough down that its hitbox is nobody's problem. */
    private static final double PARKED_Y = -2048.0;

    /**
     * Past-you, wearing your actual skin and carrying your name.
     *
     * 26.x ships a {@link Mannequin} entity - a player-shaped statue with a profile - which does
     * this properly. The earlier attempt built a connectionless {@code ServerPlayer} and drove it
     * with packets; that died on {@code connection.latency()} inside the player-info packet and
     * silently degraded to an armor stand. This needs no packets and no tab-list entry at all.
     */
    public static final class PastSelf {
        private final ServerLevel level;
        private final String name;

        private Mannequin body;
        private ArmorStand fallback;
        private boolean hidden = false;
        private double lastX, lastY, lastZ;
        private float lastYaw;

        public PastSelf(ServerPlayer viewer, ServerLevel level) {
            this.level = level;
            this.name = viewer.getGameProfile().name();
            trySpawnMannequin(viewer);
        }

        private void trySpawnMannequin(ServerPlayer viewer) {
            try {
                Mannequin m = EntityTypes.MANNEQUIN.create(level, EntitySpawnReason.MOB_SUMMONED);
                if (m == null) throw new IllegalStateException("mannequin factory returned null");

                setProfile(m, viewer);
                setImmovable(m, true);

                m.setCustomName(Component.literal("§b" + name));
                m.setCustomNameVisible(true);
                m.setInvulnerable(true);
                m.setSilent(true);
                m.setNoGravity(true);
                m.setGlowingTag(true);
                this.body = m;
            } catch (Throwable t) {
                Paradox.LOG.warn("[paradox] mannequin unavailable, falling back to an armor stand", t);
                this.body = null;
            }
        }

        /**
         * The profile accessor is protected, so this goes through reflection. Field names are
         * real in 26.x (the game ships deobfuscated), which is what makes that safe enough.
         */
        @SuppressWarnings("unchecked")
        private static void setProfile(Mannequin m, ServerPlayer viewer) throws Exception {
            GameProfile src = viewer.getGameProfile();
            // An offline/cracked login often carries no texture property at all. Asking for the
            // profile by name lets the client resolve a skin instead of showing a default one.
            ResolvableProfile profile = src.properties().isEmpty()
                    ? ResolvableProfile.createUnresolved(src.name())
                    : ResolvableProfile.createResolved(src);

            Field f = Mannequin.class.getDeclaredField("DATA_PROFILE");
            f.setAccessible(true);
            m.getEntityData().set((EntityDataAccessor<ResolvableProfile>) f.get(null), profile);
        }

        @SuppressWarnings("unchecked")
        private static void setImmovable(Mannequin m, boolean value) {
            try {
                Field f = Mannequin.class.getDeclaredField("DATA_IMMOVABLE");
                f.setAccessible(true);
                m.getEntityData().set((EntityDataAccessor<Boolean>) f.get(null), value);
            } catch (Throwable ignored) {
                // Cosmetic only - it just stops the statue being shoved around.
            }
        }

        public void spawn(double x, double y, double z, float yaw, float pitch) {
            this.lastX = x; this.lastY = y; this.lastZ = z; this.lastYaw = yaw;
            if (body != null) {
                body.snapTo(x, y, z);
                body.setYRot(yaw);
                body.setYHeadRot(yaw);
                body.setXRot(pitch);
                level.addFreshEntity(body);
            } else {
                this.fallback = glowingStand(level, x, y, z, "§b◇ " + name + " ◇");
            }
        }

        public void move(double x, double y, double z, float yaw, float pitch) {
            this.lastX = x; this.lastY = y; this.lastZ = z; this.lastYaw = yaw;
            if (hidden) return;
            if (body != null && body.isAlive()) {
                body.snapTo(x, y, z);
                body.setYRot(yaw);
                body.setYHeadRot(yaw);
                body.setXRot(pitch);
            } else if (fallback != null && fallback.isAlive()) {
                fallback.snapTo(x, y, z);
                fallback.setYRot(yaw);
            }
        }

        /**
         * Get out of the way. A visible entity always wins the client's raycast, so while the
         * player is crouching we park the replica out of the world entirely - otherwise you
         * cannot place a block on the very spot you are trying to fix.
         */
        public void setHidden(boolean value) {
            if (value == hidden) return;
            this.hidden = value;
            if (body != null && body.isAlive()) {
                if (value) body.snapTo(lastX, PARKED_Y, lastZ);
                else body.snapTo(lastX, lastY, lastZ);
            } else if (fallback != null && fallback.isAlive()) {
                if (value) fallback.snapTo(lastX, PARKED_Y, lastZ);
                else fallback.snapTo(lastX, lastY, lastZ);
            }
        }

        public boolean isHidden() {
            return hidden;
        }

        public void remove() {
            if (body != null) { body.discard(); body = null; }
            if (fallback != null) { fallback.discard(); fallback = null; }
        }
    }

    /**
     * Build a mannequin replica and throw it away, so the skin path can be checked from a server
     * console with no player attached.
     */
    public static String selfTest(ServerLevel level) {
        try {
            Mannequin m = EntityTypes.MANNEQUIN.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (m == null) return "FAILED - mannequin factory returned null";
            Field f = Mannequin.class.getDeclaredField("DATA_PROFILE");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            EntityDataAccessor<ResolvableProfile> acc = (EntityDataAccessor<ResolvableProfile>) f.get(null);
            m.getEntityData().set(acc, ResolvableProfile.createUnresolved("Notch"));
            Field imm = Mannequin.class.getDeclaredField("DATA_IMMOVABLE");
            imm.setAccessible(true);
            m.discard();
            var marker = ArmorStand.class.getDeclaredMethod("setMarker", boolean.class);
            marker.setAccessible(true);
            return "OK - mannequin profile + immovable accessors resolved, marker hitbox reachable";
        } catch (Throwable t) {
            return "FAILED - " + t;
        }
    }

    /**
     * The body the player flies around in. A vex: small, pale, already reads as a spirit.
     * Disarmed on purpose - it spawns holding an iron sword and that would be misleading.
     */
    public static Vex spawnGhostBody(ServerLevel level, ServerPlayer player) {
        Vex vex = EntityTypes.VEX.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (vex == null) return null;
        vex.snapTo(player.getX(), player.getY(), player.getZ());
        vex.setNoAi(true);
        vex.setInvulnerable(true);
        vex.setSilent(true);
        vex.setNoGravity(true);
        vex.setPersistenceRequired();
        vex.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        vex.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        level.addFreshEntity(vex);
        return vex;
    }

    /**
     * A nameplate with no hitbox. {@code setMarker} is private in 26.2, and without it the
     * marker sits on the death spot swallowing every click aimed at the block you need to fix.
     */
    public static ArmorStand markerStand(ServerLevel level, double x, double y, double z, String name) {
        ArmorStand stand = glowingStand(level, x, y, z, name);
        if (stand == null) return null;
        stand.setInvisible(true);
        try {
            var m = ArmorStand.class.getDeclaredMethod("setMarker", boolean.class);
            m.setAccessible(true);
            m.invoke(stand, true);
        } catch (Throwable t) {
            Paradox.LOG.warn("[paradox] could not zero the marker hitbox; it may absorb clicks", t);
        }
        return stand;
    }

    public static ArmorStand glowingStand(ServerLevel level, double x, double y, double z, String name) {
        ArmorStand stand = EntityTypes.ARMOR_STAND.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (stand == null) return null;
        stand.snapTo(x, y, z);
        stand.setNoGravity(true);
        stand.setInvulnerable(true);
        stand.setSilent(true);
        stand.setCustomName(Component.literal(name));
        stand.setCustomNameVisible(true);
        stand.setGlowingTag(true);
        level.addFreshEntity(stand);
        return stand;
    }
}
