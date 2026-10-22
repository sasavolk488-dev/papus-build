package project.papus.implement.features.modules.player;

import java.util.Random;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MaceItem;
import net.minecraft.item.SwordItem;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
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
import project.papus.implement.events.keyboard.MouseRotationEvent;
import project.papus.implement.events.player.TickEvent;

public class LegitKillAura extends Module {

    private final ValueSetting rangeSetting;
    private final ValueSetting minCpsSetting;
    private final ValueSetting maxCpsSetting;
    private final ValueSetting fovSetting;
    private final ValueSetting speedXSetting;
    private final ValueSetting speedYSetting;
    private final ValueSetting aimHeightSetting;
    private final ValueSetting smoothSetting;
    private final BooleanSetting weaponOnlySetting; // НОВАЯ НАСТРОЙКА
    private final MultiSelectSetting targetsSetting;

    private final Random random = new Random();

    private long lastAttackTime = 0;
    private long nextAttackDelay = 0;
    private LivingEntity currentTarget = null;
    
    private float currentHeight = 0.5F;

    public LegitKillAura() {
        super("LegitKillAura", "Плавная аура + Триггер", ModuleCategory.PLAYER);

        this.rangeSetting = new ValueSetting("Дистанция", "").setValue(3.0F).range(2.0F, 3.2F);
        this.minCpsSetting = new ValueSetting("Min CPS", "").setValue(7F).range(1F, 15F);
        this.maxCpsSetting = new ValueSetting("Max CPS", "").setValue(11F).range(1F, 15F);
        this.fovSetting = new ValueSetting("FOV", "").setValue(90F).range(10F, 180F);
        
        this.speedXSetting = new ValueSetting("Скорость X", "").setValue(6.0F).range(0.0F, 20.0F);
        this.speedYSetting = new ValueSetting("Скорость Y", "").setValue(4.0F).range(0.0F, 20.0F);
        this.aimHeightSetting = new ValueSetting("Часть тела", "").setValue(0.8F).range(0.0F, 1.0F);
        this.smoothSetting = new ValueSetting("Плавность", "").setValue(0.6F).range(0.1F, 1.0F);
        
        // НОВАЯ ГАЛОЧКА
        this.weaponOnlySetting = new BooleanSetting("Только с оружием", "Работает только с мечом/булавой").setValue(true);

        this.targetsSetting = new MultiSelectSetting("Цели", "").value("Игроки", "Мобы");
        this.targetsSetting.getSelected().add("Игроки");

        this.setup(
            this.rangeSetting, this.minCpsSetting, this.maxCpsSetting, this.fovSetting,
            this.speedXSetting, this.speedYSetting, this.aimHeightSetting,
            this.smoothSetting, this.weaponOnlySetting, this.targetsSetting
        );
    }

    @EventHandler
    public void onTick(TickEvent event) {
        if (mc.player == null || mc.world == null) return;

        // Проверка на оружие
        if (!isWeaponInHand()) return;

        currentTarget = findTarget();
        if (currentTarget == null) return;

        // Проверка: смотрим ли мы прямо на цель
        boolean isAimingAtTarget = mc.crosshairTarget instanceof EntityHitResult hitResult 
                && hitResult.getEntity() == currentTarget;

        if (!isAimingAtTarget) {
            currentTarget = null;
            return;
        }

        long now = System.currentTimeMillis();
        if (now >= lastAttackTime + nextAttackDelay) {
            attack(currentTarget);
            lastAttackTime = now;
            calculateNextDelay();
        }
    }

    @EventHandler
    public void onMouseRotation(MouseRotationEvent event) {
        if (mc.player == null || mc.world == null) return;

        // Проверка на оружие
        if (!isWeaponInHand()) return;
        
        // Если цели нет — ищем её только для наводки
        if (currentTarget == null || !currentTarget.isAlive()) {
            LivingEntity targetForAim = findTarget();
            if (targetForAim != null) {
                smoothAim(event, targetForAim);
            }
            return;
        }

        smoothAim(event, currentTarget);
    }

