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
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.orsa.nativeSquidnames.mixin.PlayerEntityMixin;
import org.orsa.nativeSquidnames.util.MojangApi;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.URL;
import java.util.*;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

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

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
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
                    .then(literal("set")
                        .then(argument("nickname", StringArgumentType.word())
                            .executes(NativeSquidnames::nickOtherCommand)
                        ))
                    .then(literal("clear")
                        .executes(NativeSquidnames::nickOtherClearCommand)
                    )
                ))
            );
    }

    private static int nickSetCommand(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var player = source.getPlayer();
        var nick = StringArgumentType.getString(context, "nickname");

        return trySetSelfNickname(player, nick, source);
    }

    private static int nickClearCommand(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var player = source.getPlayer();

        return tryClearNickname(player, source);
    }

    private static int nickOtherCommand(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var playerName = StringArgumentType.getString(context, "player");
        var nick = StringArgumentType.getString(context, "nickname");

        return trySetOtherNickname(playerName, nick, source);
    }

    private static int nickOtherClearCommand(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var playerName = StringArgumentType.getString(context, "player");

        return tryClearOtherNickname(playerName, source);
    }

    public static int trySetSelfNickname(ServerPlayer player, String nick, CommandSourceStack source) {
        if (player == null) {
            source.sendFailure(Component.literal("This command can only be run as a player."));
            return 0;
        }

        var uuid = player.getUUID();
        return trySetPlayerNickname(uuid, nick, source);
    }

    public static int tryClearNickname(ServerPlayer player, CommandSourceStack source) {
        if (player == null) {
            source.sendFailure(Component.literal("This command can only be run as a player."));
            return 0;
        }

        mapping.put(player.getUUID(), "");

        saveConfig();

        player.connection.disconnect(Component.literal("Your nickname has been cleared. Reconnect to see the changes."));
        return 1;
    }

    public static int trySetOtherNickname(String playerName, String nick, CommandSourceStack source) {
        var uuid = MojangApi.getPlayerUUID(playerName);

        if (uuid == null) {
            source.sendFailure(Component.literal("Player not found."));
            return 0;
        }

        var result = trySetPlayerNickname(uuid, nick, source);

        if (result == 1) {
            source.sendSystemMessage(Component.literal("Nick of " + uuid + " set to " + nick + "."));
        }

        return result;
    }

    public static int tryClearOtherNickname(String playerName, CommandSourceStack source) {
        var uuid = MojangApi.getPlayerUUID(playerName);

        if (uuid == null) {
            source.sendFailure(Component.literal("Player not found."));
            return 0;
        }

        source.sendSystemMessage(Component.literal("Nick of " + uuid + " cleared."));

        tryClearPlayerNickname(uuid);

        return 1;
    }

    public static void tryClearPlayerNickname(UUID uuid) {
        mapping.put(uuid, "");

        saveConfig();

        var player = SERVER.getPlayerList().getPlayer(uuid);

        if (player == null) {
            return;
        }

        player.connection.disconnect(Component.literal("Your nickname has been cleared. Reconnect to see the changes."));
    }

    public static int trySetPlayerNickname(UUID uuid, String nick) {
        return trySetPlayerNickname(uuid, nick, null);
    }

    public static int trySetPlayerNickname(UUID uuid, String nick, CommandSourceStack source) {
        if (!nick.matches(USERNAME_REGEX)) {
            if (source != null) {
                source.sendFailure(Component.literal("Nickname contains invalid characters or is longer than 16 characters. Please choose a different one."));
            }

            return 0;
        }

        if (mapping.containsValue(nick) && !nick.equals(mapping.get(uuid))) {
            if (source != null) {
                source.sendFailure(Component.literal("Someone already has that nickname. Please choose a different one."));
            }

            return 0;
        }

        mapping.put(uuid, nick);

        saveConfig();

        var player = SERVER.getPlayerList().getPlayer(uuid);

        if (player == null) {
            return 1;
        }

        player.connection.disconnect(Component.literal("Your nickname has been set to \"" + nick + "\". Reconnect to see the changes."));

        return 1;
    }

    // --- UPDATE NAME EVERYWHERE ---

    private static void replacePlayerGameProfile(UUID uuid, String nick) {
        var player = SERVER.getPlayerList().getPlayer(uuid);

        if (player == null) {
            return;
        }

        var oldProfile = player.getGameProfile();
        var newProfile = new GameProfile(oldProfile.id(), nick, oldProfile.properties());

        ((PlayerEntityMixin) player).setGameProfile(newProfile);

        simulateDcRc(player);
    }

    private static void simulateDcRc(ServerPlayer player) {
        var playerList = SERVER.getPlayerList();
        var uuid = player.getUUID();

        for (ServerPlayer otherPlayer : playerList.getPlayers()) {
            if (otherPlayer == player) continue;
            otherPlayer.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(uuid)));
            otherPlayer.connection.send(new ClientboundAddEntityPacket(player, 0, player.blockPosition()));
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
}
