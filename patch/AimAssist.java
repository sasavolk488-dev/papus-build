package project.papus.implement.features.modules.player;

import java.util.Random;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.HitResult.Type;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.RaycastContext.FluidHandling;
import net.minecraft.world.RaycastContext.ShapeType;
import project.papus.api.event.EventHandler;
import project.papus.api.feature.module.Module;
import project.papus.api.feature.module.ModuleCategory;
import project.papus.api.feature.module.setting.implement.BooleanSetting;
import project.papus.api.feature.module.setting.implement.MultiSelectSetting;
import project.papus.api.feature.module.setting.implement.ValueSetting;
import project.papus.api.repository.friend.FriendUtils;
import project.papus.common.util.rotation.Angle;
import project.papus.common.util.rotation.AngleUtil;
import project.papus.implement.events.keyboard.MouseRotationEvent;

public class AimAssist extends Module {

    private final ValueSetting speedXSetting;
    private final ValueSetting speedYSetting;
    private final ValueSetting distanceSetting;
    private final ValueSetting fovSetting;
    private final ValueSetting aimHeightSetting;
    private final BooleanSetting testSetting;
    private final MultiSelectSetting targetsSetting;

    private final Random random = new Random();
    private float noiseYaw;
    private float noisePitch;
    private float speedFactor;
    private long lastNoiseUpdate;

    public AimAssist() {
        super("AimAssist", "Aim Assist", ModuleCategory.PLAYER);

        this.speedXSetting = new ValueSetting("Скорость X", "").setValue(3.5F).range(0.0F, 10.0F);
        this.speedYSetting = new ValueSetting("Скорость Y", "").setValue(3.5F).range(0.0F, 10.0F);
        this.distanceSetting = new ValueSetting("Дистанция", "").setValue(3.0F).range(2, 8);
        this.fovSetting = new ValueSetting("Поле зрения", "").setValue(180.0F).range(10, 360);
        this.aimHeightSetting = new ValueSetting("Высота наводки", "").setValue(0.5F).range(0.0F, 1.0F);
        this.testSetting = new BooleanSetting("Test", "").setValue(false);
        this.targetsSetting = new MultiSelectSetting("Таргеты", "").value("Игроки", "Мобы", "Друзья");

        this.setup(
            this.speedXSetting,
            this.speedYSetting,
            this.distanceSetting,
            this.fovSetting,
            this.aimHeightSetting,
            this.testSetting,
            this.targetsSetting
        );

        this.targetsSetting.getSelected().add("Игроки");
    }

    @EventHandler
    public void onMouseRotation(MouseRotationEvent var1) {
        if (this.isSilent()) return;
        if (mc.player == null || mc.world == null) return;

        LivingEntity var2 = this.findTarget();
        if (var2 == null) return;

        this.resetTarget();

        Vec3d var3 = mc.player.getPos().add(0.0, mc.player.getHeight(), 0.0);
        float var4 = this.aimHeightSetting.getValue() + this.noisePitch;
        float var5 = this.noiseYaw;

        Vec3d var6 = var2.getPos().add(var5, var2.getHeight() * var4, 0.0);
        Vec3d var7 = var6.subtract(var3);

        float var8 = (float) Math.toDegrees(Math.atan2(var7.z, var7.x)) - 90.0F;
        float var9 = (float) (-Math.toDegrees(Math.atan2(var7.y, Math.sqrt(var7.x * var7.x + var7.z * var7.z))));

        Angle var10 = AngleUtil.cameraAngle();
        float var11 = var8 - var10.getYaw();
        var11 = ((var11 + 180.0F) % 360.0F + 360.0F) % 360.0F - 180.0F;

        float var12 = var9 - var10.getPitch();
        var12 = ((var12 + 180.0F) % 360.0F + 360.0F) % 360.0F - 180.0F;

        if (Math.abs(var11) > this.fovSetting.getValue() / 2.0F) return;

        if (this.testSetting.isValue() || Math.abs(var11) >= 2.0F || Math.abs(var12) >= 2.0F) {
            float var13 = this.speedXSetting.getValue() / 40.0F;
            var11 = MathHelper.clamp(var11, -20.0F, 20.0F);
            float var15 = var11 * var13 * 10.0F;

            float var16 = this.speedYSetting.getValue() / 40.0F;
            var12 = MathHelper.clamp(var12, -20.0F, 20.0F);
            float var18 = var12 * var16 * 10.0F;

            var1.setCursorDeltaX(var1.getCursorDeltaX() + (float) ((int) var15));
            var1.setCursorDeltaY(var1.getCursorDeltaY() + (float) ((int) var18));
        }
    }

    private void resetTarget() {
        long var1 = System.currentTimeMillis();
        if (var1 - this.lastNoiseUpdate > 200L) {
            this.noiseYaw = (this.random.nextFloat() - 0.5F) * 0.4F;
            this.noisePitch = (this.random.nextFloat() - 0.5F) * 0.3F;
            this.speedFactor = 0.3F + this.random.nextFloat() * 0.7F;
            this.lastNoiseUpdate = var1;
        }
        if (!this.testSetting.isValue()) {
            this.noiseYaw = 0.0F;
            this.noisePitch = 0.0F;
        }
    }

    private LivingEntity findTarget() {
        LivingEntity var1 = null;
        float var2 = Float.MAX_VALUE;
        Angle var3 = AngleUtil.cameraAngle();

        for (Entity var5 : mc.world.getEntities()) {
            if (!(var5 instanceof LivingEntity var6)) continue;
            if (var6 == mc.player) continue;
            if (!var6.isAlive()) continue;
            if (var6 instanceof ArmorStandEntity) continue;
            if (var6.distanceTo(mc.player) > this.distanceSetting.getValue()) continue;

            if (var6 instanceof PlayerEntity) {
                if (!this.targetsSetting.isSelected("Игроки")) continue;
                boolean var7 = FriendUtils.getFriends().stream()
                    .anyMatch(var1x -> var1x.getName().equals(var6.getName().getString()));
                if (var7 && !this.targetsSetting.isSelected("Друзья")) continue;
            } else {
                if (!this.targetsSetting.isSelected("Мобы")) continue;
            }

            Vec3d var15 = mc.player.getPos().add(0.0, mc.player.getHeight(), 0.0);
            Vec3d var8 = var6.getPos().add(0.0, var6.getHeight() * 0.7, 0.0);

            RaycastContext var9 = new RaycastContext(var15, var8, ShapeType.COLLIDER, FluidHandling.NONE, mc.player);
            if (mc.world.raycast(var9).getType() != Type.MISS) continue;

            Vec3d var10 = var8.subtract(var15);
            float var11 = (float) Math.toDegrees(Math.atan2(var10.z, var10.x)) - 90.0F;
            float var12 = Math.abs(var11 - var3.getYaw());
            var12 = ((var12 + 180.0F) % 360.0F + 360.0F) % 360.0F - 180.0F;
            var12 = Math.abs(var12);

            if (var12 > this.fovSetting.getValue() / 2.0F) continue;

            float var13 = var6.distanceTo(mc.player);
            float var14 = var12 + var13 * 0.5F;
            if (var14 < var2) {
                var2 = var14;
                var1 = var6;
            }
        }

        return var1;
    }
  }
