package fr.nexnpc.fabric.client.render;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;

public final class NpcPlayerModel extends EntityModel<NpcRenderState> {
    private final ModelPart head;
    private final ModelPart body;
    private final ModelPart rightArm;
    private final ModelPart leftArm;
    private final ModelPart rightLeg;
    private final ModelPart leftLeg;

    public NpcPlayerModel(ModelPart root) {
        super(root);
        head = root.getChild("head");
        body = root.getChild("body");
        rightArm = root.getChild("right_arm");
        leftArm = root.getChild("left_arm");
        rightLeg = root.getChild("right_leg");
        leftLeg = root.getChild("left_leg");
    }

    @Override
    public void setupAnim(NpcRenderState state) {
        head.xRot = state.xRot * Mth.DEG_TO_RAD;
        head.yRot = state.yRot * Mth.DEG_TO_RAD;
        body.xRot = 0.0F;
        body.yRot = 0.0F;
        rightArm.xRot = 0.0F;
        rightArm.yRot = 0.0F;
        leftArm.xRot = 0.0F;
        leftArm.yRot = 0.0F;
        rightLeg.xRot = 0.0F;
        leftLeg.xRot = 0.0F;
    }
}
