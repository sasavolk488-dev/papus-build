package project.papus.implement.features.modules.player;

import java.util.Random;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
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

    // Настройки
    private final ValueSetting rangeSetting;
    private final ValueSetting minCpsSetting;
    private final ValueSetting maxCpsSetting;
    private final ValueSetting fovSetting;
    private final ValueSetting rotSpeedSetting;
    private final BooleanSetting silentRotSetting;
    private final BooleanSetting throughWallsSetting;
    private final MultiSelectSetting targetsSetting;

    private final Random random = new Random();
    
    // Переменные
    private long lastAttackTime = 0;
    private long nextAttackDelay = 0;
    private LivingEntity currentTarget = null;
    
    // Углы для Silent Aim (серверные)
    private float silentYaw = 0;
    private float silentPitch = 0;

    public KillAura() {
        super("KillAura", "Автоатака", ModuleCategory.PLAYER);

        this.rangeSetting = new ValueSetting("Дистанция", "").setValue(3.0F).range(2.0F, 4.5F);
        this.minCpsSetting = new ValueSetting("Min CPS", "").setValue(7F).range(1F, 15F);
        this.maxCpsSetting = new ValueSetting("Max CPS", "").setValue(11F).range(1F, 15F);
        
        // Проверка наведения (FOV). Чем меньше, тем точнее нужно смотреть на цель.
        this.fovSetting = new ValueSetting("Проверка FOV", "").setValue(15F).range(5F, 90F);
        
        // Скорость доворота сервера
        this.rotSpeedSetting = new ValueSetting("Скорость поворота", "").setValue(25F).range(5F, 180F);
        
        this.silentRotSetting = new BooleanSetting("Silent Aim", "Скрыть наводку").setValue(true);
        this.throughWallsSetting = new BooleanSetting("Через стены", "").setValue(false);
        
        this.targetsSetting = new MultiSelectSetting("Цели", "").value("Игроки", "Мобы");
        this.targetsSetting.getSelected().add("Игроки");

        this.setup(
            this.rangeSetting,
            this.minCpsSetting,
            this.maxCpsSetting,
            this.fovSetting,
            this.rotSpeedSetting,
            this.silentRotSetting,
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

        // 1. Поиск цели
        currentTarget = findTarget();
        if (currentTarget == null) {
            silentYaw = mc.player.getYaw();
            silentPitch = mc.player.getPitch();
            return;
        }

        // 2. Расчет идеальных углов до цели
        Vec3d eye = mc.player.getPos().add(0.0, mc.player.getHeight(), 0.0);
        Vec3d targetPos = currentTarget.getPos().add(0.0, currentTarget.getHeight() * 0.5, 0.0);

        float targetYaw = getTargetYaw(currentTarget);
        float targetPitch = getTargetPitch(eye, targetPos);

        // 3. Плавная ротация (серверных углов)
        float speed = rotSpeedSetting.getValue();
        float yawDiff = normalize(targetYaw - silentYaw);
        float pitchDiff = normalize(targetPitch - silentPitch);

        // Плавность (Ease-out): чем ближе, тем медленнее
        float ease = Math.min(1.0F, Math.abs(yawDiff) / 15.0F);
        silentYaw += MathHelper.clamp(yawDiff, -speed * ease, speed * ease);
        silentPitch += MathHelper.clamp(pitchDiff, -speed * 0.6F * ease, speed * 0.6F * ease);

        // 4. Проверка наведения (FOV Check)
        float currentFov = Math.abs(normalize(targetYaw - silentYaw));
        if (currentFov > fovSetting.getValue()) {
            return; // Еще не навелись, удар не наносим
        }

        // 5. Логика атаки (Легитный CPS)
        long now = System.currentTimeMillis();
        if (now >= lastAttackTime + nextAttackDelay) {
            
            // Доп. проверка: не бьем, если цель успела отойти
            if (mc.player.distanceTo(currentTarget) <= rangeSetting.getValue()) {
                performAttack(currentTarget);
                lastAttackTime = now;
                calculateNextDelay();
            }
        }
    }

    private void performAttack(LivingEntity target) {
        if (!silentRotSetting.isValue()) {
            // Если Silent выключен — просто бьем
            mc.interactionManager.attackEntity(mc.player, target);
            mc.player.swingHand(Hand.MAIN_HAND);
            return;
        }

        // SILENT AIM (Скрытая наводка)
        // Запоминаем реальные углы твоей камеры
        float realYaw = mc.player.getYaw();
        float realPitch = mc.player.getPitch();

        // Ставим серверные углы
        mc.player.setYaw(silentYaw);
        mc.player.setPitch(silentPitch);
        
        // Отправляем пакет на сервер (сервер видит, что ты смотришь на цель)
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                silentYaw, silentPitch, mc.player.isOnGround(), mc.player.horizontalCollision
        ));

        // Бьем
        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);

        // Возвращаем камеру обратно (ты не видишь наводки)
        mc.player.setYaw(realYaw);
        mc.player.setPitch(realPitch);
    }

    private void calculateNextDelay() {
        float minCps = minCpsSetting.getValue();
        float maxCps = maxCpsSetting.getValue();
        float cps = minCps + random.nextFloat() * (maxCps - minCps);
        
        // Человеческая задержка
        long baseDelay = (long) (1000.0F / cps);
        
        // Рандом (имитация дрожания пальца на мышке)
        long randomDelay = random.nextInt(60) - 25; 
        
        // Редкая пауза (5% шанс), как будто человек моргнул или отвлекся
        if (random.nextInt(100) < 5) {
            randomDelay += 120 + random.nextInt(100);
        }
        
        this.nextAttackDelay = Math.max(50, baseDelay + randomDelay);
    }

    private LivingEntity findTarget() {
        LivingEntity best = null;
        double bestDist = rangeSetting.getValue() * rangeSetting.getValue();

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (living == mc.player) continue;
            if (!living.isAlive()) continue;
            if (living instanceof ArmorStandEntity) continue;

            // Через стены (по умолчанию выключено)
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

    private float getTargetYaw(LivingEntity target) {
        double dx = target.getX() - mc.player.getX();
        double dz = target.getZ() - mc.player.getZ();
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0F);
    }

    private float getTargetPitch(Vec3d eye, Vec3d targetPos) {
        double dy = targetPos.y - eye.y;
        double distXZ = Math.sqrt(
                (targetPos.x - eye.x) * (targetPos.x - eye.x) +
                (targetPos.z - eye.z) * (targetPos.z - eye.z)
        );
        return (float) -Math.toDegrees(Math.atan2(dy, distXZ));
    }

    private static float normalize(float angle) {
        angle = angle % 360.0F;
        if (angle >= 180.0F) angle -= 360.0F;
        if (angle < -180.0F) angle += 360.0F;
        return angle;
    }
            }
