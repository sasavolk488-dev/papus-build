package project.papus.implement.features.modules.player;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.math.MathHelper;
import project.papus.api.event.EventHandler;
import project.papus.api.feature.module.Module;
import project.papus.api.feature.module.ModuleCategory;
import project.papus.implement.events.player.TickEvent;

public class AuraRecorder extends Module {

    // Память (наши записанные данные)
    public static List<Float> recordedYawSteps = new ArrayList<>();
    public static List<Float> recordedPitchSteps = new ArrayList<>();
    public static List<Integer> recordedAttackTicks = new ArrayList<>();

    private boolean isRecording = false;
    private float lastYaw = 0;
    private float lastPitch = 0;
    private int tickCounter = 0;
    private int ticksSinceLastAttack = 0;

    public AuraRecorder() {
        super("AuraRecorder", "Записывает твои движения", ModuleCategory.PLAYER);
    }

    @Override
    public void onEnable() {
        recordedYawSteps.clear();
        recordedPitchSteps.clear();
        recordedAttackTicks.clear();
        tickCounter = 0;
        ticksSinceLastAttack = 0;
        isRecording = true;
        
        if (mc.player != null) {
            lastYaw = mc.player.getYaw();
            lastPitch = mc.player.getPitch();
        }
        
        super.onEnable();
    }

    @Override
    public void onDisable() {
        isRecording = false;
        super.onDisable();
    }

    @EventHandler
    public void onTick(TickEvent event) {
        if (!isRecording || mc.player == null) return;

        // Считаем разницу между текущим углом и прошлым
        float currentYaw = mc.player.getYaw();
        float currentPitch = mc.player.getPitch();

        float deltaYaw = MathHelper.wrapDegrees(currentYaw - lastYaw);
        float deltaPitch = MathHelper.wrapDegrees(currentPitch - lastPitch);

        // Сохраняем в память (только если мы реально двигали мышкой)
        if (Math.abs(deltaYaw) > 0.01F || Math.abs(deltaPitch) > 0.01F) {
            recordedYawSteps.add(deltaYaw);
            recordedPitchSteps.add(deltaPitch);
            
            // Запоминаем, через сколько тиков после движения был удар
            recordedAttackTicks.add(ticksSinceLastAttack);
            ticksSinceLastAttack = 0;
        } else {
            ticksSinceLastAttack++;
        }

        lastYaw = currentYaw;
        lastPitch = currentPitch;
    }
          }
