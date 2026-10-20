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

    private final ValueSetting rangeSetting;
    private final BooleanSetting useAIRecordSetting;
    private final BooleanSetting silentRotSetting;
    private final MultiSelectSetting targetsSetting;

    private final Random random = new Random();
    private long lastAttackTime = 0;
    private LivingEntity currentTarget = null;
    
    private float silentYaw = 0;
    private float silentPitch = 0;
    private float lastSentYaw = Float.MAX_VALUE;
    private float lastSentPitch = Float.MAX_VALUE;

    // Индекс воспроизведения записи
    private int playbackIndex = 0;

    public KillAura() {
        super("KillAura", "Автоатака", ModuleCategory.PLAYER);

        this.rangeSetting = new ValueSetting("Дистанция", "").setValue(3.0F).range(2.0F, 3.2F);
        this.useAIRecordSetting = new BooleanSetting("Использовать ИИ-Запись", "").setValue(true);
        this.silentRotSetting = new BooleanSetting("Silent Aim", "").setValue(true);
        
        this.targetsSetting = new MultiSelectSetting("Цели", "").value("Игроки", "Мобы");
        this.targetsSetting.getSelected().add("Игроки");

        this.setup(
            this.rangeSetting, this.useAIRecordSetting, 
            this.silentRotSetting, this.targetsSetting
        );
    }

    @Override
    public void deactivate() {
        currentTarget = null;
        playbackIndex = 0;
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
            playbackIndex = 0;
            return;
        }

        // Если включена ИИ-запись и она не пустая
        if (useAIRecordSetting.isValue() && !AuraRecorder.recordedYawSteps.isEmpty()) {
            // Берем следующий шаг из нашей записи
            if (playbackIndex >= AuraRecorder.recordedYawSteps.size()) {
                playbackIndex = 0; // Зацикливаем
            }

            float recordedYaw = AuraRecorder.recordedYawSteps.get(playbackIndex);
            float recordedPitch = AuraRecorder.recordedPitchSteps.get(playbackIndex);

            // Добавляем небольшую случайность, чтобы античит не видел идеальный цикл
            silentYaw += recordedYaw * (0.9F + random.nextFloat() * 0.2F);
            silentPitch += recordedPitch * (0.9F + random.nextFloat() * 0.2F);

            playbackIndex++;
        } else {
            // Обычная ротация (если записи нет)
            Vec3d eye = mc.player.getPos().add(0.0, mc.player.getHeight(), 0.0);
            Vec3d targetPos = currentTarget.getPos().add(0.0, currentTarget.getHeight() * 0.6, 0.0);
            float targetYaw = getTargetYaw(targetPos);
            float targetPitch = getTargetPitch(eye, targetPos);

            float stepYaw = MathHelper.clamp(normalize(targetYaw - silentYaw), -25.0F, 25.0F);
            float stepPitch = MathHelper.clamp(normalize(targetPitch - silentPitch), -15.0F, 15.0F);
            
            silentYaw += stepYaw;
            silentPitch += stepPitch;
        }

        silentYaw = normalize(silentYaw);
        silentPitch = MathHelper.clamp(silentPitch, -89.0F, 89.0F);

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

        // Проверка, наведены ли мы (FOV)
        float targetYawReal = getTargetYaw(currentTarget.getPos());
        if (Math.abs(normalize(targetYawReal - silentYaw)) > 30.0F) return;

        // Удар
        long now = System.currentTimeMillis();
        if (now >= lastAttackTime + 100) {
            if (mc.player.distanceTo(currentTarget) <= rangeSetting.getValue()) {
                mc.getNetworkHandler().sendPacket(PlayerInteractEntityC2SPacket.attack(currentTarget, mc.player.isSneaking()));
                mc.player.swingHand(Hand.MAIN_HAND);
                lastAttackTime = now;
            }
        }
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
