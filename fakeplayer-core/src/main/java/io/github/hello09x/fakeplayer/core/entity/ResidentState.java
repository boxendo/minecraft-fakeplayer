package io.github.hello09x.fakeplayer.core.entity;

import io.github.hello09x.fakeplayer.api.spi.ActionSetting;
import io.github.hello09x.fakeplayer.api.spi.ActionType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;

/**
 * 一个驻留假人的快照，用于服务器重启后原位恢复。
 *
 * @param uuid        假人 UUID
 * @param name        假人名称
 * @param creatorUuid 创建者 UUID（可能为空）
 * @param creatorName 创建者名称
 * @param creatorIp   创建者 IP
 * @param world       世界名
 * @param x           X
 * @param y           Y
 * @param z           Z
 * @param yaw         朝向
 * @param pitch       俯仰
 * @param sprinting   是否疾跑
 * @param sneaking    是否潜行
 * @param actions     正在执行的动作
 */
public record ResidentState(

        @NotNull UUID uuid,

        @NotNull String name,

        @Nullable UUID creatorUuid,

        @NotNull String creatorName,

        @NotNull String creatorIp,

        @NotNull String world,

        double x,
        double y,
        double z,
        float yaw,
        float pitch,

        boolean sprinting,
        boolean sneaking,

        @NotNull Map<ActionType, ActionSetting> actions,

        boolean invulnerable,
        boolean collidable,
        boolean pickupItems,
        boolean wolverine,
        boolean lookAtEntity,
        boolean replenish,
        boolean autofish

) {
}
