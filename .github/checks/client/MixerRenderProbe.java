package probe;

import com.zurrtum.create.AllBlocks;
import com.zurrtum.create.client.catnip.animation.AnimationTickHolder;
import com.zurrtum.create.client.content.kinetics.base.KineticBlockEntityRenderer;
import com.zurrtum.create.client.content.kinetics.mixer.MechanicalMixerRenderer;
import com.zurrtum.create.client.content.kinetics.mixer.MechanicalMixerRenderer.MechanicalMixerRenderState;
import com.zurrtum.create.client.foundation.blockEntity.behaviour.animation.AnimationBehaviour;
import com.zurrtum.create.client.foundation.blockEntity.behaviour.animation.MechanicalMixerAnimationBehaviour;
import com.zurrtum.create.content.kinetics.mixer.MechanicalMixerBlockEntity;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import org.joml.Quaternionf;

/** Real client-level blocks and registered fallback renderer, before their first block-entity tick. */
public class MixerRenderProbe implements ClientModInitializer {
    private int ticks;
    private int checks;
    private int failures;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level != null && client.player != null && client.gui.screen() == null && ++ticks == 100) {
                run(client);
            }
        });
    }

    private void run(Minecraft client) {
        System.out.println("MIXER_RENDER_CLASS_SOURCE " + MechanicalMixerRenderer.class.getProtectionDomain().getCodeSource().getLocation());
        BlockPos first = client.player.blockPosition().offset(2, 3, 0);
        BlockPos second = first.east();
        boolean ownsPositions = false;
        try {
            if (!client.level.isEmptyBlock(first) || !client.level.isEmptyBlock(second)) {
                throw new AssertionError("fixture requires two adjacent air blocks at " + first);
            }
            ownsPositions = true;
            var fresh = place(client, first);
            var control = place(client, second);
            Object registered = client.getBlockEntityRenderDispatcher().getRenderer(fresh);
            if (!(registered instanceof MechanicalMixerRenderer renderer)) {
                throw new AssertionError("unexpected registered renderer: " + registered);
            }
            check("registered renderer and pre-first-tick phase", () -> {
                require(fresh.getLevel() == client.level, "real client level");
                require(fresh.getBehaviour(AnimationBehaviour.TYPE) == null, "behaviour must not yet be initialized");
                System.out.println("MIXER_RENDER_LIFECYCLE renderer=" + renderer.getClass().getName()
                    + " behaviourBeforeFirstTick=null position=" + first);
            });
            check("fresh idle extraction", () -> extract(client, renderer, fresh, null, -0.4375f));
            fresh.running = true;
            fresh.runningTicks = 20;
            fresh.setSpeed(32);
            check("fresh running extraction", () -> extract(client, renderer, fresh,
                expectedAngle(client, fresh), -1.4375f));
            check("initialize installs client animation behaviour", () -> {
                control.initialize();
                require(control.getBehaviour(AnimationBehaviour.TYPE) instanceof MechanicalMixerAnimationBehaviour,
                    "registered client animation behaviour");
            });
            check("initialized idle extraction", () -> extract(client, renderer, control, null, -0.4375f));
            control.running = true;
            control.runningTicks = 20;
            control.setSpeed(32);
            check("initialized positive-speed extraction", () -> extract(client, renderer, control,
                expectedAngle(client, control), -1.4375f));
            control.setSpeed(-32);
            check("initialized negative-speed extraction", () -> extract(client, renderer, control,
                expectedAngle(client, control), -1.4375f));
            check("reused state clears prior head angle", () -> {
                var state = renderer.createRenderState();
                renderer.extractRenderState(control, state, 0.5f, client.player.position(), null);
                require(state.headAngle != null, "control must prime a nonzero rotation");
                fresh.running = false;
                fresh.setSpeed(0);
                renderer.extractRenderState(fresh, state, 0.5f, client.player.position(), null);
                validate(state, null, -0.4375f);
            });
        } catch (Throwable failure) {
            failures++;
            checks++;
            failure.printStackTrace(System.out);
        } finally {
            if (ownsPositions && client.level.getBlockState(first).is(AllBlocks.MECHANICAL_MIXER)) {
                client.level.setBlock(first, Blocks.AIR.defaultBlockState(), 3);
            }
            if (ownsPositions && client.level.getBlockState(second).is(AllBlocks.MECHANICAL_MIXER)) {
                client.level.setBlock(second, Blocks.AIR.defaultBlockState(), 3);
            }
            System.out.println("MIXER_RENDER_PROBE_RESULT failures=" + failures + " checks=" + checks);
        }
    }

    private static MechanicalMixerBlockEntity place(Minecraft client, BlockPos pos) {
        client.level.setBlock(pos, AllBlocks.MECHANICAL_MIXER.defaultBlockState(), 3);
        return (MechanicalMixerBlockEntity) client.level.getBlockEntity(pos);
    }

    private static Quaternionf expectedAngle(Minecraft client, MechanicalMixerBlockEntity mixer) {
        var behaviour = (MechanicalMixerAnimationBehaviour) mixer.getBehaviour(AnimationBehaviour.TYPE);
        float progress = KineticBlockEntityRenderer.getProgress(mixer.getSpeed(),
            AnimationTickHolder.getRenderTime(client.level));
        return KineticBlockEntityRenderer.getRotateAngle(
            progress, behaviour == null ? 0 : behaviour.getOffset(mixer.getSpeed(), 0.5f), Direction.UP);
    }

    private static void extract(Minecraft client, MechanicalMixerRenderer renderer, MechanicalMixerBlockEntity mixer,
                                Quaternionf expectedAngle, float expectedOffset) {
        var state = renderer.createRenderState();
        renderer.extractRenderState(mixer, state, 0.5f, client.player.position(), null);
        validate(state, expectedAngle, expectedOffset);
    }

    private static void validate(MechanicalMixerRenderState state, Quaternionf expectedAngle, float expectedOffset) {
        require(state.cogwheel != null && state.pole != null && state.head != null, "complete model states");
        require(Math.abs(state.headOffset - expectedOffset) < 0.00001f, "head offset=" + state.headOffset);
        require(expectedAngle == null ? state.headAngle == null
            : state.headAngle != null && state.headAngle.equals(expectedAngle, 0.00001f),
            "head angle=" + state.headAngle + " expected=" + expectedAngle);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private void check(String name, Runnable action) {
        checks++;
        try {
            action.run();
            System.out.println("MIXER_RENDER_PASS " + name);
        } catch (Throwable failure) {
            failures++;
            System.out.println("MIXER_RENDER_FAIL " + name);
            failure.printStackTrace(System.out);
        }
    }
}
