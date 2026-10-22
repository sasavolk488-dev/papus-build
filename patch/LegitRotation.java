package project.papus.implement.features.modules.player;

import java.util.Random;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
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
import project.papus.implement.events.player.TickEvent;

public class LegitRotation extends Module {

    private final ValueSetting speedXSetting;
    private final ValueSetting speedYSetting;
    private final ValueSetting distanceSetting;
    private final ValueSetting fovSetting;
    private final ValueSetting aimHeightSetting;
    private final ValueSetting smoothSetting;
    
    private final BooleanSetting silentSetting;
    private final BooleanSetting humanizeSetting;
    private final MultiSelectSetting targetsSetting;

    private final Random random = new Random();

    private float noiseYaw;
    private float noisePitch;
    private long lastNoiseUpdate;

    private LivingEntity lockedTarget = null;
    private float currentHeight = 0.5F;

    // Для Silent Rotation
    private float silentYaw = 0;
    private float silentPitch = 0;
    private float lastSentYaw = Float.MAX_VALUE;
    private float lastSentPitch = Float.MAX_VALUE;

    public LegitRotation() {
        super("LegitRotation", "Плавная наводка", ModuleCategory.PLAYER);

        this.speedXSetting = new ValueSetting("Скорость X", "").setValue(8.0F).range(0.0F, 20.0F);
        this.speedYSetting = new ValueSetting("Скорость Y", "").setValue(6.0F).range(0.0F, 20.0F);
        
        this.distanceSetting = new ValueSetting("Дистанция", "").setValue(4.0F).range(2, 8);
        this.fovSetting = new ValueSetting("Поле зрения", "").setValue(180.0F).range(10, 360);
        
        this.aimHeightSetting = new ValueSetting("Часть тела (0.0-1.0)", "").setValue(0.8F).range(0.0F, 1.0F);
        this.smoothSetting = new ValueSetting("Плавность", "").setValue(0.5F).range(0.1F, 1.0F);
        
        this.silentSetting = new BooleanSetting("Silent", "Скрыть наводку").setValue(true);
        this.humanizeSetting = new BooleanSetting("Humanize", "").setValue(true);
        
        this.targetsSetting = new MultiSelectSetting("Таргеты", "").value("Игроки", "Мобы", "Друзья");
        this.targetsSetting.getSelected().add("Игроки");

        this.setup(
            this.speedXSetting, this.speedYSetting, this.distanceSetting,
            this.fovSetting, this.aimHeightSetting, this.smoothSetting,
            this.silentSetting, this.humanizeSetting, this.targetsSetting
        );
    }

