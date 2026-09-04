package dev.supergamer2026.paradox;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import java.util.*;

/**
 * Keeps a rolling window of "what just happened" around one player.
 *
 * Two things get recorded every tick: the player's own transform, and the position of every
 * living entity nearby. The entity tracks are what let us replay the creeper walking up behind
 * you, and they are keyed by UUID so that once we know who killed you we can pull their exact
 * path back out.
 *
 * Everything is a fixed-size ring buffer, so memory is bounded no matter how long the session
 * runs. At the default 30s window this is a few hundred KB per player.
 */
public final class Recorder {

    /** One tick of the player. */
    public record Frame(double x, double y, double z, float yaw, float pitch, float health, boolean sprinting) {}

    /** One tick of some other entity. */
    public record Track(double x, double y, double z, float yaw) {}

    private final int capacity;
    private final ArrayDeque<Frame> frames;
    private final Map<UUID, ArrayDeque<Track>> tracks = new HashMap<>();
    private final Map<UUID, EntityType<?>> trackTypes = new HashMap<>();
    /** Tick index (relative to the start of the buffer) at which each track was last seen. */
    private final Map<UUID, Integer> lastSeen = new HashMap<>();

    private int tick = 0;

    public Recorder(int capacity) {
        this.capacity = capacity;
        this.frames = new ArrayDeque<>(capacity);
    }

    public void record(ServerPlayer player) {
        if (frames.size() >= capacity) frames.removeFirst();
        frames.addLast(new Frame(
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(),
                player.getHealth(), player.isSprinting()));

        ServerLevel level = player.level() instanceof ServerLevel sl ? sl : null;
        if (level != null) {
            AABB box = player.getBoundingBox().inflate(ParadoxConfig.recordRadius);
            List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class, box,
                    e -> e != player && e.isAlive());
            int cap = Math.min(nearby.size(), ParadoxConfig.maxTrackedEntities);
            for (int i = 0; i < cap; i++) {
                LivingEntity e = nearby.get(i);
                UUID id = e.getUUID();
                ArrayDeque<Track> t = tracks.computeIfAbsent(id, k -> new ArrayDeque<>(capacity));
                if (t.size() >= capacity) t.removeFirst();
                t.addLast(new Track(e.getX(), e.getY(), e.getZ(), e.getYRot()));
                trackTypes.put(id, e.getType());
                lastSeen.put(id, tick);
            }
        }

        tick++;
        // Drop tracks for anything we have not seen for a full window; otherwise a busy area
        // would accumulate dead UUIDs forever.
        if (tick % 100 == 0) {
            lastSeen.entrySet().removeIf(en -> {
                if (tick - en.getValue() > capacity) {
                    tracks.remove(en.getKey());
                    trackTypes.remove(en.getKey());
                    return true;
                }
                return false;
            });
        }
    }

    public List<Frame> snapshotFrames() {
        return new ArrayList<>(frames);
    }

    public List<Track> snapshotTrack(UUID id) {
        ArrayDeque<Track> t = tracks.get(id);
        return t == null ? List.of() : new ArrayList<>(t);
    }

    public EntityType<?> trackType(UUID id) {
        return trackTypes.get(id);
    }

    public boolean hasTrack(UUID id) {
        return tracks.containsKey(id);
    }

    public int size() {
        return frames.size();
    }

    public void clear() {
        frames.clear();
        tracks.clear();
        trackTypes.clear();
        lastSeen.clear();
        tick = 0;
    }
}
