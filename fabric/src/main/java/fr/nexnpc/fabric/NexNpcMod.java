package fr.nexnpc.fabric;

import fr.nexnpc.fabric.command.NpcCommands;
import fr.nexnpc.fabric.entity.NpcInteractionHandler;
import fr.nexnpc.fabric.entity.NpcLookHandler;
import fr.nexnpc.fabric.entity.NpcEntityLoadHandler;
import fr.nexnpc.fabric.network.NpcNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NexNpcMod implements ModInitializer {
    public static final String MOD_ID = "nexnpc";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        NpcNetworking.register();
        NpcInteractionHandler.register();
        NpcLookHandler.register();
        NpcEntityLoadHandler.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                NpcCommands.register(dispatcher));
        LOGGER.info("NexNPC serveur initialisé (mannequins natifs minecraft:mannequin)");
    }
}
