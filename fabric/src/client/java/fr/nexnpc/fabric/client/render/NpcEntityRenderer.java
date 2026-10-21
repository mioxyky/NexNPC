package fr.nexnpc.fabric.client.render;

import fr.nexnpc.fabric.entity.NpcEntity;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.Identifier;

public final class NpcEntityRenderer extends MobRenderer<NpcEntity, NpcRenderState, NpcPlayerModel> {
    public NpcEntityRenderer(EntityRendererProvider.Context context) {
        super(context, new NpcPlayerModel(context.bakeLayer(ModelLayers.PLAYER)), 0.0F);
    }

    @Override
    public NpcRenderState createRenderState() {
        return new NpcRenderState();
    }

    @Override
    public void extractRenderState(NpcEntity entity, NpcRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.skinName = entity.getSkinName();
    }

    @Override
    public Identifier getTextureLocation(NpcRenderState state) {
        return SkinTextureCache.getTexture(state.skinName);
    }
}
