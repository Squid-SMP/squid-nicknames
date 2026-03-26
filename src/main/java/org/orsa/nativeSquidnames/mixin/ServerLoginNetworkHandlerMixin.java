package org.orsa.nativeSquidnames.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.yggdrasil.ProfileResult;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.orsa.nativeSquidnames.NativeSquidnames;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.UUID;
import java.util.logging.Logger;

import static org.orsa.nativeSquidnames.NativeSquidnames.LOGGER;
import static org.orsa.nativeSquidnames.NativeSquidnames.mapping;

@Mixin(targets = "net.minecraft.server.network.ServerLoginNetworkHandler$1")
public class ServerLoginNetworkHandlerMixin {

    @WrapOperation(method = "run()V", at = @At(value="INVOKE", target="Lcom/mojang/authlib/yggdrasil/ProfileResult;profile()Lcom/mojang/authlib/GameProfile;"))
    private GameProfile injectNickname(ProfileResult instance, Operation<GameProfile> original) {
        var profile = original.call(instance);
        UUID id = profile.id();

        if (!NativeSquidnames.mapping.containsKey(id)) {
            return profile;
        }

        var nick = NativeSquidnames.mapping.get(id);
        LOGGER.info("Overriding username for user {} to {}", id, nick);

        var newProfile = withNickname(profile, nick);

        return newProfile;
    }

    @Unique
    private static GameProfile withNickname(GameProfile profile, String nick) {
        return new GameProfile(profile.id(), nick, profile.properties());
    }
}
