package project.papus.implement.features.modules.player;

import java.util.Random;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import project.papus.api.event.EventHandler;
import project.papus.api.feature.module.Module;
import project.papus.api.feature.module.ModuleCategory;
import project.papus.api.feature.module.setting.implement.BooleanSetting;
import project.papus.api.feature.module.setting.implement.MultiSelectSetting;
import project.papus.api.feature.module.setting.implement.ValueSetting;
import project.papus.implement.events.player.TickEvent;

public class KillAura extends Module {

    // Настройки из скриншота
    private final ValueSetting rangeSetting;
    private final ValueSetting yawMinSetting;
    private final ValueSetting yawMaxSetting;
    private final ValueSetting pitchMinSetting;
    private final ValueSetting pitchMaxSetting;
    private final ValueSetting hitYawSetting;
    private final ValueSetting predictionSetting;
    private final ValueSetting offsetYawSetting;
    private final ValueSetting oscXSetting;
    
    private final ValueSetting minCpsSetting;
    private final ValueSetting maxCpsSetting;
    private final BooleanSetting silentRotSetting;
    private final MultiSelectSetting targetsSetting;

    private final Random random = new Random();
    
    private long lastAttackTime = 0;
    private long nextAttackDelay = 0;
    private LivingEntity currentTarget = null;
    
    private float silentYaw = 0;
    private float silentPitch = 0;
    private float lastSentYaw = Float.MAX_VALUE;
    private float lastSentPitch = Float.MAX_VALUE;
    private int attackDelayTicks = 0;

    public KillAura() {
        super("KillAura", "Автоатака", ModuleCategory.PLAYER);

        // Настройки из твоего скриншота
        this.rangeSetting = new ValueSetting("Дистанция", "").setValue(3.0F).range(2.0F, 3.2F);
        this.yawMinSetting = new ValueSetting("Yaw мин", "").setValue(35F).range(1F, 180F);
        this.yawMaxSetting = new ValueSetting("Yaw макс", "").setValue(40F).range(1F, 180F);
        this.pitchMinSetting = new ValueSetting("Pitch мин", "").setValue(20F).range(1F, 90F);
        this.pitchMaxSetting = new ValueSetting("Pitch макс", "").setValue(25F).range(1F, 90F);
        
        this.hitYawSetting = new ValueSetting("Удар Yaw", "").setValue(15F).range(1F, 90F);
        this.predictionSetting = new ValueSetting("Упреждение", "").setValue(0.10F).range(0.0F, 1.0F);
        this.offsetYawSetting = new ValueSetting("Смещ. Yaw", "").setValue(-1.0F).range(-10F, 10F);
        this.oscXSetting = new ValueSetting("Осц. X", "").setValue(0.10F).range(0.0F, 1.0F);

        this.minCpsSetting = new ValueSetting("Min CPS", "").setValue(8F).range(1F, 15F);
        this.maxCpsSetting = new ValueSetting("Max CPS", "").setValue(12F).range(1F, 15F);
        
        this.silentRotSetting = new BooleanSetting("Silent Aim", "Скрыть наводку").setValue(true);
        this.targetsSetting = new MultiSelectSetting("Цели", "").value("Игроки", "Мобы");
        this.targetsSetting.getSelected().add("Игроки");

        this.setup(
            this.rangeSetting, this.yawMinSetting, this.yawMaxSetting,
            this.pitchMinSetting, this.pitchMaxSetting, this.hitYawSetting,
            this.predictionSetting, this.offsetYawSetting, this.oscXSetting,
            this.minCpsSetting, this.maxCpsSetting,
            this.silentRotSetting, this.targetsSetting
        );
    }

    @Override
    public void deactivate() {
        currentTarget = null;
        lastSentYaw = Float.MAX_VALUE;
        lastSentPitch = Float.MAX_VALUE;
        super.deactivate();
    }

