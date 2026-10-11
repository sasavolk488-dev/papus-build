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
    private final ValueSetting smoothSetting;
    private final ValueSetting reactionSetting;
    private final BooleanSetting humanizeSetting;
    private final BooleanSetting testSetting;
    private final MultiSelectSetting targetsSetting;

    private final Random random = new Random();

    // шум / дрожь
    private float noiseYaw;
    private float noisePitch;
    private long lastNoiseUpdate;

    // плавность — интерполяция и реакция
    private LivingEntity lastTarget;
    private long targetAcquiredAt;
    private float accumulatedYaw;
    private float accumulatedPitch;

    public AimAssist() {
        super("AimAssist", "Aim Assist", ModuleCategory.PLAYER);

        this.speedXSetting = new ValueSetting("Скорость X", "").setValue(3.5F).range(0.0F, 10.0F);
        this.speedYSetting = new ValueSetting("Скорость Y", "").setValue(3.5F).range(0.0F, 10.0F);
        this.distanceSetting = new ValueSetting("Дистанция", "").setValue(3.0F).range(2, 8);
        this.fovSetting = new ValueSetting("Поле зрения", "").setValue(180.0F).range(10, 360);
        this.aimHeightSetting = new ValueSetting("Высота наводки", "").setValue(0.5F).range(0.0F, 1.0F);
        this.smoothSetting = new ValueSetting("Плавность", "").setValue(0.35F).range(0.05F, 1.0F);
        this.reactionSetting = new ValueSetting("Реакция (мс)", "").setValue(120.0F).range(0.0F, 400.0F);
        this.humanizeSetting = new BooleanSetting("Humanize", "").setValue(true);
        this.testSetting = new BooleanSetting("Test", "").setValue(false);
        this.targetsSetting = new MultiSelectSetting("Таргеты", "").value("Игроки", "Мобы", "Друзья");

        this.setup(
            this.speedXSetting,
            this.speedYSetting,
            this.distanceSetting,
            this.fovSetting,
            this.aimHeightSetting,
            this.smoothSetting,
            this.reactionSetting,
            this.humanizeSetting,
            this.testSetting,
            this.targetsSetting
        );

        this.targetsSetting.getSelected().add("Игроки");
    }

    @Override
    public void deactivate() {
        lastTarget = null;
        targetAcquiredAt = 0L;
        accumulatedYaw = 0.0F;
        accumulatedPitch = 0.0F;
        super.deactivate();
    }

    @EventHandler
    public void onMouseRotation(MouseRotationEvent event) {
        if (this.isSilent()) return;
        if (mc.player == null || mc.world == null) return;

        LivingEntity target = this.findTarget();

        // сброс при потере цели
        if (target == null) {
            lastTarget = null;
            targetAcquiredAt = 0L;
            return;
        }

        // новая цель — фиксируем время захвата (для реакции)
        if (target != lastTarget) {
            lastTarget = target;
            targetAcquiredAt = System.currentTimeMillis();
        }

        // === РЕАКЦИЯ ===
        long reactionDelay = (long) reactionSetting.getValue();
        if (humanizeSetting.isValue()) {
            reactionDelay += random.nextInt(60) - 30; // ±30мс разброс
        }
        if (reactionDelay > 0 && System.currentTimeMillis() - targetAcquiredAt < reactionDelay) {
            return;
        }

        this.updateNoise();

        Vec3d eye = mc.player.getPos().add(0.0, mc.player.getHeight(), 0.0);

        float heightMul = this.aimHeightSetting.getValue()
                + (humanizeSetting.isValue() ? noisePitch : 0.0F);

        // прицел слегка наводится на разные части (немного смещаем точку)
        Vec3d aimPos = target.getPos().add(
            humanizeSetting.isValue() ? noiseYaw : 0.0,
            target.getHeight() * heightMul,
            humanizeSetting.isValue() ? noiseYaw * 0.5F : 0.0
        );

        Vec3d diff = aimPos.subtract(eye);

        float targetYaw = (float) Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90.0F;
        float targetPitch = (float) -Math.toDegrees(Math.atan2(
                diff.y, Math.sqrt(diff.x * diff.x + diff.z * diff.z)));

        Angle cam = AngleUtil.cameraAngle();

        float yawDelta = normalize(targetYaw - cam.getYaw());
        float pitchDelta = normalize(targetPitch - cam.getPitch());

        if (Math.abs(yawDelta) > fovSetting.getValue() / 2.0F) return;

        // мёртвая зона — если уже наведено, не дёргаем
        if (!testSetting.isValue()
                && Math.abs(yawDelta) < 1.5F
                && Math.abs(pitchDelta) < 1.5F) {
            return;
        }

        // === ПЛАВНОЕ ДВИЖЕНИЕ ===
        // ease-in-out: ближе к цели — медленнее, дальше — быстрее
        float smooth = smoothSetting.getValue();

        float yawStep = easeStep(yawDelta, smooth);
        float pitchStep = easeStep(pitchDelta, smooth);

        // масштаб скорости от настроек
        float sx = speedXSetting.getValue() / 40.0F;
        float sy = speedYSetting.getValue() / 40.0F;

        yawStep *= sx;
        pitchStep *= sy;

        // === ДЖИТТЕР ===
        if (humanizeSetting.isValue()) {
            yawStep += (random.nextFloat() - 0.5F) * 0.4F;
            pitchStep += (random.nextFloat() - 0.5F) * 0.25F;
        }

        // === НАКОПЛЕНИЕ и ОТПРАВКА ===
        accumulatedYaw += yawStep;
        accumulatedPitch += pitchStep;

        int sendX = (int) accumulatedYaw;
        int sendY = (int) accumulatedPitch;

        if (sendX != 0 || sendY != 0) {
            event.setCursorDeltaX(event.getCursorDeltaX() + (float) sendX);
            event.setCursorDeltaY(event.getCursorDeltaY() + (float) sendY);

            accumulatedYaw -= sendX;
            accumulatedPitch -= sendY;
        }
    }

    /**
     * Ease-in-out движение: чем меньше разница, тем медленнее подходим.
     * Имитирует человеческое движение мышью.
     */
    private float easeStep(float delta, float smooth) {
        float abs = Math.abs(delta);

        // нелинейная скорость
        float factor;
        if (abs > 30.0F) {
            factor = 1.0F;          // далеко — быстро
        } else if (abs > 10.0F) {
            factor = 0.75F;         // средне
        } else if (abs > 3.0F) {
            factor = 0.5F;          // близко — точнее
        } else {
            factor = 0.3F;          // почти наведено — медленно
        }

        // ускорение ближе к концу (эффект доводки)
        float eased = delta * factor * smooth;

        // ограничение, чтобы не рывками
        float max = 30.0F;
        return MathHelper.clamp(eased, -max, max);
    }

    private void updateNoise() {
        long now = System.currentTimeMillis();
        if (now - lastNoiseUpdate > 200L) {
            noiseYaw = (random.nextFloat() - 0.5F) * 0.35F;
            noisePitch = (random.nextFloat() - 0.5F) * 0.25F;
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
            if (living == mc.player) continue;
            if (!living.isAlive()) continue;
            if (living instanceof ArmorStandEntity) continue;
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
