package dev.supergamer2026.paradox;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The letter.
 *
 * A newly bound Remnant carries a written book to its owner, drops it at their feet, and gets
 * visibly pleased while they read. Fifteen seconds after it is opened the letter comes apart -
 * it was only ever meant to be read once, and the mod explains itself in the creature's own
 * voice instead of in a wiki page.
 */
public final class RemnantLetter {

    private RemnantLetter() {}

    public static final String TITLE = "A Letter, Unsigned";

    /** Owner -> the tick their letter should come apart, once they have opened it. */
    private static final Map<java.util.UUID, Integer> EXPIRY = new HashMap<>();

    public static ItemStack create(String origin) {
        List<Filterable<Component>> pages = List.of(
                page("""
                        §0You will not remember me.

                        §0That is not a complaint. It is the whole point of what I did.

                        §0There was a moment. You did not survive it. I was what was left over when you did anyway."""),
                page("""
                        §0I can take three deaths for you.

                        §0Not stop them. §0§oTake§0 them. Each one costs me a star, and when the last one goes out, so do I.

                        §0You will not have to ask. I will simply be there first."""),
                page("""
                        §0There is one thing I cannot be calm about.

                        §0""" + causeLine(origin) + """


                        §0I was there for it. I have not been able to put it down since.

                        §0When it is near you, I will be between you and it. Forgive me."""),
                page("""
                        §0You have seen others like me.

                        §0Small. Pale. Carrying a sword nobody handed them willingly.

                        §0We were all somebody once. Most of us never got asked.

                        §0                    §8- ✦"""));

        WrittenBookContent content = new WrittenBookContent(
                new Filterable<>(TITLE, Optional.empty()), "✦", 0, pages, true);

        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, content);
        return book;
    }

    /** What actually killed them, in the Remnant's own words. */
    private static String causeLine(String origin) {
        if (origin.startsWith("MOB:")) {
            String path = origin.substring(origin.indexOf(':', 4) + 1).replace('_', ' ');
            String article = "aeiou".indexOf(Character.toLowerCase(path.charAt(0))) >= 0 ? "An " : "A ";
            return article + path + ". It is still out there somewhere, and I have not forgotten it.";
        }
        return switch (origin) {
            case "LAVA" -> "You went into the lava. I watched it close over you.";
            case "FIRE" -> "You burned. I could do nothing but count the seconds.";
            case "DROWN" -> "You drowned. It was quiet, and it took a long time.";
            case "FALL" -> "You fell. There was a moment where you knew, and then there was not.";
            case "CONTACT" -> "Something you brushed past. Small, and enough.";
            case "SUFFOCATE" -> "The world closed in on you and would not let go.";
            default -> "I could not tell you what it was. Only that it happened, and that I was there.";
        };
    }

    private static Filterable<Component> page(String text) {
        return new Filterable<>(Component.literal(text), Optional.empty());
    }

    public static boolean isLetter(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(Items.WRITTEN_BOOK)) return false;
        WrittenBookContent c = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        return c != null && TITLE.equals(c.title().raw());
    }

    /** It flies the letter over and lets it fall at your feet. */
    public static void deliver(ServerLevel level, ServerPlayer player, Vex carrier) {
        // Drop the very letter it has been carrying, so the death named inside it is this
        // Remnant's own rather than a freshly written generic one.
        ItemStack held = carrier.getMainHandItem();
        ItemStack letter = isLetter(held) ? held.copy() : create("GENERIC");
        ItemEntity drop = new ItemEntity(level,
                carrier.getX(), carrier.getY() - 0.2, carrier.getZ(), letter);
        drop.setDeltaMovement(
                (player.getX() - carrier.getX()) * 0.05,
                0.05,
                (player.getZ() - carrier.getZ()) * 0.05);
        drop.setPickUpDelay(10);
        level.addFreshEntity(drop);

        level.playSound(null, carrier.getX(), carrier.getY(), carrier.getZ(),
                ParadoxSounds.REMNANT_CHEER, SoundSource.NEUTRAL, 0.8F, 1.3F);
        level.sendParticles(ParticleTypes.END_ROD,
                carrier.getX(), carrier.getY(), carrier.getZ(), 10, 0.2, 0.2, 0.2, 0.02);
        player.sendSystemMessage(Component.literal("§7It drops something at your feet."));
    }

    /** They opened it. Start the clock, and let the Remnant enjoy itself. */
    public static void onOpened(ServerPlayer player, int tick) {
        if (EXPIRY.containsKey(player.getUUID())) return;
        EXPIRY.put(player.getUUID(), tick + 300);          // 15 seconds
        Remnant.delight(player, tick);
    }

    /** Take it back once it has been read. It was a one-time thing. */
    public static void tick(ServerPlayer player, int tick) {
        Integer due = EXPIRY.get(player.getUUID());
        if (due == null || tick < due) return;
        EXPIRY.remove(player.getUUID());

        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (isLetter(inv.getItem(i))) {
                inv.setItem(i, ItemStack.EMPTY);
                if (player.level() instanceof ServerLevel sl) {
                    sl.sendParticles(ParticleTypes.POOF,
                            player.getX(), player.getY() + 1.2, player.getZ(), 20, 0.3, 0.3, 0.3, 0.03);
                    sl.playSound(null, player.getX(), player.getY(), player.getZ(),
                            ParadoxSounds.REMNANT_FADE, SoundSource.NEUTRAL, 0.6F, 1.4F);
                }
                player.sendSystemMessage(Component.literal("§8The letter comes apart in your hands."));
                return;
            }
        }
    }

    public static void forget(java.util.UUID playerId) {
        EXPIRY.remove(playerId);
    }
}
