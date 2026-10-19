package dev.supergamer2026.paradox;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
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
import net.minecraft.world.phys.AABB;
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

    /** The exact reason string an abort passes to {@link #resolve} - shared so resolve() can
     *  tell "walked away on purpose" apart from an actual fix, without a flag of its own. */
    public static final String ABORTED_REASON = "aborted by hand";

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
    /** An explosion's crater, held back from the script so it can go off as ONE blast instead of being dug out block by block. */
    private List<BlockJournal.Change> burst = List.of();
    private boolean burstFired = false;
    private boolean killLanded = false;
    /** "You lived" goes up the moment the danger is gone, not when the re-live runs out. */
    private boolean livedShown = false;
    private int scriptCursor = 0;
    private int reliveTick = 0;
    private int reliveTicks = 0;
    private Mob revived;                 // what dies on cue during the re-live
    private int scheduledKillTick = -1;
    /** Where along the killer's recorded route the ghost actually killed it, so the re-live can match. */
    private int replayKi = -1;
    private int killKi = -1;
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
    /** The ghost's clock. Its name is the goal, and it is the only text on screen. */
    private ServerBossEvent bar;
    private String barGoal;

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
            // /damage and /kill: there is nothing in the world to undo.
            case "outOfWorld", "starve", "genericKill", "generic" -> Kind.UNPREVENTABLE;
            default -> Kind.GENERIC;
        };
    }

    /**
     * What a Remnant born from this death should dread, as a string it can carry through a
     * reload. {@code MOB:<type>} only when the killer is something the dread-lookup can actually
     * go looking for again - a living entity. TNT and an end crystal both explode and are gone,
     * so labelling them {@code MOB:tnt} sent the Remnant hunting for a living entity of a type
     * that will never appear; those fall back to a plain {@code EXPLOSION}, which watches for a
     * lit fuse instead. {@code CONTACT} and {@code SUFFOCATE} keep the exact vanilla cause
     * (cactus, freeze, inWall, cramming, ...) so the Remnant can dread that hazard specifically
     * rather than the whole family of them.
     */
    private String origin() {
        if (kind == Kind.MOB && killerType != null) {
            return "MOB:" + net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(killerType);
        }
        if (kind == Kind.EXPLOSION) {
            return killerEntity instanceof LivingEntity && killerType != null
                    ? "MOB:" + net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(killerType)
                    : "EXPLOSION";
        }
        if (kind == Kind.CONTACT || kind == Kind.SUFFOCATE) {
            return kind.name() + ":" + causeLabel;
        }
        return kind.name();
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

        Recovery.hold(player, stashedGameMode, stashedInventory, stashedFood, stashedXpLevel, stashedXpProgress);
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

        // No words for the death itself: the screen dips dark, a heavy beat, and the clock appears.
        player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 40, 0, false, false, false));
        playTo(player, ParadoxSounds.LOOP_DEATH);
        this.barGoal = goal();
        this.bar = new ServerBossEvent(UUID.randomUUID(), Component.literal(barGoal),
                BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS);
        bar.addPlayer(player);
    }

    private void playTo(ServerPlayer player, net.minecraft.sounds.SoundEvent sound) {
        level.playSound(null, player.getX(), player.getY(), player.getZ(), sound, SoundSource.PLAYERS, 1.0F, 1.0F);
    }

    /** Big centre-screen text instead of chat. */
    static void title(ServerPlayer player, String title, String subtitle, int stay) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(5, stay, 15));
        player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(subtitle)));
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(title)));
    }

    /** What the player has to do, short enough to sit on a boss bar. */
    private String goal() {
        return switch (kind) {
            case LAVA -> "Get rid of the lava";
            case FIRE -> "Put out the fire";
            case CONTACT -> "Break what hurt you (" + hazardsRemaining() + " left)";
            case DROWN -> "Make an air pocket";
            case SUFFOCATE -> "Clear the block you're stuck in";
            case FALL -> "Put water or hay where you land";
            case MOB, EXPLOSION -> killerType != null
                    ? "Kill the " + killerType.getDescription().getString()
                    : "Kill what killed you";
            default -> "Change what killed you";
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
                // Dress it like the real one: a bare-headed copy of a helmeted zombie burns in daylight.
                if (killerEntity instanceof Mob real) {
                    for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
                        mob.setItemSlot(slot, real.getItemBySlot(slot).copy());
                    }
                }
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

        // The death spot is marked by a column of soul fire, not a label.
        if (ticksLeft % 5 == 0) {
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                    deathPos.getX() + 0.5, deathPos.getY() + 1.0, deathPos.getZ() + 0.5, 8, 0.12, 1.0, 0.12, 0.01);
        }
        updateBar(player);
        return false;
    }

    private void updateBar(ServerPlayer player) {
        if (bar == null) return;
        bar.setProgress(Math.max(0F, Math.min(1F, (float) ticksLeft / ParadoxConfig.interventionTicks())));
        String goal = goal();
        if (!goal.equals(barGoal)) {
            barGoal = goal;
            bar.setName(Component.literal(goal));
        }
        // Last five seconds: the bar goes red and a heartbeat counts them down.
        if (ticksLeft <= 100) {
            bar.setColor(BossEvent.BossBarColor.RED);
            if (ticksLeft % 20 == 0) playTo(player, ParadoxSounds.LOOP_HEARTBEAT);
        }
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
                phantom.clearFire();   // a memory doesn't catch fire
                replayKi = ki;
            }
        }
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

            // If you died standing in lava, only that spot counts. Requiring every lava block nearby to go meant
            // fighting the flow from a source you never touched.
            case LAVA -> isHazard(deathBlock)
                    ? !isHazard(now) && !isHazard(level.getBlockState(deathPos.above()))
                    : hazards.isEmpty() ? worldChanged() : hazardsClear();

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
                case LAVA -> "lava's gone";
                case FIRE -> "fire's out";
                case CONTACT -> "you cleared it";
                case DROWN -> "you can breathe";
                case SUFFOCATE -> "you're free";
                case FALL -> "fall broken";
                case MOB, EXPLOSION -> "it's dead";
                default -> "you changed it";
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
        killKi = replayKi;               // the phantom is dead, so this is where it fell
        // Grab the ghost's work BEFORE unwinding it - reverting past the death removes it.
        // The journal only diffs every few ticks, so catch up first or the last edits are lost.
        journal.flush(level);
        this.script = journal.changesSince(deathTick);
        splitBurst();
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
        Recovery.releaseStash(player);   // the real inventory is in their hands again

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
            if (revived != null) {
                if (followsTrack()) {
                    // Walk the route it really took and fall where the ghost dropped it. Its own AI
                    // would beeline at you and the kill would land somewhere you never saw.
                    revived.setNoAi(true);
                    int killTick = reliveTicks - (killerTrack.size() - killKi);
                    this.scheduledKillTick = Math.max(5, Math.min(reliveTicks - 5, killTick - 10));
                } else {
                    this.scheduledKillTick = Math.max(5, reliveTicks - 45);
                }
            }
        }
        sweepStrays("relive start");

        // Nothing new comes loose while an evoker is still holding the last one. Without this,
        // dying repeatedly is a cheaper route to a fresh three-life Remnant than a rescue.
        boolean eligible = !dryRun && ParadoxConfig.remnantEnabled
                && !Remnant.awaitingRescue(player)
                && !Remnant.hasAny(player);
        // And only the day's first eligible rescue gets a shot at all - see Remnant.LAST_ROLL_DAY.
        long day = level.getLevelData().getGameTime() / 24000L;
        // A chance of 1.0 is the testing setting, so it skips the once-a-day limit.
        boolean testing = ParadoxConfig.remnantChance >= 1.0;
        boolean rollsToday = eligible && (testing || Remnant.rollEligible(player, day));
        this.glimpse = rollsToday && level.getRandom().nextDouble() < ParadoxConfig.remnantChance;
        if (rollsToday && !testing) Remnant.markRolled(player, day);
        this.heldElsewhere = !dryRun && Remnant.awaitingRescue(player);
        this.glimpseAt = Math.max(5, reliveTicks - 30);

        this.phase = Phase.RELIVE;
        this.reliveTick = 0;
        Paradox.LOG.info("[paradox] rewind");

        // The rewind is felt, not read: a blink, a pull of sound, the air folding back in.
        player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 12, 0, false, false, false));
        playTo(player, ParadoxSounds.LOOP_REWIND);
        level.sendParticles(ParticleTypes.REVERSE_PORTAL,
                player.getX(), player.getY() + 1.0, player.getZ(), 80, 0.6, 1.0, 0.6, 0.05);
    }

    /** How many ticks apart consecutive placements must land, so a handful of blocks placed in
     *  the same real-time instant still read as one-after-another rather than one cluster. */
    private static final int MIN_PLACEMENT_GAP = 4;

    /** Space the ghost's edits across the re-live, keeping their original order - and, unlike
     *  their original clumping, at least {@link #MIN_PLACEMENT_GAP} ticks apart, so you actually
     *  see and hear each one land instead of a stack of them appearing on the same tick. */
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
        // Enforce the minimum gap forward, then clamp everything back inside the usable window -
        // a long build queues up near the end rather than overrunning into the final countdown.
        for (int i = 1; i < scriptAt.length; i++) {
            scriptAt[i] = Math.max(scriptAt[i], scriptAt[i - 1] + MIN_PLACEMENT_GAP);
        }
        int overflow = scriptAt[scriptAt.length - 1] - usable;
        if (overflow > 0) {
            for (int i = 0; i < scriptAt.length; i++) scriptAt[i] = Math.max(1, scriptAt[i] - overflow);
        }
        this.scriptCursor = 0;
    }

    private boolean tickRelive(ServerPlayer player) {
        reliveTick++;

        // Re-apply the ghost's edits on cue.
        while (scriptCursor < script.size() && scriptAt[scriptCursor] <= reliveTick) {
            applyChange(script.get(scriptCursor++));
        }

        if (followsTrack() && revived != null && revived.isAlive() && !killLanded) {
            int ki = killerTrack.size() - (reliveTicks - reliveTick);
            if (ki >= 0 && ki < killerTrack.size()) {
                Recorder.Track t = killerTrack.get(Math.min(ki, Math.max(0, killKi)));
                revived.snapTo(t.x(), t.y(), t.z());
                revived.setYRot(t.yaw());
                revived.setYHeadRot(t.yaw());
            }
        }

        if (scheduledKillTick >= 0 && reliveTick >= scheduledKillTick
                && revived != null && revived.isAlive()) {
            striking = true;
            strikeStep = 0;
            scheduledKillTick = -1;
        }
        if (striking) strikeUnseen(player);
        if (!burstFired && !burst.isEmpty() && (killLanded || reliveTick >= reliveTicks - 15)) fireBurst();

        if (ParadoxConfig.reliveInvulnerable) {
            player.setInvulnerable(true);
            player.fallDistance = 0;
        }

        if (glimpse && reliveTick == glimpseAt) {
            // Stamp it with the death that made it: that is what it will dread forever.
            Remnant.spawnGlimpse(level, player,
                    journal.now() + ParadoxConfig.remnantLingerSeconds * 20, origin());
            ParadoxAdvancements.grant(player, "something_pale");
            player.sendSystemMessage(Component.literal(
                    "§dRight-click the Remnant to keep it."));
        }

        if (reliveTick >= reliveTicks) {
            finishRelive(player);
            return true;
        }
        return false;
    }

    /**
     * The ghost's edits used to just appear - the kill got three visible blows and a sound, and a
     * placed block got nothing, so you'd look down and it was simply already there. Now every
     * restored block gets the same treatment: its own vanilla break/place sound, in whichever
     * direction it actually went, at the exact moment it lands - no particle burst, just the
     * sound, spaced out one block at a time by {@link #buildSchedule()} so you hear them land in
     * the order you placed them rather than as one cluster.
     */
    private void applyChange(BlockJournal.Change c) {
        BlockPos pos = BlockPos.of(c.pos());
        boolean removal = c.after().isAir() && !c.before().isAir();

        level.playSound(null, pos.getX(), pos.getY(), pos.getZ(),
                removal ? c.before().getSoundType().getBreakSound() : c.after().getSoundType().getPlaceSound(),
                SoundSource.BLOCKS, 0.7F, 1.0F);

        level.setBlock(pos, c.after(), Block.UPDATE_ALL);
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
            if (revived.isAlive()) revived.discard();   // the blow must never be a message with nothing behind it
            killLanded = true;
            striking = false;
            showLived(player);
        } else {
            revived.hurtServer(level, level.damageSources().magic(),
                    Math.max(2.0F, revived.getMaxHealth() / 3.0F));
        }
    }

    /**
     * A creeper kills you by ceasing to exist, so the thing that "killed you" is a copy we conjure
     * and put down on cue. Any OTHER creeper standing near the spot is not part of that story, and
     * leaving it there means the rescue ends with you being blown up by a creeper that was never
     * the one you just killed (seen 2026-10-10: killed on cue, "YOU LIVED", then dead five seconds
     * later). So for explosion deaths, strays of the killer's own type close to the spot go too.
     * Everything else, including every other kind of mob, is left alone.
     */
    private void sweepStrays(String when) {
        if (kind != Kind.EXPLOSION || killerType != EntityTypes.CREEPER) return;
        AABB box = new AABB(deathPos).inflate(16.0);
        List<Mob> strays = level.getEntitiesOfClass(Mob.class, box,
                e -> e.getType() == killerType && e != revived && e.isAlive() && e != phantom);
        for (Mob m : strays) {
            Paradox.LOG.info("[paradox] {}: removing stray {} at {} {} {}", when, killerType.getDescriptionId(),
                    (int) m.getX(), (int) m.getY(), (int) m.getZ());
            m.discard();
        }
    }

    /**
     * The crater of an explosion death is logged a moment after the death itself, so it ends up in the
     * script and was being re-dug one block at a time, each with its own little break sound. Pull the
     * blocks that vanished together with the blast out of the script; {@link #fireBurst()} sets them
     * off as a single explosion.
     */
    private void splitBurst() {
        if (kind != Kind.EXPLOSION) return;
        List<BlockJournal.Change> blast = new ArrayList<>();
        List<BlockJournal.Change> rest = new ArrayList<>();
        for (BlockJournal.Change c : script) {
            boolean removal = c.after().isAir() && !c.before().isAir();
            if (removal && c.tick() <= deathTick + 10) blast.add(c); else rest.add(c);
        }
        if (blast.size() < 4) return;     // not a crater, just a block or two
        this.burst = blast;
        this.script = rest;
    }

    /** All of the crater at once, with the real blast sound and the big puff - and nothing that can hurt. */
    private void fireBurst() {
        burstFired = true;
        double cx = 0, cy = 0, cz = 0;
        for (BlockJournal.Change c : burst) {
            BlockPos pos = BlockPos.of(c.pos());
            cx += pos.getX() + 0.5; cy += pos.getY() + 0.5; cz += pos.getZ() + 0.5;
        }
        cx /= burst.size(); cy /= burst.size(); cz /= burst.size();
        for (BlockJournal.Change c : burst) {
            level.setBlock(BlockPos.of(c.pos()), c.after(), Block.UPDATE_ALL);
        }
        level.playSound(null, cx, cy, cz, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 4.0F, 0.9F);
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, cx, cy, cz, 1, 0.0, 0.0, 0.0, 0.0);
        level.sendParticles(ParticleTypes.EXPLOSION, cx, cy, cz, 12, 1.2, 0.8, 1.2, 0.0);
    }

    private void finishRelive(ServerPlayer player) {
        // Anything the schedule did not reach still has to land, or the world is left half-fixed.
        if (!burstFired && !burst.isEmpty()) fireBurst();
        while (scriptCursor < script.size()) {
            applyChange(script.get(scriptCursor++));
        }
        finished = true;
        cleanup();
        sweepStrays("relive end");
        Recovery.releaseAll(player);
        if (!dryRun) markRescued(player);
        player.setInvulnerable(false);
        player.removeEffect(MobEffects.INVISIBILITY);
        player.setInvisible(false);
        showLived(player);
    }

    private void showLived(ServerPlayer player) {
        if (livedShown) return;
        livedShown = true;
        Paradox.LOG.info("[paradox] lived");
        title(player, "§aYou lived", "", 50);
        playTo(player, ParadoxSounds.LOOP_LIVED);
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
        Recovery.releaseAll(player);

        player.snapTo(deathPos.getX() + 0.5, deathPos.getY() + 0.1, deathPos.getZ() + 0.5);

        if (saved) {
            if (!dryRun && !ABORTED_REASON.equals(why)) markRescued(player);
            player.setHealth(Math.min(ParadoxConfig.returnHealth, player.getMaxHealth()));
            player.setRemainingFireTicks(0);
            player.fallDistance = 0;
            title(player, "§aYou lived", "", 50);
            playTo(player, ParadoxSounds.LOOP_LIVED);
        } else if (dryRun) {
            // Nothing was ever at stake here, so nothing dies. Hand the body back intact.
            player.setHealth(Math.max(ParadoxConfig.returnHealth, 1.0f));
            player.setRemainingFireTicks(0);
            player.fallDistance = 0;
            title(player, "§eTest over", "", 50);
        } else {
            ParadoxAdvancements.grant(player, "the_loop_closes");
            player.setHealth(1.0f);
            player.hurtServer(level, level.damageSources().genericKill(), Float.MAX_VALUE);
        }
    }

    /** A real cause was actually fixed - not a test, not a walk-away. */
    /** A mob death with a recorded route and a known kill point can be replayed exactly. */
    private boolean followsTrack() {
        return (kind == Kind.MOB || kind == Kind.EXPLOSION) && !killerTrack.isEmpty()
                && killKi >= 0 && killKi < killerTrack.size();
    }

    private void markRescued(ServerPlayer player) {
        ParadoxAdvancements.grant(player, "first_rescue");
        if (ticksLeft <= 40) ParadoxAdvancements.grant(player, "cutting_it_close");
        ParadoxAdvancements.grantCriterion(player, "master_of_the_loop", kind.name().toLowerCase());
    }

    public void cleanup() {
        if (pastSelf != null)   { pastSelf.remove();    pastSelf = null; }
        if (phantom != null)    { phantom.discard();    phantom = null; }
        if (marker != null)     { marker.discard();     marker = null; }
        if (ghostBody != null)  { ghostBody.discard();  ghostBody = null; }
        if (bar != null)        { bar.removeAllPlayers(); bar = null; }
    }

    public void markDryRun() { this.dryRun = true; }

    public boolean isDryRun() { return dryRun; }

    public UUID playerId() { return playerId; }
    public boolean isFinished() { return finished; }
    public Kind kind() { return kind; }
    public int remainingTicks() { return Math.max(0, ticksLeft); }
}
