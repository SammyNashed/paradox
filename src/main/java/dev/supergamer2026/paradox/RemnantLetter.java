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

    public static final String TITLE = "From your Remnant";
    /** Letters dropped before 1.1.3 still carry this title. */
    private static final String OLD_TITLE = "A Letter, Unsigned";

    /** Owner -> the tick their letter should come apart, once they have opened it. */
    private static final Map<java.util.UUID, Integer> EXPIRY = new HashMap<>();

    public static ItemStack create(String origin) {
        List<Filterable<Component>> pages = List.of(
                page("""
                        §0Hey, it's me.

                        §0The one who saved you back there. You didn't see me, but I was there."""),
                page("§0I can take " + ParadoxConfig.remnantLives + " deaths for you.\n\n"
                        + "§0Each one costs me a star. When they're all gone, so am I.\n\n"
                        + "§0Keep me close."),
                page("""
                        §0What scares me most:

                        §0""" + causeLine(origin) + """


                        §0If it comes back, I'll get in its way."""),
                page("""
                        §0There are others like me out there.

                        §0Be nice to them.

                        §0                    §8✦"""));

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
            return article + path + ". I won't forget it.";
        }
        if (origin.startsWith("CONTACT")) {
            return switch (sub(origin, "CONTACT")) {
                case "cactus" -> "A cactus. Seriously, a cactus.";
                case "sweetBerryBush" -> "Berry bushes. They hurt more than they look.";
                case "hotFloor" -> "Magma blocks. Watch your step.";
                case "freeze" -> "Powder snow. You froze.";
                case "stalagmite" -> "Dripstone. Look up in caves.";
                case "wither" -> "A wither rose.";
                default -> "A block you walked into.";
            };
        }
        if (origin.startsWith("SUFFOCATE")) {
            return "cramming".equals(sub(origin, "SUFFOCATE"))
                    ? "Too many mobs in too little space."
                    : "Getting stuck in a wall.";
        }
        return switch (origin) {
            case "LAVA" -> "Lava.";
            case "FIRE" -> "Fire.";
            case "DROWN" -> "Drowning.";
            case "FALL" -> "Falling.";
            case "EXPLOSION" -> "Explosions.";
            default -> "Whatever got you. I didn't see what it was.";
        };
    }

    /** The specific vanilla cause after the colon - "cactus", "inWall" - or "" for an old save
     *  from before this mod tracked it, or the label of a family with no favourite of its own. */
    private static String sub(String origin, String prefix) {
        return origin.startsWith(prefix + ":") ? origin.substring(prefix.length() + 1) : "";
    }

    private static Filterable<Component> page(String text) {
        return new Filterable<>(Component.literal(text), Optional.empty());
    }

    public static boolean isLetter(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(Items.WRITTEN_BOOK)) return false;
        WrittenBookContent c = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        return c != null && (TITLE.equals(c.title().raw()) || OLD_TITLE.equals(c.title().raw()));
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
    }

    /** They opened it. Start the clock, and let the Remnant enjoy itself. */
    public static void onOpened(ServerPlayer player, int tick) {
        if (EXPIRY.containsKey(player.getUUID())) return;
        EXPIRY.put(player.getUUID(), tick + 300);          // 15 seconds
        Remnant.delight(player, tick);
        ParadoxAdvancements.grant(player, "a_letter_unsigned");
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
                return;
            }
        }
    }

    public static void forget(java.util.UUID playerId) {
        EXPIRY.remove(playerId);
    }
}
