package project.papus.implement.features.modules.player;

import java.util.Random;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffects;
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
    private final ValueSetting fovSetting;
    private final ValueSetting speedXSetting;
    private final ValueSetting speedYSetting;
    private final ValueSetting aimHeightSetting;
    private final ValueSetting smoothSetting;
    
    private final BooleanSetting weaponOnlySetting;
    private final BooleanSetting sprintResetSetting; 
    private final BooleanSetting critOnlySetting;    
    private final BooleanSetting smartCritsSetting;  
    private final BooleanSetting use19Setting; // НОВОЕ: Атака 1.9
    
    private final MultiSelectSetting targetsSetting;

    private final Random random = new Random();

    private long lastAttackTime = 0;
    private long nextAttackDelay = 0;
    private LivingEntity currentTarget = null;
    
    private float currentHeight = 0.5F;

    public LegitKillAura() {
        super("LegitKillAura", "Плавная аура + Триггер", ModuleCategory.PLAYER);

        this.rangeSetting = new ValueSetting("Дистанция", "").setValue(3.0F).range(1.0F, 3.0F);
        this.fovSetting = new ValueSetting("FOV", "").setValue(360F).range(10F, 360F);
        
        this.speedXSetting = new ValueSetting("Скорость X", "").setValue(8.0F).range(0.0F, 20.0F);
        this.speedYSetting = new ValueSetting("Скорость Y", "").setValue(6.0F).range(0.0F, 20.0F);
        this.aimHeightSetting = new ValueSetting("Часть тела", "").setValue(0.8F).range(0.0F, 1.0F);
        this.smoothSetting = new ValueSetting("Плавность", "").setValue(0.6F).range(0.1F, 1.0F);
        
        this.weaponOnlySetting = new BooleanSetting("Только с оружием", "").setValue(true);
        this.sprintResetSetting = new BooleanSetting("Сброс спринта", "").setValue(true);
        
        this.critOnlySetting = new BooleanSetting("Только криты", "Бьет только при падении").setValue(false);
        this.smartCritsSetting = new BooleanSetting("Умные криты", "Бьет и критами, и обычными").setValue(true);
        
        // НОВОЕ: Режим 1.9
        this.use19Setting = new BooleanSetting("Атака 1.9", "Ждет кулдаун удара").setValue(true);

        this.targetsSetting = new MultiSelectSetting("Цели", "").value("Игроки", "Мобы");
        this.targetsSetting.getSelected().add("Игроки");

        this.setup(
            this.rangeSetting, this.fovSetting, this.speedXSetting, this.speedYSetting,
            this.aimHeightSetting, this.smoothSetting, this.weaponOnlySetting,
            this.sprintResetSetting, this.critOnlySetting, this.smartCritsSetting, 
            this.use19Setting, this.targetsSetting
        );
    }

    @EventHandler
    public void onTick(TickEvent event) {
        if (mc.player == null || mc.world == null) return;
        if (!isWeaponInHand()) return;

        currentTarget = findTarget();
        if (currentTarget == null) return;

        boolean isAimingAtTarget = mc.crosshairTarget instanceof EntityHitResult hitResult 
                && hitResult.getEntity() == currentTarget;

        if (!isAimingAtTarget) {
            currentTarget = null;
            return;
        }

        if (critOnlySetting.isValue() && !canCrit()) return;

        long now = System.currentTimeMillis();
        
        // === ЛОГИКА АТАКИ ===
        if (use19Setting.isValue()) {
            // 1.9 режим: проверяем шкалу перезарядки
            float cooldown = mc.player.getAttackCooldownProgress(0.5F);
            
            // Ждем заполнения шкалы (0.95 = 95% для надежности)
            // Grim ловит удары, если шкала заполнена меньше чем на 90%
            if (cooldown < 0.95F) return;
            
            // Небольшая пауза, чтобы пакеты не улетали в один тик
            if (now - lastAttackTime < 50) return; 
        } else {
            // 1.8 режим: рандомная задержка (как раньше)
            if (now < lastAttackTime + nextAttackDelay) return;
        }

        // Нанесение удара
        boolean wasSprinting = mc.player.isSprinting();
        if (sprintResetSetting.isValue() && wasSprinting) {
            mc.player.setSprinting(false);
        }

        attack(currentTarget);
        
        if (sprintResetSetting.isValue() && wasSprinting) {
            mc.player.setSprinting(true);
        }

        lastAttackTime = now;
        
        if (!use19Setting.isValue()) {
            calculateHumanDelay(); // Обновляем задержку только для 1.8
        }
    }

    @EventHandler
    public void onMouseRotation(MouseRotationEvent event) {
        if (mc.player == null || mc.world == null) return;
        if (!isWeaponInHand()) return;

        if (currentTarget == null || !currentTarget.isAlive()) {
            LivingEntity targetForAim = findTarget();
            if (targetForAim != null) smoothAim(event, targetForAim);
            return;
        }
        smoothAim(event, currentTarget);
    }

    private boolean isWeaponInHand() {
        if (!weaponOnlySetting.isValue()) return true;
        ItemStack stack = mc.player.getMainHandStack();
        return !stack.isEmpty() && (stack.getItem() instanceof SwordItem || stack.getItem() instanceof MaceItem);
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

        yawStep = MathHelper.clamp(yawStep, -100.0F, 100.0F);
        pitchStep = MathHelper.clamp(pitchStep, -100.0F, 100.0F);

        event.setCursorDeltaX(event.getCursorDeltaX() + yawStep);
        event.setCursorDeltaY(event.getCursorDeltaY() + pitchStep);
    }

    private void attack(LivingEntity target) {
        mc.getNetworkHandler().sendPacket(PlayerInteractEntityC2SPacket.attack(target, mc.player.isSneaking()));
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private void calculateHumanDelay() {
        long delay = 110;
        delay += random.nextInt(40) - 20; 
        if (random.nextInt(100) < 10) delay += 80 + random.nextInt(70);
        this.nextAttackDelay = Math.max(70, Math.min(200, delay));
    }

    private boolean canCrit() {
        return !mc.player.isOnGround() && !mc.player.isTouchingWater() && !mc.player.isClimbing() 
                && mc.player.getVehicle() == null && !mc.player.hasStatusEffect(StatusEffects.BLINDNESS);
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
