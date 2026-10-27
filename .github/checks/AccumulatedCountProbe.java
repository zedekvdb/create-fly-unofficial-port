package probe;

import com.zurrtum.create.AllBlocks;
import com.zurrtum.create.AllDisplaySources;
import com.zurrtum.create.AllDisplayTargets;
import com.zurrtum.create.content.redstone.displayLink.DisplayLinkBlockEntity;
import com.zurrtum.create.content.redstone.displayLink.DisplayLinkContext;
import com.zurrtum.create.content.redstone.displayLink.target.DisplayTargetStats;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/** Public display-source formatting contract; the scene fixture verifies transfer to real signs. */
public class AccumulatedCountProbe implements DedicatedServerModInitializer {
    private int failures;
    private int checks;

    @Override
    public void onInitializeServer() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                var link = new DisplayLinkBlockEntity(BlockPos.ZERO, AllBlocks.DISPLAY_LINK.defaultBlockState());
                link.setLevel(server.overworld());
                var config = new CompoundTag();
                link.setSourceConfig(config);
                var context = new DisplayLinkContext(server.overworld(), link);
                var stats = new DisplayTargetStats(4, 15, AllDisplayTargets.SIGN);

                expect(context, stats, "0", "missing counter");
                config.putInt("Collected", 42);
                expect(context, stats, "42", "stored 42");
                config.putInt("Collected", 0);
                expect(context, stats, "0", "stored zero");
                config.putInt("Collected", Integer.MAX_VALUE);
                expect(context, stats, "2147483647", "maximum integer");
                config.putString("Label", "Items");
                config.remove("Collected");
                expect(context, stats, "Items 0", "labeled missing counter");
                config.putInt("Collected", 42);
                expect(context, stats, "Items 42", "labeled stored counter");
                config.putString("Label", "");
                expect(context, stats, "42", "empty label adds no space");

                config.putString("Label", "Items");
                AllDisplaySources.ACCUMULATE_ITEMS.onSignalReset(context);
                check(!config.contains("Collected"), "reset removes counter");
                expect(context, stats, "Items 0", "reset renders zero");
                check(config.getStringOr("Label", "").equals("Items"), "reset preserves label");
            } catch (Throwable failure) {
                checks++;
                failures++;
                failure.printStackTrace(System.out);
            }
            System.out.println("ACCUMULATED_COUNT_PROBE_RESULT failures=" + failures + " checks=" + checks);
        });
    }

    private void expect(DisplayLinkContext context, DisplayTargetStats stats, String expected, String name) {
        var lines = AllDisplaySources.ACCUMULATE_ITEMS.provideText(context, stats);
        String actual = lines.size() == 1 ? lines.getFirst().getString() : lines.toString();
        check(lines.size() == 1 && actual.equals(expected), name + " expected=" + expected + " actual=" + actual);
    }

    private void check(boolean condition, String name) {
        checks++;
        if (condition) {
            System.out.println("ACCUMULATED_COUNT_PASS " + name);
        } else {
            failures++;
            System.out.println("ACCUMULATED_COUNT_FAIL " + name);
        }
    }
}
