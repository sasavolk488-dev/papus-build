package project.papus.implement.features.modules.player; // <-- ИСПРАВЛЕН ПУТЬ

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import project.papus.api.event.EventHandler;
import project.papus.api.feature.module.Module;
import project.papus.api.feature.module.ModuleCategory;
import project.papus.implement.events.player.TickEvent;

import java.util.Comparator;

public class KillAura extends Module {

    private final float RANGE = 3.0f;
    private final float CPS = 10.0f;

    private LivingEntity currentTarget;
    private long lastAttackTime = 0;

    private float silentYaw = 0;
    private float silentPitch = 0;
    private float lastSentYaw = Float.MAX_VALUE;
    private float lastSentPitch = Float.MAX_VALUE;

    public KillAura() {
        super("KillAura", "Автоатака с критами и наводкой", ModuleCategory.COMBAT);
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
        if (currentTarget == null) return;

        Vec3d eyePos = mc.player.getEyePos();
        Vec3d targetPos = currentTarget.getPos().add(0, currentTarget.getHeight() / 2.0, 0);

        float targetYaw = getTargetYaw(eyePos, targetPos);
        float targetPitch = getTargetPitch(eyePos, targetPos);

        silentYaw = smoothRotation(silentYaw, targetYaw, 45f);
        silentPitch = smoothRotation(silentPitch, targetPitch, 45f);

        if (Math.abs(silentYaw - lastSentYaw) > 1.0f || Math.abs(silentPitch - lastSentPitch) > 1.0f) {
            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(silentYaw, silentPitch, mc.player.isOnGround()));
            lastSentYaw = silentYaw;
            lastSentPitch = silentPitch;
        }

        if (mc.player.distanceTo(currentTarget) > RANGE) return;
        if (!mc.player.canSee(currentTarget)) return;
        if (!canCrit()) return;

        long currentTime = System.currentTimeMillis();
        int delay = (int) (1000 / CPS);

        if (currentTime - lastAttackTime >= delay) {
            mc.player.setYaw(silentYaw);
            mc.player.setPitch(silentPitch);

            mc.interactionManager.attackEntity(mc.player, currentTarget);
            mc.player.swingHand(Hand.MAIN_HAND);
            lastAttackTime = currentTime;
        }
    }

    private LivingEntity findTarget() {
        return mc.world.getEntitiesByClass(LivingEntity.class, mc.player.getBoundingBox().expand(RANGE), entity -> {
            if (entity == mc.player || !entity.isAlive()) return false;
            return !entity.isInvulnerable() && entity.getHealth() > 0;
        }).stream().min(Comparator.comparingDouble(mc.player::distanceTo)).orElse(null);
    }

    private boolean canCrit() {
        return mc.player.fallDistance > 0.0F 
                && !mc.player.isOnGround() 
                && !mc.player.isTouchingWater() 
                && !mc.player.isInLava() 
                && !mc.player.isClimbing() 
                && !mc.player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.BLINDNESS) 
                && !mc.player.isRiding()
                && !mc.player.isSprinting();
    }

    private float smoothRotation(float current, float target, float speed) {
        float delta = MathHelper.wrapDegrees(target - current);
        if (delta > speed) delta = speed;
        if (delta < -speed) delta = -speed;
        return current + delta;
    }

    private float getTargetYaw(Vec3d eyePos, Vec3d targetPos) {
        double x = targetPos.x - eyePos.x;
        double z = targetPos.z - eyePos.z;
        return (float) (Math.toDegrees(Math.atan2(z, x)) - 90.0F);
    }

    private float getTargetPitch(Vec3d eyePos, Vec3d targetPos) {
        double x = targetPos.x - eyePos.x;
        double y = targetPos.y - eyePos.y;
        double z = targetPos.z - eyePos.z;
        double dist = Math.sqrt(x * x + z * z);
        return (float) (-Math.toDegrees(Math.atan2(y, dist)));
    }
    }
