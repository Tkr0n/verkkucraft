package com.verkku.hardcore;

import com.verkku.hardcore.commands.HardcoreCommand;
import com.verkku.hardcore.network.LivesSyncPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HardcoreMod implements ModInitializer {
    public static final String MOD_ID = "verkku-hardcore";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static HardcoreMod instance;
    private MinecraftServer server;
    private LifeManager lifeManager;
    private KOHandler koHandler;
    private WorldRegenManager worldRegenManager;

    @Override
    public void onInitialize() {
        instance = this;
        LOGGER.info("Verkku Hardcore initializing...");

        // Check for world regeneration flag BEFORE the world loads
        WorldRegenManager.checkAndDeleteWorldOnStartup();

        new NetworkHandler();

        ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            HardcoreCommand.register(dispatcher);
        });

        // COPY_FROM fires when a player respawns (entity recreated).
        // With Simple Revive + immediate_respawn, this fires on EVERY death:
        //   1) Initial death -> Simple Revive puts player in downed state (no kill, no COPY_FROM)
        //   2) downed timer expires -> fail.mcfunction calls kill @s -> player respawns -> COPY_FROM fires
        // We only decrement life on case 2 (player had the downed tag when they died).
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, force) -> {
            String oldName = oldPlayer.getName().getString();
            String newName = newPlayer.getName().getString();
            boolean wasDowned = oldPlayer.entityTags().contains("simplerevive.downed");
            boolean hadDownedInitiated = oldPlayer.entityTags().contains("simplerevive.downed.initiated");
            int oldDeathCount = oldPlayer.getStats().getValue(net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.DEATHS));
            int oldHealth = (int) oldPlayer.getHealth();
            boolean oldAlive = oldPlayer.isAlive();

            LOGGER.info("=== COPY_FROM DETAIL ===");
            LOGGER.info("  Player: {} -> {}", oldName, newName);
            LOGGER.info("  force={}", force);
            LOGGER.info("  simplerevive.downed={}", wasDowned);
            LOGGER.info("  simplerevive.downed.initiated={}", hadDownedInitiated);
            LOGGER.info("  oldPlayer.deathCount={}", oldDeathCount);
            LOGGER.info("  oldPlayer.health={} alive={}", oldHealth, oldAlive);
            LOGGER.info("  oldPlayer.allTags={}", oldPlayer.entityTags());
            LOGGER.info("=== END COPY_FROM DETAIL ===");

            if (lifeManager == null || koHandler == null) return;

            server.execute(() -> {
                try {
                    if (!wasDowned) {
                        LOGGER.info("DECISION: SKIP life decrement for {} — no downed tag (initial death handled by Simple Revive)", newName);
                        return;
                    }

                    // CRITICAL FIX: With Simple Revive + immediate_respawn, the tick function may run
                    // BEFORE COPY_FROM in the same tick. This means on the INITIAL death:
                    //   1) deathCount increments
                    //   2) tick function runs → down_me sets simplerevive.downed tag
                    //   3) COPY_FROM fires → sees the tag → incorrectly decrements life
                    //
                    // To distinguish: truly downed players (timer running) have simplerevive.downed.initiated
                    // (set by initiate.mcfunction after full setup). Players where the tag was just set
                    // by down_me on the initial death won't have initiated yet.
                    boolean wasDownedInitiated = newPlayer.entityTags().contains("simplerevive.downed.initiated")
                        || hadDownedInitiated;
                    if (!wasDownedInitiated) {
                        LOGGER.info("DECISION: SKIP life decrement for {} — had downed tag but NOT initiated (initial death, tick ran before COPY_FROM)", newName);
                        return;
                    }

                    LOGGER.info("DECISION: DECREMENT life for {} — was fully downed (downed.initiated=true, timer expired or failed revive)", newName);
                    boolean hadLives = lifeManager.decrementLife(newPlayer);
                    int remaining = lifeManager.getLives(newPlayer);
                    LOGGER.info("Player {} now has {} lives remaining after downed timer expired", newName, remaining);

                    // Sync lives to all clients immediately
                    koHandler.syncLivesToAllPlayers();
                    LOGGER.info("Synced lives to all players after COPY_FROM for {}", newName);

                    if (!hadLives || remaining <= 0) {
                        LOGGER.info("Player {} has no lives left! Triggering world regeneration", newName);
                        worldRegenManager.triggerWorldRegeneration();
                    } else {
                        String message = "§e§l" + newName + " respawned. §c§l(" + remaining + " vidas restantes)";
                        server.getPlayerList().broadcastSystemMessage(
                            net.minecraft.network.chat.Component.literal(message), false
                        );
                    }
                } catch (Exception e) {
                    LOGGER.error("Error handling respawn for {}: {}", newName, e.getMessage(), e);
                }
            });
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, serverInstance) -> {
            if (lifeManager == null) return;

            ServerPlayer player = handler.getPlayer();
            String playerName = player.getName().getString();

            // Clear stale Simplerevive downed state from previous sessions (e.g. after world regen)
            // Use player connection to execute commands - more reliable than dispatcher
            serverInstance.execute(() -> {
                try {
                    // Remove all downed-related tags
                    player.removeTag(net.minecraft.resources.ResourceLocation.parse("simplerevive:downed"));
                    player.removeTag(net.minecraft.resources.ResourceLocation.parse("simplerevive:downed.initiated"));

                    // Also try via commands as backup
                    var dispatcher = serverInstance.getCommands().getDispatcher();
                    var source = serverInstance.createCommandSourceStack()
                        .withPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS);
                    dispatcher.execute(
                        "tag " + playerName + " remove simplerevive.downed",
                        source
                    );
                    dispatcher.execute(
                        "tag " + playerName + " remove simplerevive.downed.initiated",
                        source
                    );
                    LOGGER.info("Cleared stale downed state for {}", playerName);
                } catch (Exception e) {
                    LOGGER.warn("Failed to clear downed state for {}: {}", playerName, e.getMessage());
                }
            });

            // Send lives sync
            int lives = lifeManager.getLives(player);
            ServerPlayNetworking.send(player, new LivesSyncPayload(
                java.util.List.of(new LivesSyncPayload.PlayerLivesData(player.getUUID(), playerName, lives))
            ));

            // Also sync KO state as false (player is not KO when joining)
            koHandler.clearKoState(player);

            LOGGER.info("Sent {} lives and cleared KO state for {} on join", lives, playerName);
        });

        LOGGER.info("Verkku Hardcore initialized!");
    }

    private void onServerStarting(MinecraftServer server) {
        this.server = server;
        this.lifeManager = new LifeManager(server);
        this.koHandler = new KOHandler(this);
        this.worldRegenManager = new WorldRegenManager(this);

        LOGGER.info("Hardcore system loaded with {} max lives", lifeManager.getMaxLives());
    }

    private void onServerStopping(MinecraftServer server) {
        if (lifeManager != null) {
            lifeManager.save();
        }
    }

    public static HardcoreMod getInstance() {
        return instance;
    }

    public MinecraftServer getServer() {
        return server;
    }

    public LifeManager getLifeManager() {
        return lifeManager;
    }

    public KOHandler getKoHandler() {
        return koHandler;
    }

    public WorldRegenManager getWorldRegenManager() {
        return worldRegenManager;
    }
}
