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
    
    private boolean wasEnabled = false;
    private float lastYaw = 0;
    private float lastPitch = 0;

    public AuraRecorder() {
        super("AuraRecorder", "Записывает твои движения", ModuleCategory.PLAYER);
    }

    @EventHandler
    public void onTick(TickEvent event) {
        if (mc.player == null) return;

        // Проверяем, включили ли мы модуль
        if (!this.isEnabled()) {
            if (wasEnabled) {
                wasEnabled = false;
                // Когда выключаем - сбрасываем
            }
            return;
        }

        // Когда только включили
        if (!wasEnabled) {
            wasEnabled = true;
            recordedYawSteps.clear();
            recordedPitchSteps.clear();
            lastYaw = mc.player.getYaw();
            lastPitch = mc.player.getPitch();
        }

        // Запись движений
        float currentYaw = mc.player.getYaw();
        float currentPitch = mc.player.getPitch();

        float deltaYaw = MathHelper.wrapDegrees(currentYaw - lastYaw);
        float deltaPitch = MathHelper.wrapDegrees(currentPitch - lastPitch);

        // Сохраняем, если мы реально двигали мышкой
        if (Math.abs(deltaYaw) > 0.01F || Math.abs(deltaPitch) > 0.01F) {
            recordedYawSteps.add(deltaYaw);
            recordedPitchSteps.add(deltaPitch);
        }

        lastYaw = currentYaw;
        lastPitch = currentPitch;
    }
}
