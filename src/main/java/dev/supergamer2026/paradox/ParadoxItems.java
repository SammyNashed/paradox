package dev.supergamer2026.paradox;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

/**
 * Items that exist only to be advancement icons.
 *
 * Nothing here is craftable, dropped, or meant to be held - a custom picture on the advancement
 * screen still needs a real registered item behind it, and this is that item. Its texture lives
 * at {@code assets/paradox/textures/item/remnant_sigil.png}.
 */
public final class ParadoxItems {

    private ParadoxItems() {}

    public static final Item REMNANT_SIGIL = register("remnant_sigil");

    private static Item register(String path) {
        Identifier id = Identifier.fromNamespaceAndPath(Paradox.MOD_ID, path);
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        return Registry.register(BuiltInRegistries.ITEM, key,
                new Item(new Item.Properties().setId(key).stacksTo(1)));
    }

    /** Touching the class is enough to run the static initialisers. */
    public static void init() {
        Paradox.LOG.info("[paradox] registered {} item(s)", 1);
    }
}
