package fr.nexnpc.fabric.entity;

import fr.nexnpc.fabric.command.NpcAdminState;
import fr.nexnpc.fabric.network.NpcNetworking;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class NpcEntity extends PathfinderMob {
    private static final EntityDataAccessor<String> SKIN_NAME = SynchedEntityData.defineId(
            NpcEntity.class, EntityDataSerializers.STRING
    );
    private final Map<UUID, Long> lastInteractions = new HashMap<>();
    private ActionType actionType = ActionType.NONE;
    private String actionValue = "";
    private boolean lookAtNearestPlayer;

    public NpcEntity(EntityType<? extends NpcEntity> entityType, Level level) {
        super(entityType, level);
        setNoGravity(true);
        setNoAi(true);
        setPersistenceRequired();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(SKIN_NAME, "Steve");
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            return;
        }

        setNoGravity(true);
        if (lookAtNearestPlayer && tickCount % 3 == 0) {
            lookAtNearestPlayer();
        }
        if (tickCount % 200 == 0) {
            long now = level().getGameTime();
            lastInteractions.values().removeIf(last -> now - last > 200);
        }
    }

    private void lookAtNearestPlayer() {
        Player nearest = level().getNearestPlayer(this, 16.0D);
        if (nearest == null) {
            return;
        }

        double dx = nearest.getX() - getX();
        double dy = nearest.getEyePosition().y - getEyePosition().y;
        double dz = nearest.getZ() - getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float targetPitch = (float) -(Mth.atan2(dy, horizontalDistance) * (180.0D / Math.PI));
        setYRot(Mth.rotLerp(0.2F, getYRot(), targetYaw));
        setYHeadRot(Mth.rotLerp(0.2F, getYHeadRot(), targetYaw));
        setYBodyRot(Mth.rotLerp(0.2F, getYRot(), targetYaw));
        setXRot(Mth.rotLerp(0.2F, getXRot(), Mth.clamp(targetPitch, -45.0F, 45.0F)));
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }

        if (NpcAdminState.consumeSelectionClick(serverPlayer, this)) {
            return InteractionResult.SUCCESS;
        }
        if (!allowInteraction(serverPlayer)) {
            return InteractionResult.SUCCESS;
        }

        switch (actionType) {
            case COMMAND -> executeCommand(serverPlayer);
            case SERVER -> NpcNetworking.requestTransfer(serverPlayer, actionValue);
            case NONE -> {
                // NPC sans action : le clic reste consommé pour éviter une interaction accidentelle.
            }
        }
        return InteractionResult.SUCCESS;
    }

    private boolean allowInteraction(ServerPlayer player) {
        long now = level().getGameTime();
        Long previous = lastInteractions.put(player.getUUID(), now);
        return previous == null || now - previous >= 8L;
    }

    private void executeCommand(ServerPlayer player) {
        String command = actionValue.strip();
        while (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (!command.isBlank()) {
            ((ServerLevel) player.level()).getServer().getCommands()
                    .performPrefixedCommand(player.createCommandSourceStack(), command);
        }
    }

    public String getSkinName() {
        return entityData.get(SKIN_NAME);
    }

    public void setSkinName(String skinName) {
        entityData.set(SKIN_NAME, skinName);
    }

    public ActionType getActionType() {
        return actionType;
    }

    public String getActionValue() {
        return actionValue;
    }

    public void setAction(ActionType actionType, String actionValue) {
        this.actionType = actionType;
        this.actionValue = actionType == ActionType.NONE ? "" : actionValue;
    }

    public boolean shouldLookAtNearestPlayer() {
        return lookAtNearestPlayer;
    }

    public void setLookAtNearestPlayer(boolean lookAtNearestPlayer) {
        this.lookAtNearestPlayer = lookAtNearestPlayer;
    }

    @Override
    public boolean isInvulnerableTo(ServerLevel level, DamageSource damageSource) {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void push(Entity entity) {
        // Immobilité complète.
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putString("NexNpcSkin", getSkinName());
        output.putString("NexNpcAction", actionType.name());
        output.putString("NexNpcActionValue", actionValue);
        output.putBoolean("NexNpcLook", lookAtNearestPlayer);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        input.getString("NexNpcSkin").ifPresent(this::setSkinName);
        input.getString("NexNpcAction").ifPresent(value -> {
            try {
                actionType = ActionType.valueOf(value);
            } catch (IllegalArgumentException ignored) {
                actionType = ActionType.NONE;
            }
        });
        actionValue = input.getString("NexNpcActionValue").orElse("");
        lookAtNearestPlayer = input.getBooleanOr("NexNpcLook", false);
    }

    public enum ActionType {
        NONE,
        COMMAND,
        SERVER
    }
}
