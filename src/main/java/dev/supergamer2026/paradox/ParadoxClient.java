package dev.supergamer2026.paradox;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.monster.Vex;

/**
 * Optional client half. The server can't tell first person from F5, so the client hides your
 * own ghost body while you look through its eyes and shows it again in third person.
 * Without Paradox on the client the body is just always visible.
 */
public final class ParadoxClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player == null || mc.level == null) return;
            boolean firstPerson = mc.options.getCameraType().isFirstPerson();
            for (Vex vex : mc.level.getEntitiesOfClass(Vex.class, mc.player.getBoundingBox().inflate(4.0),
                    v -> v.getCustomName() != null && Avatars.GHOST_NAME.equals(v.getCustomName().getString()))) {
                vex.setInvisible(firstPerson);
            }
        });
    }
}
