package project.papus.implement.features.modules;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import project.papus.api.event.EventHandler;
import project.papus.api.feature.module.Module;
import project.papus.api.feature.module.ModuleCategory;
import project.papus.api.feature.module.setting.BooleanSetting;
import project.papus.api.feature.module.setting.MultiSelectSetting;
import project.papus.api.feature.module.setting.ValueSetting;
import project.papus.implement.events.player.TickEvent;

import java.util.Comparator;
import java.util.Random;

public class KillAura extends Module {

    // Настройки модуля
    private final ValueSetting rangeSetting;
    private final ValueSetting cpsSetting; // Скорость ударов
    private final BooleanSetting critsSetting; // Проверка на криты
    private final BooleanSetting silentRotSetting;
    private final BooleanSetting playersSetting; // Бить ли игроков
    private final BooleanSetting mobsSetting; // Бить ли мобов

    private final Random random = new Random();
    private LivingEntity currentTarget;
    private long lastAttackTime = 0;

    // Переменные для ротации
    private float silentYaw = 0;
    private float silentPitch = 0;
    private float lastSentYaw = Float.MAX_VALUE;
    private float lastSentPitch = Float.MAX_VALUE;

    public KillAura() {
        super("KillAura", "Автоатака с критами и наводкой", ModuleCategory.COMBAT);

        this.rangeSetting = new ValueSetting("Дистанция", 3.0f, 2.5f, 6.0f);
        this.cpsSetting = new ValueSetting("CPS (Скорость)", 10.0f, 1.0f, 20.0f);
        this.critsSetting = new BooleanSetting("Криты", true);
        this.silentRotSetting = new BooleanSetting("Silent Rotate", true);
        this.playersSetting = new BooleanSetting("Игроки", true);
        this.mobsSetting = new BooleanSetting("Мобы", false);

        this.setup(rangeSetting, cpsSetting, critsSetting, silentRotSetting, playersSetting, mobsSetting);
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

        // 1. Ищем цель (Наводка)
        currentTarget = findTarget();
        if (currentTarget == null) {
            return;
        }

        // 2. Считаем углы для наводки
        Vec3d eyePos = mc.player.getEyePos();
        Vec3d targetPos = currentTarget.getPos().add(0, currentTarget.getHeight() / 2.0, 0); // Бьем в центр тела

        float targetYaw = getTargetYaw(eyePos, targetPos);
        float targetPitch = getTargetPitch(eyePos, targetPos);

        // 3. Плавная ротация (Silent Rotation)
        if (silentRotSetting.isValue()) {
            silentYaw = smoothRotation(silentYaw, targetYaw, 45f);
            silentPitch = smoothRotation(silentPitch, targetPitch, 45f);
            
            // Отправляем пакет поворота на сервер
            if (Math.abs(silentYaw - lastSentYaw) > 1.0f || Math.abs(silentPitch - lastSentPitch) > 1.0f) {
                mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(silentYaw, silentPitch, mc.player.isOnGround()));
                lastSentYaw = silentYaw;
                lastSentPitch = silentPitch;
            }
        }

        // 4. Проверка дистанции
        if (mc.player.distanceTo(currentTarget) > rangeSetting.getValue()) return;

        // 5. Проверка наводки (прямая видимость)
        if (!mc.player.canSee(currentTarget)) return;

        // 6. Проверка критов
        if (critsSetting.isValue() && !canCrit()) return;

        // 7. Логика удара (CPS)
        long currentTime = System.currentTimeMillis();
        int delay = (int) (1000 / cpsSetting.getValue());

        if (currentTime - lastAttackTime >= delay) {
            // Если включен Silent Rotate, поворачиваем игрока перед ударом
            if (silentRotSetting.isValue()) {
                mc.player.setYaw(silentYaw);
                mc.player.setPitch(silentPitch);
            }

            // Атакуем
            mc.interactionManager.attackEntity(mc.player, currentTarget);
            mc.player.swingHand(Hand.MAIN_HAND);
            lastAttackTime = currentTime;
        }
    }

    // --- ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ---

    // Поиск цели
    private LivingEntity findTarget() {
        return mc.world.getEntitiesByClass(LivingEntity.class, mc.player.getBoundingBox().expand(rangeSetting.getValue()), entity -> {
            if (entity == mc.player || !entity.isAlive()) return false;
            if (entity instanceof PlayerEntity && !playersSetting.isValue()) return false;
            if (entity instanceof MobEntity && !mobsSetting.isValue()) return false;
            
            // Проверка на античит (не бить мертвых или невидимых)
            return !entity.isInvulnerable() && entity.getHealth() > 0;
        }).stream().min(Comparator.comparingDouble(mc.player::distanceTo)).orElse(null);
    }

    // Проверка на крит
    private boolean canCrit() {
        // Крит возможен, если мы падаем, не на земле, не в воде, не на лестнице, нет слепоты и мы не спринтим
        return mc.player.fallDistance > 0.0F 
                && !mc.player.isOnGround() 
                && !mc.player.isTouchingWater() 
                && !mc.player.isInLava() 
                && !mc.player.isClimbing() 
                && !mc.player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.BLINDNESS) 
                && !mc.player.isRiding()
                && !mc.player.isSprinting(); // Спринт отменяет крит в новых версиях
    }

    // Плавный поворот (чтобы античит не палил)
    private float smoothRotation(float current, float target, float speed) {
        float delta = MathHelper.wrapDegrees(target - current);
        if (delta > speed) delta = speed;
        if (delta < -speed) delta = -speed;
        return current + delta;
    }

    // Математика для Yaw
    private float getTargetYaw(Vec3d eyePos, Vec3d targetPos) {
        double x = targetPos.x - eyePos.x;
        double z = targetPos.z - eyePos.z;
        return (float) (Math.toDegrees(Math.atan2(z, x)) - 90.0F);
    }

    // Математика для Pitch
    private float getTargetPitch(Vec3d eyePos, Vec3d targetPos) {
        double x = targetPos.x - eyePos.x;
        double y = targetPos.y - eyePos.y;
        double z = targetPos.z - eyePos.z;
        double dist = Math.sqrt(x * x + z * z);
        return (float) (-Math.toDegrees(Math.atan2(y, dist)));
    }
            }
