package dev.supergamer2026.paradox;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One death, suspended in time.
 *
 * Two clocks run, and keeping them separate is the whole trick. The <b>replay</b> is a briefing:
 * it plays back fast and on a loop so you can see how you died within a few seconds, and keep
 * re-watching it while you work. The <b>intervention timer</b> is the real clock, and it is the
 * only thing that can run out.
 *
 * The world is re-judged every single tick, so the instant you clear the cause you are pulled
 * back into your body. You never wait for the replay to catch up.
 *
 * Nothing here rewinds the world. That is v0.2.
 */
public final class LoopSession {

    public enum Kind { LAVA, FIRE, CONTACT, DROWN, SUFFOCATE, FALL, MOB, EXPLOSION, GENERIC, UNPREVENTABLE }

    /** How far around the death we look for the thing that actually did it. */
    private static final int HAZARD_SCAN = 2;

    private final UUID playerId;
    private final ServerLevel level;

    // --- what we are trying to undo -------------------------------------------------
    private final Kind kind;
    private final BlockPos deathPos;
    private final BlockState deathBlock;
    private final BlockState deathBlockBelow;
    private final String causeLabel;
    /**
     * Every hazardous block within {@link #HAZARD_SCAN} of the death, captured at the moment it
     * happened. Cactus does not kill you from the block you stand in, it kills you from the one
     * next door, so checking a single position was never going to work.
     */
    private final List<BlockPos> hazards = new ArrayList<>();

    // --- the replay ------------------------------------------------------------------
    private final List<Recorder.Frame> frames;
    private final List<Recorder.Track> killerTrack;
    private final EntityType<?> killerType;
    /** The replay only covers the last few seconds, so it starts partway into the buffer. */
    private int replayStart = 0;
    private double replayCursor = 0;

    // --- the real clock ---------------------------------------------------------------
    private int ticksLeft = ParadoxConfig.interventionTicks();

    // --- v0.2: the rewind and the re-live ---------------------------------------------
    private enum Phase { GHOST, RELIVE }
    private Phase phase = Phase.GHOST;
    private final BlockJournal journal;
    private final int deathTick;
    private final int windowStartTick;
    /** What the ghost changed, to be re-applied on schedule while the player lives it. */
    private List<BlockJournal.Change> script = List.of();
    private int[] scriptAt = new int[0];
    private int scriptCursor = 0;
    private int reliveTick = 0;
    private int reliveTicks = 0;
    private Mob revived;                 // what dies on cue during the re-live
    private int scheduledKillTick = -1;
    /** The actual thing that killed them. Usually still alive - it won, after all. */
    private Entity killerEntity;
    private boolean striking = false;
    private int strikeStep = 0;
    /** Rolled once per re-live: does the player get to see what saved them? */
    private boolean glimpse = false;
    private boolean heldElsewhere = false;
    private int glimpseAt = -1;

    // --- entities we spawned and must clean up --------------------------------------
    private Avatars.PastSelf pastSelf;
    private ArmorStand marker;
    private Mob phantom;
    private Vex ghostBody;

    // --- the real life, held in escrow -----------------------------------------------
    private final ItemStack[] stashedInventory;
    private final int stashedFood;
    private final int stashedXpLevel;
    private final float stashedXpProgress;
    private final GameType stashedGameMode;

    private boolean finished = false;
    /**
     * Set for /paradox test. A dry run must never be able to kill the player - it is the thing
     * people will try first, on a world they care about, and a hardcore death is unrecoverable.
     */
    private boolean dryRun = false;

