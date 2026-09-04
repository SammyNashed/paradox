package dev.supergamer2026.paradox;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Paradox — you do not die, you get one shot at stopping it.
 *
 * Record the last 30 seconds, catch the killing blow, hold the inventory in escrow, turn the
 * player loose on the scene as a vex while a replay of the final seconds loops beside them, and
 * judge every tick whether the recorded death would still happen in the world they are building.
 *
 * v0.2 adds the part that makes it worth doing: on success the world is wound back with a block
 * journal, the player is put in their own body at the start of the window, and the ghost's edits
 * are replayed on schedule. You watch yourself get saved by something you cannot see.
 *
 * Deliberately server-side only. No mixins, no client code, no custom rendering — the replay
 * is built out of real entities so it works on a dedicated server and can be tested headlessly.
 */
public final class Paradox implements ModInitializer {

    public static final String MOD_ID = "paradox";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static final Map<UUID, Recorder> RECORDERS = new HashMap<>();
    private static final Map<UUID, LoopSession> SESSIONS = new HashMap<>();
    private static final Map<UUID, BlockJournal> JOURNALS = new HashMap<>();

    @Override
    public void onInitialize() {
        ParadoxConfig.load();
        ParadoxSounds.init();
        LOG.info("[paradox] armed - {}s window, unlimited={}", ParadoxConfig.recordSeconds, ParadoxConfig.unlimited);

        // 1. Keep a rolling record of everything that just happened to every player.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                UUID id = player.getUUID();

                // The journal keeps running through the ghost phase - that is how we capture
                // what the ghost changes and can replay it during the re-live.
                BlockJournal journal = JOURNALS.computeIfAbsent(id,
                        k -> new BlockJournal(ParadoxConfig.journalRadius,
                                ParadoxConfig.recordTicks() + ParadoxConfig.interventionTicks() + 200));
                journal.tick();
                if (journal.now() % 4 == 0 && player.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                    journal.poll(sl, player.blockPosition());
                }

                // Server tick count, not the journal's own counter: the grudge timer and the
                // cheer cooldown are both stamped with it, and mixing the two clocks makes
                // "12 seconds ago" mean nothing.
                Remnant.follow(player, server.getTickCount());
                Remnant.expireGlimpses(journal.now());
                RemnantLetter.tick(player, server.getTickCount());
                if (journal.now() % 20 == 0 && player.level() instanceof net.minecraft.server.level.ServerLevel csl) {
                    Remnant.tickCaged(csl, player, server.getTickCount());
                }

                LoopSession session = SESSIONS.get(id);

                if (session != null) {
                    if (session.tick(player)) {
                        SESSIONS.remove(id);
                        Recorder r = RECORDERS.get(id);
                        if (r != null) r.clear();   // fresh slate after a loop resolves
                    }
                    continue;                        // do not record the ghost phase
                }

                if (player.isAlive() && !player.isSpectator()) {
                    RECORDERS.computeIfAbsent(id, k -> new Recorder(ParadoxConfig.recordTicks()))
                             .record(player);
                }
            }
        });

        // 2. Catch the killing blow.
        ServerPlayerEvents.ALLOW_DEATH.register((player, source, amount) -> {
            UUID id = player.getUUID();

            // A loop is closing on them: this death is the real one.
            if (SESSIONS.containsKey(id)) return true;

            // A Remnant is a totem, not a fallback. It fires before anything else, so a player
            // carrying one never becomes a ghost at all.
            if (Remnant.consume(player)) return false;

            if (ParadoxConfig.singlePlayerOnly
                    && player.level().getServer() != null
                    && player.level().getServer().getPlayerList().getPlayerCount() > 1) {
                LOG.info("[paradox] more than one player online, standing down");
                return true;
            }

            BlockJournal journal = JOURNALS.get(id);
            Recorder recorder = RECORDERS.get(id);
            if (recorder == null || journal == null || recorder.size() < 20) {
                LOG.info("[paradox] not enough recorded history, allowing death");
                return true;
            }

            try {
                LoopSession session = new LoopSession(player, source, recorder, journal);
                if (session.kind() == LoopSession.Kind.UNPREVENTABLE) {
                    // No point holding them for 30s over something nothing can change.
                    LOG.info("[paradox] {} is unpreventable, allowing death", source == null ? "?" : source.getMsgId());
                    return true;
                }
                session.begin(player);
                SESSIONS.put(id, session);
                LOG.info("[paradox] loop opened for {} ({})", player.getGameProfile().name(), session.kind());
                return false;   // deny the death; the loop owns this player now
            } catch (Exception e) {
                LOG.error("[paradox] failed to open loop, allowing death rather than breaking the world", e);
                return true;
            }
        });

        // 3. If a player logs out mid-loop, do not leave them stuck as a creative ghost.
        ServerPlayerEvents.LEAVE.register(player -> {
            LoopSession session = SESSIONS.get(player.getUUID());
            if (session != null && !session.isFinished()) {
                // Resolve while the session is still registered, so the killing blow it deals
                // is seen as "already looping" and does not open a fresh loop on top.
                session.resolve(player, false, "you left mid-loop");
            }
            SESSIONS.remove(player.getUUID());
            RECORDERS.remove(player.getUUID());
            JOURNALS.remove(player.getUUID());
            Remnant.forget(player.getUUID());
            RemnantLetter.forget(player.getUUID());
        });

        // 4. Right-click a glimpse to keep it.
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            if (entity instanceof Vex vex && Remnant.isGlimpse(vex)) {
                Remnant.bind(sp, vex);
                return InteractionResult.SUCCESS;
            }
            if (entity instanceof Vex vex && Remnant.isBound(vex)) {
                Remnant.pet(sp, vex);
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });

        // Anything that draws the player's blood gets remembered, animal or not.
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (entity instanceof ServerPlayer sp && taken > 0.0F
                    && source.getEntity() instanceof net.minecraft.world.entity.LivingEntity attacker
                    && !attacker.isInvulnerable()) {
                Remnant.rememberAttacker(sp, attacker, sp.level().getServer().getTickCount());
            }
        });

        // Opening the letter delights it, and starts the clock on the letter coming apart.
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (player instanceof ServerPlayer sp && RemnantLetter.isLetter(sp.getItemInHand(hand))) {
                RemnantLetter.onOpened(sp, sp.level().getServer().getTickCount());
            }
            return InteractionResult.PASS;
        });

        // Kill the evoker that took your Remnant and it comes loose.
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                Remnant.onBinderKilled(sl, entity);
            }
        });

        // It cheers when its owner wins a fight.
        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((world, killer, killed, source) -> {
            if (killer instanceof ServerPlayer sp) {
                Remnant.cheer(sp, world.getServer().getTickCount());
            }
        });

        // A bound Remnant lives in the world, not in our map, so pick it back up after a relog.
        ServerPlayerEvents.JOIN.register(Remnant::reacquire);

        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) ->
                dispatcher.register(buildCommand()));
    }

    /**
     * No permission requirement on purpose. This is the mod's own feature, not a cheat, and it
     * has to work in a single-player hardcore world that was never opened to LAN.
     */
    private LiteralArgumentBuilder<CommandSourceStack> buildCommand() {
        return Commands.literal("paradox")
                .then(Commands.literal("status").executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayer();
                    if (p == null) return 0;
                    Recorder r = RECORDERS.get(p.getUUID());
                    LoopSession s = SESSIONS.get(p.getUUID());
                    ctx.getSource().sendSystemMessage(Component.literal(
                            "§b[paradox] §7window §f" + ParadoxConfig.recordSeconds + "s§7, buffered §f"
                                    + (r == null ? 0 : r.size()) + "§7/§f" + ParadoxConfig.recordTicks() + " ticks"));
                    ctx.getSource().sendSystemMessage(Component.literal(
                            s == null ? "§7no loop running"
                                      : "§cloop running §7- " + s.kind() + ", " + s.remainingTicks() + " ticks left"));
                    BlockJournal j = JOURNALS.get(p.getUUID());
                    ctx.getSource().sendSystemMessage(Component.literal(
                            "§7remnant: §f" + (Remnant.has(p)
                                    ? "bound, " + Remnant.lives(p) + " life/lives left"
                                    : Remnant.awaitingRescue(p) ? "held by an evoker"
                                    : Remnant.hasAny(p) ? "yours, but not nearby"
                                    : "none")));
                    ctx.getSource().sendSystemMessage(Component.literal(
                            "§7rewind §f" + (ParadoxConfig.rewindEnabled ? "on" : "off")
                                    + "§7, journal §f" + (j == null ? 0 : j.logSize()) + "§7 change(s) logged"));
                    return 1;
                }))
                .then(Commands.literal("test").executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayer();
                    if (p == null) return 0;
                    if (SESSIONS.containsKey(p.getUUID())) {
                        ctx.getSource().sendSystemMessage(Component.literal("§calready in a loop"));
                        return 0;
                    }
                    Recorder r = RECORDERS.get(p.getUUID());
                    if (r == null || JOURNALS.get(p.getUUID()) == null || r.size() < 20) {
                        ctx.getSource().sendSystemMessage(Component.literal(
                                "§cnot enough history yet - walk around for a few seconds first"));
                        return 0;
                    }
                    LoopSession s = new LoopSession(p, null, r, JOURNALS.get(p.getUUID()));
                    s.markDryRun();          // MUST be set before begin(): this can never kill
                    s.begin(p);
                    SESSIONS.put(p.getUUID(), s);
                    ctx.getSource().sendSystemMessage(Component.literal(
                            "§e[paradox] dry run - you cannot die from this, even if the timer runs out"));
                    return 1;
                }))
                .then(Commands.literal("abort").executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayer();
                    if (p == null) return 0;
                    LoopSession s = SESSIONS.remove(p.getUUID());
                    if (s == null) {
                        ctx.getSource().sendSystemMessage(Component.literal("§7no loop to abort"));
                        return 0;
                    }
                    s.resolve(p, true, "aborted by hand");
                    return 1;
                }))
                .then(Commands.literal("selftest").executes(ctx -> {
                    var level = ctx.getSource().getLevel();
                    String result = Avatars.selfTest(level);
                    ctx.getSource().sendSystemMessage(Component.literal("§b[paradox] replica: §f" + result));
                    LOG.info("[paradox] selftest replica: {}", result);
                    return result.startsWith("OK") ? 1 : 0;
                }))
                .then(Commands.literal("reload").executes(ctx -> {
                    ParadoxConfig.load();
        ParadoxSounds.init();
                    ctx.getSource().sendSystemMessage(Component.literal("§b[paradox] §7config reloaded"));
                    return 1;
                }));
    }
}
