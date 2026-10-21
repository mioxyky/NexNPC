package fr.nexnpc.fabric.entity;

import fr.nexnpc.fabric.NexNpcMod;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.core.Registry;

public final class NpcEntityTypes {
    public static final EntityType<NpcEntity> NPC = register(
            "npc",
            EntityType.Builder.<NpcEntity>of(NpcEntity::new, MobCategory.MISC)
                    .sized(0.6F, 1.8F)
                    .clientTrackingRange(10)
                    .updateInterval(2)
    );

    private NpcEntityTypes() {
    }

    private static <T extends Entity> EntityType<T> register(String name, EntityType.Builder<T> builder) {
        ResourceKey<EntityType<?>> key = ResourceKey.create(
                Registries.ENTITY_TYPE,
                Identifier.fromNamespaceAndPath(NexNpcMod.MOD_ID, name)
        );
        return Registry.register(BuiltInRegistries.ENTITY_TYPE, key, builder.build(key));
    }

    public static void register() {
        NexNpcMod.LOGGER.info("Enregistrement de l'entité NPC");
    }

    public static void registerAttributes() {
        FabricDefaultAttributeRegistry.register(
                NPC,
                PathfinderMob.createMobAttributes()
                        .add(Attributes.MAX_HEALTH, 20.0D)
                        .add(Attributes.MOVEMENT_SPEED, 0.0D)
        );
    }
}