    @EventHandler
    public void onTick(TickEvent event) {
        if (mc.player == null || mc.world == null) return;

        currentTarget = findTarget();
        if (currentTarget == null) {
            silentYaw = mc.player.getYaw();
            silentPitch = mc.player.getPitch();
            lastSentYaw = Float.MAX_VALUE;
            lastSentPitch = Float.MAX_VALUE;
            attackDelayTicks = 0;
            return;
        }

        // 1. ПРЕДУГАДЫВАНИЕ (Prediction)
        // Берем позицию цели + (скорость * упреждение)
        Vec3d predictedPos = currentTarget.getPos().add(
                currentTarget.getVelocity().multiply(predictionSetting.getValue())
        );

        // 2. ОСЦИЛЛЯЦИЯ (Гуляние точки)
        // Синусоида по X
        double time = (System.currentTimeMillis() % 2000) / 1000.0;
        double oscX = Math.sin(time * Math.PI) * oscXSetting.getValue();
        predictedPos = predictedPos.add(oscX, 0, 0);

        Vec3d eye = mc.player.getPos().add(0.0, mc.player.getEyeHeight(mc.player.getPose()), 0.0);
        // Целимся в глаза (как на скрине), а не в центр
        Vec3d targetEyes = predictedPos.add(0.0, currentTarget.getHeight() * 0.9, 0.0); 

        // 3. СМЕЩЕНИЕ (Offset)
        float targetYaw = getTargetYaw(targetEyes) + offsetYawSetting.getValue();
        float targetPitch = getTargetPitch(eye, targetEyes);

        // 4. РАСЧЕТ СКОРОСТИ (Случайная между мин и макс)
        float currentYawSpeed = yawMinSetting.getValue() + random.nextFloat() * (yawMaxSetting.getValue() - yawMinSetting.getValue());
        float currentPitchSpeed = pitchMinSetting.getValue() + random.nextFloat() * (pitchMaxSetting.getValue() - pitchMinSetting.getValue());

        // Если мы бьем, используем скорость Удара (Hit Yaw)
        boolean isAttacking = System.currentTimeMillis() >= lastAttackTime + nextAttackDelay - 100;
        if (isAttacking) {
            currentYawSpeed = hitYawSetting.getValue(); 
        }

        float yawDiff = normalize(targetYaw - silentYaw);
        float pitchDiff = normalize(targetPitch - silentPitch);

        // Плавный шаг с учетом скорости и GCD
        float stepYaw = MathHelper.clamp(yawDiff, -currentYawSpeed, currentYawSpeed);
        float stepPitch = MathHelper.clamp(pitchDiff, -currentPitchSpeed, currentPitchSpeed);
        
        float gcd = getGCD();
        silentYaw += Math.round(stepYaw / gcd) * gcd;
        silentPitch += Math.round(stepPitch / gcd) * gcd;

        silentYaw = normalize(silentYaw);
        // Ограничиваем Pitch (как в настройках -75 до 40)
        silentPitch = MathHelper.clamp(silentPitch, -75.0F, 40.0F);

        // Отправка пакета
        if (silentRotSetting.isValue()) {
            if (Math.abs(silentYaw - lastSentYaw) > 0.001F || Math.abs(silentPitch - lastSentPitch) > 0.001F) {
                mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                        silentYaw, silentPitch, mc.player.isOnGround(), mc.player.horizontalCollision
                ));
                lastSentYaw = silentYaw;
                lastSentPitch = silentPitch;
            }
        }

        // Задержка удара (PacketOrder)
        if (attackDelayTicks < 2) {
            attackDelayTicks++;
            return;
        }

        // Логика удара
        long now = System.currentTimeMillis();
        if (now >= lastAttackTime + nextAttackDelay) {
            if (mc.player.distanceTo(currentTarget) <= rangeSetting.getValue()) {
                mc.getNetworkHandler().sendPacket(PlayerInteractEntityC2SPacket.attack(currentTarget, mc.player.isSneaking()));
                mc.player.swingHand(Hand.MAIN_HAND);
                lastAttackTime = now;
                calculateNextDelay();
                attackDelayTicks = 0;
            }
        }
    }

    private float getGCD() {
        float sens = mc.options.getMouseSensitivity().getValue().floatValue();
        float f = sens * 0.6F + 0.2F;
        return f * f * f * 8.0F * 0.15F;
    }

    private void calculateNextDelay() {
        float cps = minCpsSetting.getValue() + random.nextFloat() * (maxCpsSetting.getValue() - minCpsSetting.getValue());
        long baseDelay = (long) (1000.0F / cps);
        this.nextAttackDelay = Math.max(50, baseDelay + (random.nextInt(60) - 25));
    }

    private LivingEntity findTarget() {
        LivingEntity best = null;
        double bestDist = rangeSetting.getValue() * rangeSetting.getValue();
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof LivingEntity living) || living == mc.player || !living.isAlive() || living instanceof ArmorStandEntity) continue;
            if (living instanceof PlayerEntity && !targetsSetting.isSelected("Игроки")) continue;
            if (!(living instanceof PlayerEntity) && !targetsSetting.isSelected("Мобы")) continue;
            if (!mc.player.canSee(living)) continue;
            double dist = mc.player.squaredDistanceTo(living);
            if (dist < bestDist) { bestDist = dist; best = living; }
        }
        return best;
    }

    private float getTargetYaw(Vec3d target) {
        double dx = target.x - mc.player.getX();
        double dz = target.z - mc.player.getZ();
        return normalize((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0F));
    }

    private float getTargetPitch(Vec3d eye, Vec3d target) {
        double dy = target.y - eye.y;
        double distXZ = Math.sqrt(Math.pow(target.x - eye.x, 2) + Math.pow(target.z - eye.z, 2));
        return normalize((float) -Math.toDegrees(Math.atan2(dy, distXZ)));
    }

    private static float normalize(float angle) {
        angle = angle % 360.0F;
        if (angle >= 180.0F) angle -= 360.0F;
        if (angle < -180.0F) angle += 360.0F;
        return angle;
    }
    }