    @EventHandler
    public void onTick(TickEvent event) {
        if (mc.player == null || mc.world == null) return;

        // Плавная интерполяция высоты
        float targetHeight = aimHeightSetting.getValue();
        currentHeight += (targetHeight - currentHeight) * 0.05F;

        LivingEntity target = findTarget();
        if (target != null) {
            lockedTarget = target;
        } else if (lockedTarget != null && isValidTarget(lockedTarget)) {
            target = lockedTarget;
        } else {
            silentYaw = mc.player.getYaw();
            silentPitch = mc.player.getPitch();
            return;
        }

        this.updateNoise();

        Vec3d eye = mc.player.getPos().add(0.0, mc.player.getHeight(), 0.0);
        float finalHeight = currentHeight + (humanizeSetting.isValue() ? noisePitch : 0.0F);
        
        Vec3d aimPos = target.getPos().add(
            humanizeSetting.isValue() ? noiseYaw : 0.0,
            target.getHeight() * finalHeight,
            humanizeSetting.isValue() ? noiseYaw * 0.5F : 0.0
        );

        Vec3d diff = aimPos.subtract(eye);
        float targetYaw = (float) Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90.0F;
        float targetPitch = (float) -Math.toDegrees(Math.atan2(diff.y, Math.sqrt(diff.x * diff.x + diff.z * diff.z)));

        Angle cam = AngleUtil.cameraAngle();
        float yawDelta = normalize(targetYaw - cam.getYaw());
        float pitchDelta = normalize(targetPitch - cam.getPitch());

        if (Math.abs(yawDelta) > fovSetting.getValue() / 2.0F) return;
        if (Math.abs(yawDelta) < 1.0F && Math.abs(pitchDelta) < 1.0F) return;

        // ПЛАВНЫЙ ПОВОРОТ (Ease-out)
        float smooth = this.smoothSetting.getValue();
        float yawEase = Math.min(1.0F, Math.abs(yawDelta) / 15.0F); 
        float pitchEase = Math.min(1.0F, Math.abs(pitchDelta) / 10.0F);

        float yawStep = (yawDelta * yawEase) * (smooth * 0.15F);
        float pitchStep = (pitchDelta * pitchEase) * (smooth * 0.15F);

        yawStep *= (speedXSetting.getValue() / 10.0F);
        pitchStep *= (speedYSetting.getValue() / 10.0F);

        yawStep = MathHelper.clamp(yawStep, -4.0F, 4.0F);
        pitchStep = MathHelper.clamp(pitchStep, -3.0F, 3.0F);

        if (humanizeSetting.isValue()) {
            yawStep += (random.nextFloat() - 0.5F) * 0.1F;
            pitchStep += (random.nextFloat() - 0.5F) * 0.1F;
        }

        if (silentSetting.isValue()) {
            // Silent: отправляем пакет, но камера на месте
            silentYaw += yawStep;
            silentPitch += pitchStep;
            silentYaw = normalize(silentYaw);
            silentPitch = MathHelper.clamp(silentPitch, -90.0F, 90.0F);

            if (Math.abs(silentYaw - lastSentYaw) > 0.001F || Math.abs(silentPitch - lastSentPitch) > 0.001F) {
                mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                        silentYaw, silentPitch, mc.player.isOnGround(), mc.player.horizontalCollision
                ));
                lastSentYaw = silentYaw;
                lastSentPitch = silentPitch;
            }
        } else {
            // Визуальный поворот (камера двигается)
            mc.player.setYaw(mc.player.getYaw() + yawStep);
            mc.player.setPitch(mc.player.getPitch() + pitchStep);
        }
    }

    private boolean isValidTarget(LivingEntity living) {
        if (living == null || !living.isAlive() || living == mc.player) return false;
        if (living.distanceTo(mc.player) > distanceSetting.getValue()) return false;
        
        Vec3d eye = mc.player.getPos().add(0.0, mc.player.getHeight(), 0.0);
        Vec3d tpos = living.getPos().add(0.0, living.getHeight() * currentHeight, 0.0);
        Vec3d diff = tpos.subtract(eye);
        float angle = (float) Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90.0F;
        float angleDelta = Math.abs(normalize(angle - AngleUtil.cameraAngle().getYaw()));
        
        return angleDelta <= fovSetting.getValue() / 2.0F;
    }

    private void updateNoise() {
        long now = System.currentTimeMillis();
        if (now - lastNoiseUpdate > 300L) {
            noiseYaw = (random.nextFloat() - 0.5F) * 0.2F;
            noisePitch = (random.nextFloat() - 0.5F) * 0.15F;
            lastNoiseUpdate = now;
        }
    }

    private static float normalize(float angle) {
        angle = angle % 360.0F;
        if (angle >= 180.0F) angle -= 360.0F;
        if (angle < -180.0F) angle += 360.0F;
        return angle;
    }

    private LivingEntity findTarget() {
        LivingEntity best = null;
        float bestScore = Float.MAX_VALUE;
        Angle camera = AngleUtil.cameraAngle();

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (living == mc.player || !living.isAlive() || living instanceof ArmorStandEntity) continue;
            if (living.distanceTo(mc.player) > distanceSetting.getValue()) continue;

            if (living instanceof PlayerEntity player) {
                if (!targetsSetting.isSelected("Игроки")) continue;
                boolean isFriend = FriendUtils.getFriends().stream()
                        .anyMatch(f -> f.getName().equals(player.getName().getString()));
                if (isFriend && !targetsSetting.isSelected("Друзья")) continue;
            } else {
                if (!targetsSetting.isSelected("Мобы")) continue;
            }

            Vec3d eye = mc.player.getPos().add(0.0, mc.player.getHeight(), 0.0);
            Vec3d tpos = living.getPos().add(0.0, living.getHeight() * 0.7, 0.0);

            RaycastContext ctx = new RaycastContext(
                    eye, tpos, ShapeType.COLLIDER, FluidHandling.NONE, mc.player);
            if (mc.world.raycast(ctx).getType() != Type.MISS) continue;

            Vec3d diff = tpos.subtract(eye);
            float angle = (float) Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90.0F;
            float angleDelta = Math.abs(normalize(angle - camera.getYaw()));

            if (angleDelta > fovSetting.getValue() / 2.0F) continue;

            float score = angleDelta + living.distanceTo(mc.player) * 0.5F;
            if (score < bestScore) {
                bestScore = score;
                best = living;
            }
        }
        return best;
    }
  }
