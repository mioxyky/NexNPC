package fr.nexnpc.fabric.entity;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.Mannequin;

import java.util.Comparator;

public final class NpcLookHandler {
    private static final double LOOK_DISTANCE_SQUARED = 16.0D * 16.0D;

    private NpcLookHandler() {
    }

    public static void register() {
        ServerTickEvents.END_LEVEL_TICK.register(NpcLookHandler::tick);
    }

    private static void tick(ServerLevel level) {
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof Mannequin mannequin)
                    || !NpcEntityData.isNpc(mannequin)
                    || !NpcEntityData.shouldLookAtNearestPlayer(mannequin)
                    || mannequin.tickCount % 4 != 0) {
                continue;
            }

            ServerPlayer nearest = level.players().stream()
                    .filter(player -> player.isAlive() && !player.isSpectator())
                    .filter(player -> mannequin.distanceToSqr(player) <= LOOK_DISTANCE_SQUARED)
                    .min(Comparator.comparingDouble(mannequin::distanceToSqr))
                    .orElse(null);
            if (nearest == null) {
                continue;
            }

            double dx = nearest.getX() - mannequin.getX();
            double dy = nearest.getEyeY() - mannequin.getEyeY();
            double dz = nearest.getZ() - mannequin.getZ();
            double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
            float targetYaw = (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
            float targetPitch = (float) -(Mth.atan2(dy, horizontalDistance) * (180.0D / Math.PI));

            float yaw = Mth.rotLerp(0.25F, mannequin.getYRot(), targetYaw);
            float pitch = Mth.rotLerp(0.25F, mannequin.getXRot(), Mth.clamp(targetPitch, -45.0F, 45.0F));
            mannequin.setYRot(yaw);
            mannequin.setYHeadRot(yaw);
            mannequin.setYBodyRot(yaw);
            mannequin.setXRot(pitch);
            mannequin.setDeltaMovement(0.0D, 0.0D, 0.0D);
        }
    }
}
