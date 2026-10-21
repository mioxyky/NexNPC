package fr.nexnpc.fabric.client;

import fr.nexnpc.fabric.entity.NpcEntityTypes;
import fr.nexnpc.fabric.client.render.NpcEntityRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.renderer.entity.EntityRenderers;

public final class NexNpcClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRenderers.register(NpcEntityTypes.NPC, NpcEntityRenderer::new);
    }
}
