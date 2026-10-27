package probe;

import com.zurrtum.create.AllContraptionTypes;
import com.zurrtum.create.api.behaviour.movement.MovementBehaviour;
import com.zurrtum.create.api.contraption.ContraptionType;
import com.zurrtum.create.content.contraptions.Contraption;
import com.zurrtum.create.content.contraptions.MountedStorageManager;
import com.zurrtum.create.content.contraptions.behaviour.MovementContext;
import com.zurrtum.create.infrastructure.config.AllConfigs;
import com.zurrtum.create.infrastructure.items.CombinedInvWrapper;
import com.zurrtum.create.infrastructure.items.ItemStackHandler;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Checks the shared actor collection path against real spawned item entities. */
public class ActorDropCountProbe implements DedicatedServerModInitializer {
    private static final int[] INITIAL_COUNTS = {62, 63, 64};
    private final ItemStackHandler[] inventories = new ItemStackHandler[3];
    private int ticks;
    private int failures;
    private int startedAt = -1;
    private boolean done;

    @Override
    public void onInitializeServer() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> server.overworld().setChunkForced(5, 5, true));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            ticks++;
            if (!done && startedAt < 0 && server.overworld().isPositionEntityTicking(new BlockPos(81, 100, 81))) {
                startedAt = ticks;
                for (int i = 0; i < INITIAL_COUNTS.length; i++) {
                    int scenario = i;
                    check("setup " + i, () -> collect(server.overworld(), scenario));
                }
            }
            if (!done && startedAt >= 0 && ticks >= startedAt + 5) {
                done = true;
                for (int i = 0; i < INITIAL_COUNTS.length; i++) {
                    int scenario = i;
                    check("capacity " + (64 - INITIAL_COUNTS[i]), () -> verify(server.overworld(), scenario));
                }
                System.out.println("ACTOR_DROP_COUNT_PROBE_RESULT failures=" + failures);
            }
            if (!done && ticks >= 600) {
                done = true;
                System.out.println("ACTOR_DROP_COUNT_FAIL chunk did not become entity-ticking");
                System.out.println("ACTOR_DROP_COUNT_PROBE_RESULT failures=" + (++failures));
            }
        });
    }

    private void collect(ServerLevel level, int scenario) {
        if (!AllConfigs.server().kinetics.moveItemsToStorage.get())
            throw new AssertionError("moveItemsToStorage must be enabled");
        ItemStackHandler inventory = new ItemStackHandler(1);
        inventory.setItem(0, new ItemStack(Items.COBBLESTONE, INITIAL_COUNTS[scenario]));
        inventories[scenario] = inventory;
        CombinedInvWrapper combined = new CombinedInvWrapper(inventory);
        MountedStorageManager probeStorage = new MountedStorageManager() {
            @Override public CombinedInvWrapper getAllItems() { return combined; }
        };
        Contraption contraption = new Contraption() {
            @Override public boolean assemble(Level world, BlockPos pos) { return false; }
            @Override public boolean canBeStabilized(Direction facing, BlockPos pos) { return false; }
            @Override public ContraptionType getType() { return AllContraptionTypes.MOUNTED; }
            @Override public MountedStorageManager getStorage() { return probeStorage; }
        };
        MovementContext context = new MovementContext(level,
            new StructureBlockInfo(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), null), contraption);
        context.position = new Vec3(81.5 + scenario * 4, 100, 81.5);
        level.getChunkAt(BlockPos.containing(context.position));
        level.setBlockAndUpdate(BlockPos.containing(context.position).below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(BlockPos.containing(context.position), Blocks.AIR.defaultBlockState());
        level.getEntitiesOfClass(ItemEntity.class, area(scenario)).forEach(ItemEntity::discard);
        new MovementBehaviour() {}.collectOrDropItem(context, new ItemStack(Items.COBBLESTONE, 2));
    }

    private void verify(ServerLevel level, int scenario) {
        int stored = inventories[scenario].getItem(0).getCount() - INITIAL_COUNTS[scenario];
        int dropped = level.getEntitiesOfClass(ItemEntity.class, area(scenario)).stream()
            .filter(entity -> entity.getItem().is(Items.COBBLESTONE))
            .mapToInt(entity -> entity.getItem().getCount()).sum();
        int expectedStored = 64 - INITIAL_COUNTS[scenario];
        System.out.println("ACTOR_DROP_COUNT_OBS capacity=" + expectedStored + " stored=" + stored + " dropped=" + dropped);
        if (stored != expectedStored || dropped != 2 - expectedStored || stored + dropped != 2)
            throw new AssertionError("expected conservation of two actor drops; stored=" + stored + ", dropped=" + dropped);
        level.getEntitiesOfClass(ItemEntity.class, area(scenario)).forEach(ItemEntity::discard);
    }

    private static AABB area(int scenario) {
        return new AABB(80 + scenario * 4, -64, 80, 83 + scenario * 4, 320, 83);
    }

    private void check(String name, Runnable action) {
        try { action.run(); }
        catch (Throwable failure) {
            failures++;
            System.out.println("ACTOR_DROP_COUNT_FAIL " + name);
            failure.printStackTrace(System.out);
        }
    }
}