    // ПРОВЕРКА: В РУКЕ МЕЧ ИЛИ БУЛАВА
    private boolean isWeaponInHand() {
        if (!weaponOnlySetting.isValue()) return true; // Если галочка выключена, бьем чем угодно
        
        ItemStack stack = mc.player.getMainHandStack();
        if (stack == null || stack.isEmpty()) return false;

        // Проверяем меч или булаву
        return stack.getItem() instanceof SwordItem || stack.getItem() instanceof MaceItem;
    }

    private void smoothAim(MouseRotationEvent event, LivingEntity target) {
        float targetHeight = aimHeightSetting.getValue();
        currentHeight += (targetHeight - currentHeight) * 0.05F;

        Vec3d eye = mc.player.getPos().add(0.0, mc.player.getHeight(), 0.0);
        Vec3d aimPos = target.getPos().add(0.0, target.getHeight() * currentHeight, 0.0);

        Vec3d diff = aimPos.subtract(eye);
        float targetYaw = (float) Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90.0F;
        float targetPitch = (float) -Math.toDegrees(Math.atan2(diff.y, Math.sqrt(diff.x * diff.x + diff.z * diff.z)));

        Angle cam = AngleUtil.cameraAngle();
        float yawDelta = normalize(targetYaw - cam.getYaw());
        float pitchDelta = normalize(targetPitch - cam.getPitch());

        if (Math.abs(yawDelta) > fovSetting.getValue() / 2.0F) return;
        if (Math.abs(yawDelta) < 1.0F && Math.abs(pitchDelta) < 1.0F) return;

        float smooth = this.smoothSetting.getValue();
        float yawEase = Math.min(1.0F, Math.abs(yawDelta) / 15.0F); 
        float pitchEase = Math.min(1.0F, Math.abs(pitchDelta) / 10.0F);

        float yawStep = (yawDelta * yawEase) * (smooth * 0.15F);
        float pitchStep = (pitchDelta * pitchEase) * (smooth * 0.15F);

        yawStep *= (speedXSetting.getValue() / 10.0F);
        pitchStep *= (speedYSetting.getValue() / 10.0F);

        yawStep = MathHelper.clamp(yawStep, -4.0F, 4.0F);
        pitchStep = MathHelper.clamp(pitchStep, -3.0F, 3.0F);

        event.setCursorDeltaX(event.getCursorDeltaX() + yawStep);
        event.setCursorDeltaY(event.getCursorDeltaY() + pitchStep);
    }

    private void attack(LivingEntity target) {
        mc.getNetworkHandler().sendPacket(PlayerInteractEntityC2SPacket.attack(target, mc.player.isSneaking()));
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private void calculateNextDelay() {
        float cps = minCpsSetting.getValue() + random.nextFloat() * (maxCpsSetting.getValue() - minCpsSetting.getValue());
        long baseDelay = (long) (1000.0F / cps);
        this.nextAttackDelay = Math.max(50, baseDelay + (random.nextInt(60) - 25));
    }

    private LivingEntity findTarget() {
        LivingEntity best = null;
        double bestDist = rangeSetting.getValue() * rangeSetting.getValue();
        Angle camera = AngleUtil.cameraAngle();

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof LivingEntity living) || living == mc.player || !living.isAlive() || living instanceof ArmorStandEntity) continue;
            
            if (living instanceof PlayerEntity && !targetsSetting.isSelected("Игроки")) continue;
            if (!(living instanceof PlayerEntity) && !targetsSetting.isSelected("Мобы")) continue;

            double dist = mc.player.squaredDistanceTo(living);
            if (dist > bestDist) continue;

            Vec3d diff = living.getPos().subtract(mc.player.getPos());
            float angle = (float) Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90.0F;
            if (Math.abs(normalize(angle - camera.getYaw())) > fovSetting.getValue() / 2.0F) continue;

            bestDist = dist;
            best = living;
        }
        return best;
    }

    private static float normalize(float angle) {
        angle = angle % 360.0F;
        if (angle >= 180.0F) angle -= 360.0F;
        if (angle < -180.0F) angle += 360.0F;
        return angle;
    }
        }
