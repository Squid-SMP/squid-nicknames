package org.orsa.nativeSquidnames.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PlayerEntity.class)
public interface PlayerEntityMixin {
    @Accessor("gameProfile")
    abstract void setGameProfile(GameProfile profile);
}
