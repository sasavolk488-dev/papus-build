package project.papus.implement.features.modules.player;

import java.util.Random;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import project.papus.api.event.EventHandler;
import project.papus.api.feature.module.Module;
import project.papus.api.feature.module.ModuleCategory;
import project.papus.api.feature.module.setting.implement.BooleanSetting;
import project.papus.api.feature.module.setting.implement.MultiSelectSetting;
import project.papus.api.feature.module.setting.implement.ValueSetting;
import project.papus.common.util.rotation.Angle;
import project.papus.common.util.rotation.AngleUtil;
import project.papus.implement.events.player.TickEvent;

public class KillAura extends Module {

    private final ValueSetting rangeSetting;
    private final ValueSetting minCpsSetting;
    private final ValueSetting maxCpsSetting;
    private final ValueSetting rotSpeedSetting;
    private final BooleanSetting critOnlySetting;
    private final BooleanSetting throughWallsSetting;
    private final MultiSelectSetting targetsSetting;

    private final Random random = new Random();
    private long lastAttackTime = 0;
    private long nextAttackDelay = 0;
    private LivingEntity currentTarget = null;

    public KillAura() {
        super("KillAura", "Автоатака ближайшего игрока", ModuleCategory.PLAYER);

        this.rangeSetting = new ValueSetting("Дистанция", "").setValue(3.0F).range(2.0F, 6.0F);
        this.minCpsSetting = new ValueSetting("Min CPS", "").setValue(7F).range(1F, 20F);
        this.maxCpsSetting = new ValueSetting("Max CPS", "").setValue(12F).range(1F, 20F);
        this.rotSpeedSetting = new ValueSetting("Скорость поворота", "").setValue(45F).range(10F, 180F);
        
        this.critOnlySetting = new BooleanSetting("Только криты", "").setValue(false);
        this.throughWallsSetting = new BooleanSetting("Через стены", "").setValue(false);
        
        this.targetsSetting = new MultiSelectSetting("Цели", "").value("Игроки", "Мобы");
        this.targetsSetting.getSelected().add("Игроки");

        this.setup(
            this.rangeSetting,
            this.minCpsSetting,
            this.maxCpsSetting,
            this.rotSpeedSetting,
            this.critOnlySetting,
            this.throughWallsSetting,
            this.targetsSetting
        );
    }

    @Override
    public void deactivate() {
        currentTarget = null;
        super.deactivate();
    }

    @EventHandler
    public void onTick(TickEvent event) {
        if (mc.player == null || mc.world == null) return;

        currentTarget = findTarget();
        if (currentTarget == null) return;

        if (mc.player.distanceTo(currentTarget) > rangeSetting.getValue()) return;
        if (critOnlySetting.isValue() && !canCrit()) return;

        rotateTo(currentTarget, rotSpeedSetting.getValue());

        Angle cam = AngleUtil.cameraAngle();
        float yawDiff = Math.abs(normalize(getTargetYaw(currentTarget) - cam.getYaw()));
        if (yawDiff > 45.0F) return;

        long now = System.currentTimeMillis();
        if (now - lastAttackTime >= nextAttackDelay) {
            attack(currentTarget);
            lastAttackTime = now;
            
            float minCps = minCpsSetting.getValue();
            float maxCps = maxCpsSetting.getValue();
            float cps = minCps + random.nextFloat() * (maxCps - minCps);
            nextAttackDelay = (long) (1000.0F / cps) + (random.nextInt(20) - 10);
        }
    }

    private void attack(LivingEntity target) {
        if (critOnlySetting.isValue() && mc.player.isSprinting()) {
            mc.player.setSprinting(false);
        }

        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);
        
        if (critOnlySetting.isValue()) {
            mc.player.setSprinting(true);
        }
    }

    private void rotateTo(LivingEntity target, float speed) {
        Vec3d eye = mc.player.getPos().add(0.0, mc.player.getHeight(), 0.0);
        Vec3d targetPos = target.getPos().add(0.0, target.getHeight() * 0.5, 0.0);

        float targetYaw = getTargetYaw(target);
        float targetPitch = getTargetPitch(target, eye, targetPos);

        Angle cam = AngleUtil.cameraAngle();
        float yawDiff = normalize(targetYaw - cam.getYaw());
        float pitchDiff = normalize(targetPitch - cam.getPitch());

        float newYaw = cam.getYaw() + MathHelper.clamp(yawDiff, -speed, speed);
        float newPitch = cam.getPitch() + MathHelper.clamp(pitchDiff, -speed * 0.7F, speed * 0.7F);

        mc.player.setYaw(newYaw);
        mc.player.setPitch(newPitch);
    }

    private float getTargetYaw(LivingEntity target) {
        double dx = target.getX() - mc.player.getX();
        double dz = target.getZ() - mc.player.getZ();
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0F);
    }

    private float getTargetPitch(LivingEntity target, Vec3d eye, Vec3d targetPos) {
        double dy = targetPos.y - eye.y;
        double distXZ = Math.sqrt(
                (targetPos.x - eye.x) * (targetPos.x - eye.x) +
                (targetPos.z - eye.z) * (targetPos.z - eye.z)
        );
        return (float) -Math.toDegrees(Math.atan2(dy, distXZ));
    }

    private LivingEntity findTarget() {
        LivingEntity best = null;
        double bestDist = rangeSetting.getValue() * rangeSetting.getValue();

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (living == mc.player) continue;
            if (!living.isAlive()) continue;
            if (living instanceof ArmorStandEntity) continue;

            if (!throughWallsSetting.isValue() && !mc.player.canSee(living)) continue;

            if (living instanceof PlayerEntity) {
                if (!targetsSetting.isSelected("Игроки")) continue;
            } else {
                if (!targetsSetting.isSelected("Мобы")) continue;
            }

            double dist = mc.player.squaredDistanceTo(living);
            if (dist < bestDist) {
                bestDist = dist;
                best = living;
            }
        }

        return best;
    }

    private boolean canCrit() {
        return !mc.player.isOnGround()
                && !mc.player.isTouchingWater()
                && !mc.player.isClimbing()
                && mc.player.getVehicle() == null;
    }

    private static float normalize(float angle) {
        angle = angle % 360.0F;
        if (angle >= 180.0F) angle -= 360.0F;
        if (angle < -180.0F) angle += 360.0F;
        return angle;
    }
              }