    public LoopSession(ServerPlayer player, DamageSource source, Recorder recorder, BlockJournal journal) {
        this.playerId = player.getUUID();
        this.level = (ServerLevel) player.level();
        this.journal = journal;
        this.deathTick = journal.now();
        this.windowStartTick = deathTick - ParadoxConfig.rewindTicks();
        this.deathPos = player.blockPosition();
        this.deathBlock = level.getBlockState(deathPos);
        this.deathBlockBelow = level.getBlockState(deathPos.below());
        this.frames = recorder.snapshotFrames();

        // Who or what did it. Take the type straight off the damage source: we no longer need a
        // recorded track to know what to spawn, only to know where to walk it.
        Entity killer = source == null ? null : source.getEntity();
        UUID killerId = killer == null ? null : killer.getUUID();
        boolean tracked = killerId != null && recorder.hasTrack(killerId);
        this.killerType = killer != null ? killer.getType() : null;
        this.killerTrack = tracked ? recorder.snapshotTrack(killerId) : List.of();
        this.killerEntity = killer;
        this.causeLabel = source == null ? "unknown" : source.getMsgId();
        this.kind = classify(source, killer);

        scanHazards();

        var inv = player.getInventory();
        this.stashedInventory = new ItemStack[inv.getContainerSize()];
        for (int i = 0; i < stashedInventory.length; i++) {
            stashedInventory[i] = inv.getItem(i).copy();
        }
        this.stashedFood = player.getFoodData().getFoodLevel();
        this.stashedXpLevel = player.experienceLevel;
        this.stashedXpProgress = player.experienceProgress;
        this.stashedGameMode = player.gameMode.getGameModeForPlayer();
    }

    private Kind classify(DamageSource source, Entity killer) {
        if (source == null) return Kind.GENERIC;
        String id = source.getMsgId();
        if (killer instanceof Mob) {
            return id.contains("explosion") ? Kind.EXPLOSION : Kind.MOB;
        }
        return switch (id) {
            case "lava" -> Kind.LAVA;
            case "inFire", "onFire", "campfire" -> Kind.FIRE;
            case "cactus", "sweetBerryBush", "hotFloor", "freeze", "stalagmite", "wither" -> Kind.CONTACT;
            case "drown" -> Kind.DROWN;
            case "inWall", "cramming" -> Kind.SUFFOCATE;
            case "fall", "flyIntoWall" -> Kind.FALL;
            case "explosion", "explosion.player" -> Kind.EXPLOSION;
            case "outOfWorld", "starve", "genericKill" -> Kind.UNPREVENTABLE;
            default -> Kind.GENERIC;
        };
    }

    /** Remember every hurting block near the death so we can check later that they are all gone. */
    private void scanHazards() {
        for (BlockPos p : BlockPos.betweenClosed(
                deathPos.offset(-HAZARD_SCAN, -HAZARD_SCAN, -HAZARD_SCAN),
                deathPos.offset(HAZARD_SCAN, HAZARD_SCAN, HAZARD_SCAN))) {
            if (isHazard(level.getBlockState(p))) hazards.add(p.immutable());
        }
    }

