package org.orsa.nativeSquidnames;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.orsa.nativeSquidnames.mixin.PlayerEntityMixin;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.URL;
import java.util.*;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class NativeSquidnames implements ModInitializer {
    public static final String MOD_ID = "native-squidnames";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);
    public static final Type GSON_TYPE = new TypeToken<Map<UUID, String>>() {}.getType();

    public static MinecraftServer SERVER;

    // A regex to validate usernames
    private static final String USERNAME_REGEX = "^[0-9a-zA-Z_]{1,16}$";
    public static File CONFIG_FILE = null;

    // The mapping from UUID to username
    public static HashMap<UUID, String> mapping = new HashMap<>();

    @Override
    public void onInitialize() {
        CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".json").toFile();
        loadConfig();

        CommandRegistrationCallback.EVENT.register((cd, ra, re) -> registerCommands(cd));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> SERVER = server);
    }

    public static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            literal("nick")
                .then(literal("set")
                .then(argument("nickname", StringArgumentType.word())
                    .executes(NativeSquidnames::nickSetCommand)
                ))
            );

        dispatcher.register(
            literal("nick")
                .then(literal("clear")
                    .executes(NativeSquidnames::nickClearCommand)
                )
            );

        dispatcher.register(
            literal("nick")
                .then(literal("other")
                .then(argument("player", StringArgumentType.word())
                .then(argument("nickname", StringArgumentType.word())
                    .executes(NativeSquidnames::nickOtherCommand)
                )))
            );
    }

    private static int nickSetCommand(CommandContext<ServerCommandSource> context) {
        var source = context.getSource();
        var player = source.getPlayer();
        var nick = StringArgumentType.getString(context, "nickname");

        return trySetSelfNickname(player, nick, source);
    }

    private static int nickClearCommand(CommandContext<ServerCommandSource> context) {
        var source = context.getSource();
        var player = source.getPlayer();

        return tryClearNickname(player, source);
    }

    private static int nickOtherCommand(CommandContext<ServerCommandSource> context) {
        var source = context.getSource();
        var playerName = StringArgumentType.getString(context, "player");
        var nick = StringArgumentType.getString(context, "nickname");

        return trySetOtherNickname(playerName, nick, source);
    }

    public static int trySetSelfNickname(ServerPlayerEntity player, String nick, ServerCommandSource source) {
        if (player == null) {
            source.sendError(Text.literal("This command can only be run as a player."));
            return 0;
        }

        var uuid = player.getUuid();
        return trySetPlayerNickname(uuid, nick, source);
    }

    public static int tryClearNickname(ServerPlayerEntity player, ServerCommandSource source) {
        if (player == null) {
            source.sendError(Text.literal("This command can only be run as a player."));
            return 0;
        }

        mapping.remove(player.getUuid());

        source.sendMessage(Text.literal("Nick cleared. To apply changes, please disconnect & reconnect to this server."));
        return 1;
    }

    public static int trySetOtherNickname(String playerName, String nick, ServerCommandSource source) {
        var uuid = getOfflinePlayerUUID(playerName);

        if (uuid == null) {
            source.sendError(Text.literal("Player not found."));
            return 0;
        }

        var result = trySetPlayerNickname(uuid, nick, source);

        if (result == 1) {
            source.sendMessage(Text.literal("Nick of " + uuid + " set to " + nick + "."));
        }

        return result;
    }

    public static int trySetPlayerNickname(UUID uuid, String nick) {
        return trySetPlayerNickname(uuid, nick, null);
    }

    public static int trySetPlayerNickname(UUID uuid, String nick, ServerCommandSource source) {
        if (!nick.matches(USERNAME_REGEX)) {
            if (source != null) {
                source.sendError(Text.literal("Nickname contains invalid characters or is longer than 16 characters. Please choose a different one."));
            }

            return 0;
        }

        if (mapping.containsValue(nick) && !nick.equals(mapping.get(uuid))) {
            if (source != null) {
                source.sendError(Text.literal("Someone already has that nickname. Please choose a different one."));
            }

            return 0;
        }

        mapping.put(uuid, nick);

        saveConfig();

        var player = SERVER.getPlayerManager().getPlayer(uuid);

        if (player == null) {
            return 1;
        }

        player.networkHandler.disconnect(Text.literal("Your nickname has been set to \"" + nick + "\". Reconnect to see the changes."));

        return 1;
    }

    // --- UPDATE NAME EVERYWHERE ---

    private static void replacePlayerGameProfile(UUID uuid, String nick) {
        var player = SERVER.getPlayerManager().getPlayer(uuid);

        if (player == null) {
            return;
        }

        var oldProfile = player.getGameProfile();
        var newProfile = new GameProfile(oldProfile.id(), nick, oldProfile.properties());

        ((PlayerEntityMixin) player).setGameProfile(newProfile);

        simulateDcRc(player);
    }

    private static void simulateDcRc(ServerPlayerEntity player) {
        var playerManager = SERVER.getPlayerManager();
        var uuid = player.getUuid();

        for (ServerPlayerEntity otherPlayer : playerManager.getPlayerList()) {
            if (otherPlayer == player) continue;
            otherPlayer.networkHandler.sendPacket(new PlayerRemoveS2CPacket(List.of(uuid)));
            otherPlayer.networkHandler.sendPacket(new EntitySpawnS2CPacket(player, 0, player.getBlockPos()));
        }
    }

    // --- CONFIG STUFF ---

    private static void saveConfig() {
        try {
            var json = new Gson().toJson(mapping);
            try (var writer = new FileWriter(CONFIG_FILE)) {
                writer.write(json);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to save config.", e);
        }
    }

    private static void loadConfig() {
        if (!CONFIG_FILE.exists()) {
            LOGGER.info("Config file not found, skipping config load");
            return;
        }
        try {
            try (var reader = new FileReader(CONFIG_FILE)) {
                mapping = new Gson().fromJson(reader, GSON_TYPE);
                if (new HashSet<>(mapping.values()).size() != mapping.size()) {
                    LOGGER.error("Duplicate nickname(s) detected, refusing to load config.");
                    mapping.clear();
                }

                if (mapping.values().stream().anyMatch(s -> !s.matches(USERNAME_REGEX))) {
                    LOGGER.error("Invalid nickname(s) detected, refusing to load config.");
                    mapping.clear();
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to read config.", e);
        }
    }

    public static UUID getOfflinePlayerUUID(String name) {
        try {
            URL url = new URL("https://api.mojang.com/users/profiles/minecraft/" + name);
            InputStreamReader reader = new InputStreamReader(url.openStream());
            JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
            String id = obj.get("id").getAsString();
            return UUID.fromString(id.replaceFirst(
                    "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
                    "$1-$2-$3-$4-$5"
            ));
        } catch (Exception e) {
            return null;
        }
    }
}