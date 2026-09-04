package dev.supergamer2026.paradox;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An undo log for the world.
 *
 * Rewinding Minecraft is usually described as impossible, and rewinding the *whole* world is.
 * But in any given six seconds the number of blocks that actually change near a player is tiny -
 * a few ores mined, a torch placed, the crater a creeper leaves. So instead of snapshotting
 * regions we keep a cache of the blocks around the player and diff it a few times a second.
 * What comes out is a list of changes small enough to walk backwards.
 *
 * There is no mixin here and no block-change event to hook; this is pure polling, which means it
 * catches everything - player edits, fire spread, explosions, water flow - without caring what
 * caused it.
 */
public final class BlockJournal {

    /** One block that changed, and what it used to be. */
    public record Change(int tick, long pos, BlockState before, BlockState after) {}

    private final int radius;
    private final int keepTicks;
    private final int recenterDistSqr;

    private final Map<Long, BlockState> cache = new HashMap<>();
    private final List<Change> log = new ArrayList<>();

    private BlockPos center;
    private int tick = 0;

    public BlockJournal(int radius, int keepTicks) {
        this.radius = radius;
        this.keepTicks = keepTicks;
        int recenter = Math.max(2, radius / 2);
        this.recenterDistSqr = recenter * recenter;
    }

    public int now() {
        return tick;
    }

    public void tick() {
        tick++;
    }

    /** Diff the cached region against the live world and record whatever moved. */
    public void poll(ServerLevel level, BlockPos playerPos) {
        if (center == null || playerPos.distSqr(center) > recenterDistSqr) {
            recenter(level, playerPos);
            return;                     // a recenter is not a set of changes
        }

        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (Map.Entry<Long, BlockState> e : cache.entrySet()) {
            m.set(BlockPos.of(e.getKey()));
            BlockState current = level.getBlockState(m);
            if (current != e.getValue()) {
                log.add(new Change(tick, e.getKey(), e.getValue(), current));
                e.setValue(current);
            }
        }
        prune();
    }

    /**
     * Move the window with the player: seed anything newly covered, forget anything left behind.
     * Positions in the log are absolute, so the log survives a recenter untouched.
     */
    private void recenter(ServerLevel level, BlockPos playerPos) {
        this.center = playerPos.immutable();
        Map<Long, BlockState> next = new HashMap<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    m.set(playerPos.getX() + dx, playerPos.getY() + dy, playerPos.getZ() + dz);
                    if (level.isOutsideBuildHeight(m)) continue;
                    long key = m.asLong();
                    BlockState known = cache.get(key);
                    next.put(key, known != null ? known : level.getBlockState(m));
                }
            }
        }
        cache.clear();
        cache.putAll(next);
    }

    private void prune() {
        int cutoff = tick - keepTicks;
        if (cutoff <= 0) return;
        log.removeIf(c -> c.tick() < cutoff);
    }

    /** Everything that changed strictly after the given tick, oldest first. */
    public List<Change> changesSince(int sinceTick) {
        List<Change> out = new ArrayList<>();
        for (Change c : log) {
            if (c.tick() > sinceTick) out.add(c);
        }
        return out;
    }

    /**
     * Put the world back the way it was at {@code sinceTick} by walking the log backwards.
     * Uses {@link Block#UPDATE_CLIENTS} so restoring a block does not set off neighbour physics -
     * we want the world as it was, not sand falling and water re-flowing.
     */
    public int revertTo(ServerLevel level, int sinceTick) {
        int reverted = 0;
        for (int i = log.size() - 1; i >= 0; i--) {
            Change c = log.get(i);
            if (c.tick() <= sinceTick) continue;
            BlockPos p = BlockPos.of(c.pos());
            level.setBlock(p, c.before(), Block.UPDATE_CLIENTS);
            cache.put(c.pos(), c.before());
            log.remove(i);
            reverted++;
        }
        return reverted;
    }

    /** Forget the log without touching the world (used when a loop ends in death). */
    public void dropSince(int sinceTick) {
        log.removeIf(c -> c.tick() > sinceTick);
    }

    public void clear() {
        cache.clear();
        log.clear();
        center = null;
    }

    public int logSize() {
        return log.size();
    }
}