    private boolean isHazard(BlockState s) {
        var fluid = s.getFluidState().getType();
        if (fluid == Fluids.LAVA || fluid == Fluids.FLOWING_LAVA) return true;
        return s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE)
                || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.CACTUS)
                || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.WITHER_ROSE)
                || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE)
                || s.is(Blocks.POINTED_DRIPSTONE);
    }

    /** True once every hazard we recorded has been dealt with. */
    private boolean hazardsClear() {
        for (BlockPos p : hazards) {
            if (isHazard(level.getBlockState(p))) return false;
        }
        return true;
    }

    private boolean worldChanged() {
        return !level.getBlockState(deathPos).equals(deathBlock)
                || !level.getBlockState(deathPos.below()).equals(deathBlockBelow);
    }

    // ---------------------------------------------------------------------------------

    public void begin(ServerPlayer player) {
        player.setHealth(player.getMaxHealth());
        player.setRemainingFireTicks(0);
        player.setAirSupply(300);
        player.fallDistance = 0;
        player.getFoodData().setFoodLevel(20);

        player.getInventory().clearContent();
        player.setGameMode(GameType.CREATIVE);
        player.setInvulnerable(true);
        player.getAbilities().mayfly = true;
        player.getAbilities().flying = true;
        player.onUpdateAbilities();

        if (ParadoxConfig.useSatchel) giveSatchel(player);

        // Wear a vex instead of your own body. A potion effect, NOT setInvisible(): LivingEntity
        // recomputes the invisibility flag from active effects every tick and would undo it.
        player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, -1, 0, false, false, false));
        player.setInvisible(true);
        this.ghostBody = Avatars.spawnGhostBody(level, player);

        spawnHolograms(player);

        player.sendSystemMessage(Component.literal("§c§l☠ YOU DIED — §r§cbut not yet."));
        player.sendSystemMessage(Component.literal(
                "§7Killed by §f" + causeLabel + "§7 at §f"
                        + deathPos.getX() + " " + deathPos.getY() + " " + deathPos.getZ()
                        + "§7. Gear held safe."));
        player.sendSystemMessage(Component.literal("§b" + objective()));
        player.sendSystemMessage(Component.literal(
                "§8The replay loops on fast-forward. Fix it and you snap back the moment you do."));
    }

    /** Plain English for what the player has to actually accomplish. */
    private String objective() {
        return switch (kind) {
            case LAVA -> "Get rid of the lava around the marker.";
            case FIRE -> "Put the fire out around the marker.";
            case CONTACT -> "Break whatever is hurting you near the marker (" + hazards.size() + " found).";
            case DROWN -> "Make an air pocket at the marker.";
            case SUFFOCATE -> "Clear the block at the marker.";
            case FALL -> "Put water or hay where you land.";
            case MOB, EXPLOSION -> "Kill the thing that killed you.";
            default -> "Change whatever it was that got you.";
        };
    }

    private void giveSatchel(ServerPlayer player) {
        var inv = player.getInventory();
        inv.add(new ItemStack(Items.WATER_BUCKET, 1));
        inv.add(new ItemStack(Items.WATER_BUCKET, 1));
        inv.add(new ItemStack(Items.OBSIDIAN, 64));
        inv.add(new ItemStack(Items.COBBLESTONE, 64));
        inv.add(new ItemStack(Items.HAY_BLOCK, 16));
        inv.add(new ItemStack(Items.LADDER, 32));
        inv.add(new ItemStack(Items.TORCH, 32));
        inv.add(new ItemStack(Items.IRON_SWORD, 1));   // matches the Remnant's blade
        inv.add(new ItemStack(Items.BOW, 1));
        inv.add(new ItemStack(Items.ARROW, 64));
        inv.add(new ItemStack(Items.FISHING_ROD, 1));
        inv.add(new ItemStack(Items.SHIELD, 1));
    }

    private void spawnHolograms(ServerPlayer viewer) {
        this.replayStart = Math.max(0, frames.size() - ParadoxConfig.replayWindowTicks());
        this.replayCursor = replayStart;
        if (!frames.isEmpty()) {
            Recorder.Frame f = frames.get(replayStart);
            this.pastSelf = new Avatars.PastSelf(viewer, level);
            pastSelf.spawn(f.x(), f.y(), f.z(), f.yaw(), f.pitch());
        }

        // A fixed marker on the spot, so you know where to work.
        this.marker = Avatars.markerStand(level, deathPos.getX() + 0.5, deathPos.getY(), deathPos.getZ() + 0.5,
                "§c☠ it happens here");

        if (killerType != null) {
            Entity e = killerType.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (e instanceof Mob mob) {
                double sx = deathPos.getX() + 0.5, sy = deathPos.getY(), sz = deathPos.getZ() + 0.5;
                if (!killerTrack.isEmpty()) {
                    Recorder.Track t = killerTrack.get(0);
                    sx = t.x(); sy = t.y(); sz = t.z();
                }
                mob.snapTo(sx, sy, sz);
                mob.setNoAi(true);              // harmless: it is a memory, not a threat
                mob.setSilent(true);
                mob.setGlowingTag(true);
                mob.setCustomName(Component.literal("§c☠ what killed you"));
                mob.setCustomNameVisible(true);
                mob.setPersistenceRequired();
                level.addFreshEntity(mob);
                this.phantom = mob;
            }
        }
    }

    // ---------------------------------------------------------------------------------

    /** Advance one tick. Returns true when the loop has resolved. */
    public boolean tick(ServerPlayer player) {
        if (finished) return true;
        if (phase == Phase.RELIVE) return tickRelive(player);

        advanceReplay();

        player.setHealth(player.getMaxHealth());
        player.fallDistance = 0;

        if (pastSelf != null) {
            pastSelf.setHidden(player.isShiftKeyDown());
        }

        if (ghostBody != null && ghostBody.isAlive()) {
            ghostBody.snapTo(player.getX(), player.getY(), player.getZ());
            ghostBody.setYRot(player.getYRot());
            ghostBody.setYHeadRot(player.getYRot());
        }

        // The whole point of the rewrite: check the world constantly, not once at the end.
        if (adjudicate()) {
            if (ParadoxConfig.rewindEnabled) {
                beginRelive(player);
                return false;            // the session lives on through the re-live
            }
            resolve(player, true, verdictReason(true));
            return true;
        }

        ticksLeft--;
        if (ticksLeft <= 0) {
            journal.dropSince(deathTick);   // the ghost's work dies with them
            resolve(player, false, verdictReason(false));
            return true;
        }

        if (ticksLeft % 4 == 0) {
            double secs = ticksLeft / 20.0;
            player.sendSystemMessage(Component.literal(
                    String.format("%s §f%.1fs §8| §c%s", timeBar(), secs, shortObjective())), true);
        }
        return false;
    }

    /** The replay is a briefing: fast, and it loops so you can keep re-watching while you work. */
    private void advanceReplay() {
        if (frames.isEmpty()) return;
        replayCursor += ParadoxConfig.replaySpeed;
        if (replayCursor >= frames.size()) replayCursor = replayStart;   // loop the tail
        int i = Math.min(frames.size() - 1, (int) replayCursor);

        Recorder.Frame f = frames.get(i);
        if (pastSelf != null) {
            pastSelf.move(f.x(), f.y(), f.z(), f.yaw(), f.pitch());
        }
        if (phantom != null && phantom.isAlive() && !killerTrack.isEmpty()) {
            int ki = killerTrack.size() - (frames.size() - i);   // aligned so both end on the death tick
            if (ki >= 0 && ki < killerTrack.size()) {
                Recorder.Track t = killerTrack.get(ki);
                phantom.snapTo(t.x(), t.y(), t.z());
                phantom.setYRot(t.yaw());
            }
        }
    }

    private String timeBar() {
        int width = 20;
        int total = ParadoxConfig.interventionTicks();
        int filled = Math.max(0, Math.min(width, (int) ((double) ticksLeft / total * width)));
        return "§a" + "|".repeat(filled) + "§8" + "|".repeat(width - filled);
    }

    private String shortObjective() {
        return switch (kind) {
            case LAVA -> "lava still there";
            case FIRE -> "still burning";
            case CONTACT -> hazardsRemaining() + " hazard(s) left";
            case DROWN -> "still underwater";
            case SUFFOCATE -> "still walled in";
            case FALL -> "nothing to land on";
            case MOB, EXPLOSION -> "it still lives";
            default -> "nothing changed yet";
        };
    }

    private int hazardsRemaining() {
        int n = 0;
        for (BlockPos p : hazards) if (isHazard(level.getBlockState(p))) n++;
        return n;
    }

    // ---------------------------------------------------------------------------------

    /** Given the world as it stands right now, would the recorded death still happen? */
    private boolean adjudicate() {
        BlockState now = level.getBlockState(deathPos);
        BlockState below = level.getBlockState(deathPos.below());

        return switch (kind) {
            case UNPREVENTABLE -> false;

            case MOB, EXPLOSION -> phantom != null
                    ? (!phantom.isAlive() || phantom.isRemoved())
                    : worldChanged();

            case FALL -> isSoftLanding(below) || isSoftLanding(now);

            case DROWN -> !isWater(now);

            case SUFFOCATE -> !now.blocksMotion();

            // Lava, fire, cactus, berries, magma, dripstone and anything unmodelled all reduce to
            // the same question: is the thing that hurt you still there?
            default -> hazards.isEmpty() ? worldChanged() : hazardsClear();
        };
    }

    private boolean isWater(BlockState s) {
        var f = s.getFluidState().getType();
        return f == Fluids.WATER || f == Fluids.FLOWING_WATER;
    }

    private boolean isSoftLanding(BlockState s) {
        if (isWater(s)) return true;
        return s.is(Blocks.HAY_BLOCK) || s.is(Blocks.SLIME_BLOCK) || s.is(Blocks.POWDER_SNOW)
                || s.is(Blocks.COBWEB);
    }

    private String verdictReason(boolean saved) {
        if (saved) {
            return switch (kind) {
                case LAVA -> "the lava is gone";
                case FIRE -> "the fire is out";
                case CONTACT -> "you cleared it";
                case DROWN -> "there is air to breathe";
                case SUFFOCATE -> "the way is open";
                case FALL -> "something broke the fall";
                case MOB, EXPLOSION -> "the thing that killed you is dead";
                default -> "you changed what was there";
            };
        }
        return "time ran out";
    }

    // --- v0.2: rewind, then live it again ---------------------------------------------

    /**
     * The trick the whole mod is built around.
     *
     * The ghost has just fixed something. Rather than dropping the player into the aftermath, we
     * wind the world back to where the recording starts, put them in their own body, and then
     * replay the ghost's edits on the original schedule. They watch obsidian place itself and
     * hear the thing that killed them die, and they never see who did it, because it was them.
     */
    private void beginRelive(ServerPlayer player) {
        // Grab the ghost's work BEFORE unwinding it - reverting past the death removes it.
        this.script = journal.changesSince(deathTick);
        int reverted = journal.revertTo(level, windowStartTick);

        cleanup();                       // vex, replica, phantom and marker all go

        // Back into a real body.
        player.removeEffect(MobEffects.INVISIBILITY);
        player.setInvisible(false);
        player.getInventory().clearContent();
        for (int i = 0; i < stashedInventory.length; i++) {
            player.getInventory().setItem(i, stashedInventory[i]);
        }
        player.getFoodData().setFoodLevel(stashedFood);
        player.experienceLevel = stashedXpLevel;
        player.experienceProgress = stashedXpProgress;
        player.setGameMode(stashedGameMode == GameType.CREATIVE ? GameType.SURVIVAL : stashedGameMode);
        player.getAbilities().flying = false;
        player.getAbilities().mayfly = false;
        player.onUpdateAbilities();

        int rewindFrom = Math.max(0, frames.size() - ParadoxConfig.rewindTicks());
        Recorder.Frame f = frames.get(Math.min(rewindFrom, frames.size() - 1));
        player.snapTo(f.x(), f.y(), f.z());
        player.setYRot(f.yaw());
        player.setXRot(f.pitch());
        player.setHealth(Math.max(2.0f, f.health()));
        player.setRemainingFireTicks(0);
        player.setAirSupply(300);
        player.fallDistance = 0;
        player.setInvulnerable(ParadoxConfig.reliveInvulnerable);

        this.reliveTicks = Math.max(40, ParadoxConfig.rewindTicks());
        buildSchedule();

        // Bring the killer back, alive and thinking, so that killing it again means something.
        if (killerType != null && (kind == Kind.MOB || kind == Kind.EXPLOSION)) {
            // The thing that killed you usually did NOT die doing it - it is still standing there.
            // Spawning a copy gave you two bears, one of which was not the one that mattered.
            if (killerEntity instanceof Mob original
                    && original.isAlive() && !original.isRemoved() && original.level() == level) {
                this.revived = original;
            } else {
                // Only conjure one if the original is genuinely gone (a creeper, say).
                Entity e = killerType.create(level, EntitySpawnReason.MOB_SUMMONED);
                if (e instanceof Mob mob) {
                    double sx = deathPos.getX() + 0.5, sy = deathPos.getY(), sz = deathPos.getZ() + 0.5;
                    if (!killerTrack.isEmpty()) {
                        Recorder.Track t = killerTrack.get(0);
                        sx = t.x(); sy = t.y(); sz = t.z();
                    }
                    mob.snapTo(sx, sy, sz);
                    mob.setPersistenceRequired();
                    level.addFreshEntity(mob);
                    this.revived = mob;
                }
            }
            if (revived != null) this.scheduledKillTick = Math.max(5, reliveTicks - 45);
        }

        // Nothing new comes loose while an evoker is still holding the last one. Without this,
        // dying repeatedly is a cheaper route to a fresh three-life Remnant than a rescue.
        this.glimpse = !dryRun && ParadoxConfig.remnantEnabled
                && !Remnant.awaitingRescue(player)
                && !Remnant.hasAny(player)
                && level.getRandom().nextDouble() < ParadoxConfig.remnantChance;
        this.heldElsewhere = !dryRun && Remnant.awaitingRescue(player);
        this.glimpseAt = Math.max(5, reliveTicks - 30);

        this.phase = Phase.RELIVE;
        this.reliveTick = 0;

        player.sendSystemMessage(Component.literal("§d§l↻ REWOUND — §r§d" + (reliveTicks / 20) + " seconds back."));
        player.sendSystemMessage(Component.literal(
                "§7" + reverted + " block(s) put back. Live it again - something is looking after you."));
    }

    /** Space the ghost's edits across the re-live, keeping their original clumping and order. */
    private void buildSchedule() {
        this.scriptAt = new int[script.size()];
        if (script.isEmpty()) return;
        int first = script.get(0).tick();
        int last = script.get(script.size() - 1).tick();
        int span = Math.max(1, last - first);
        int usable = Math.max(1, reliveTicks - 10);
        for (int i = 0; i < script.size(); i++) {
            scriptAt[i] = 1 + (script.get(i).tick() - first) * usable / span;
        }
        this.scriptCursor = 0;
    }

    private boolean tickRelive(ServerPlayer player) {
        reliveTick++;

        // Re-apply the ghost's edits on cue.
        while (scriptCursor < script.size() && scriptAt[scriptCursor] <= reliveTick) {
            BlockJournal.Change c = script.get(scriptCursor++);
            level.setBlock(BlockPos.of(c.pos()), c.after(), Block.UPDATE_ALL);
        }

        if (scheduledKillTick >= 0 && reliveTick >= scheduledKillTick
                && revived != null && revived.isAlive()) {
            striking = true;
            strikeStep = 0;
            scheduledKillTick = -1;
        }
        if (striking) strikeUnseen(player);

        if (ParadoxConfig.reliveInvulnerable) {
            player.setInvulnerable(true);
            player.fallDistance = 0;
        }

        if (reliveTick % 4 == 0) {
            double secs = Math.max(0, (reliveTicks - reliveTick) / 20.0);
            player.sendSystemMessage(Component.literal(
                    String.format("§d↻ re-living §f%.1fs §8| §7%d/%d changes restored",
                            secs, scriptCursor, script.size())), true);
        }

        if (glimpse && reliveTick == glimpseAt) {
            // Stamp it with the death that made it: that is what it will dread forever.
            String origin = (kind == Kind.MOB || kind == Kind.EXPLOSION) && killerType != null
                    ? "MOB:" + net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(killerType)
                    : kind.name();
            Remnant.spawnGlimpse(level, player,
                    journal.now() + ParadoxConfig.remnantLingerSeconds * 20, origin);
            player.sendSystemMessage(Component.literal("§d§oSomething pale is standing there."));
            player.sendSystemMessage(Component.literal("§7Right-click it, quickly, before it goes."));
        }

        if (reliveTick >= reliveTicks) {
            finishRelive(player);
            return true;
        }
        return false;
    }

    /**
     * Something you cannot see kills the thing that killed you.
     *
     * An instant, silent removal read as "the mob just vanished". Three visible blows with hit
     * particles and attack sounds read as a fight you are watching but not fighting - which is
     * the whole idea.
     */
    private void strikeUnseen(ServerPlayer player) {
        if (revived == null || !revived.isAlive() || revived.isRemoved()) {
            striking = false;
            return;
        }
        if (reliveTick % 5 != 0) return;

        strikeStep++;
        boolean last = strikeStep >= 3;
        double hx = revived.getX(), hy = revived.getY() + revived.getBbHeight() * 0.6, hz = revived.getZ();

        level.sendParticles(ParticleTypes.CRIT, hx, hy, hz, 14, 0.25, 0.25, 0.25, 0.35);
        level.sendParticles(ParticleTypes.SWEEP_ATTACK, hx, hy, hz, 1, 0.0, 0.0, 0.0, 0.0);
        level.sendParticles(ParticleTypes.END_ROD, hx, hy, hz, 6, 0.3, 0.3, 0.3, 0.05);
        level.playSound(null, hx, hy, hz,
                last ? SoundEvents.PLAYER_ATTACK_CRIT : SoundEvents.PLAYER_ATTACK_STRONG,
                SoundSource.PLAYERS, 1.0F, last ? 0.9F : 1.1F);

        if (last) {
            revived.hurtServer(level, level.damageSources().magic(), Float.MAX_VALUE);
            player.sendSystemMessage(Component.literal("§7Something you cannot see just killed it."));
            striking = false;
        } else {
            revived.hurtServer(level, level.damageSources().magic(),
                    Math.max(2.0F, revived.getMaxHealth() / 3.0F));
        }
    }

    private void finishRelive(ServerPlayer player) {
        // Anything the schedule did not reach still has to land, or the world is left half-fixed.
        while (scriptCursor < script.size()) {
            BlockJournal.Change c = script.get(scriptCursor++);
            level.setBlock(BlockPos.of(c.pos()), c.after(), Block.UPDATE_ALL);
        }
        finished = true;
        cleanup();
        player.setInvulnerable(false);
        player.removeEffect(MobEffects.INVISIBILITY);
        player.setInvisible(false);
        player.sendSystemMessage(Component.literal("§a§l↺ YOU LIVED. §r§7The loop is closed."));
        player.sendSystemMessage(Component.literal(
                heldElsewhere ? "§5Nothing comes loose. Yours is still being held."
                        : glimpse ? "§d§oThis time you saw it. It is still there."
                        : "§8You never saw who saved you."));
    }

    // ---------------------------------------------------------------------------------

    public void resolve(ServerPlayer player, boolean saved, String why) {
        if (finished) return;
        finished = true;
        cleanup();

        player.getInventory().clearContent();
        for (int i = 0; i < stashedInventory.length; i++) {
            player.getInventory().setItem(i, stashedInventory[i]);
        }
        player.getFoodData().setFoodLevel(stashedFood);
        player.experienceLevel = stashedXpLevel;
        player.experienceProgress = stashedXpProgress;
        player.removeEffect(MobEffects.INVISIBILITY);
        player.setInvisible(false);
        player.setInvulnerable(false);
        player.getAbilities().flying = false;
        player.setGameMode(stashedGameMode == GameType.CREATIVE ? GameType.SURVIVAL : stashedGameMode);
        player.onUpdateAbilities();

        player.snapTo(deathPos.getX() + 0.5, deathPos.getY() + 0.1, deathPos.getZ() + 0.5);

        if (saved) {
            player.setHealth(Math.min(ParadoxConfig.returnHealth, player.getMaxHealth()));
            player.setRemainingFireTicks(0);
            player.fallDistance = 0;
            player.sendSystemMessage(Component.literal("§a§l↺ PARADOX RESOLVED — §r§ayou live."));
            player.sendSystemMessage(Component.literal("§7" + why + ". Everything is where you left it."));
        } else if (dryRun) {
            // Nothing was ever at stake here, so nothing dies. Hand the body back intact.
            player.setHealth(Math.max(ParadoxConfig.returnHealth, 1.0f));
            player.setRemainingFireTicks(0);
            player.fallDistance = 0;
            player.sendSystemMessage(Component.literal("§e§l⏱ DRY RUN OVER — §r§eyou are fine."));
            player.sendSystemMessage(Component.literal(
                    "§7" + why + ", but this was a test. A real death here would have been permanent."));
        } else {
            player.sendSystemMessage(Component.literal("§4§l☠ THE LOOP CLOSED — §r§4" + why + "."));
            player.setHealth(1.0f);
            player.hurtServer(level, level.damageSources().genericKill(), Float.MAX_VALUE);
        }
    }

    public void cleanup() {
        if (pastSelf != null)   { pastSelf.remove();    pastSelf = null; }
        if (phantom != null)    { phantom.discard();    phantom = null; }
        if (marker != null)     { marker.discard();     marker = null; }
        if (ghostBody != null)  { ghostBody.discard();  ghostBody = null; }
    }

    public void markDryRun() { this.dryRun = true; }

    public boolean isDryRun() { return dryRun; }

    public UUID playerId() { return playerId; }
    public boolean isFinished() { return finished; }
    public Kind kind() { return kind; }
    public int remainingTicks() { return Math.max(0, ticksLeft); }
}
