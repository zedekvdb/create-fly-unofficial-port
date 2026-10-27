package probe;

import com.zurrtum.create.infrastructure.fluids.FluidStack;
import com.zurrtum.create.infrastructure.transfer.FluidInventoryWrapper;
import com.zurrtum.create.infrastructure.transfer.InventoryWrapper;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.fluid.base.SingleFluidStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.item.base.SingleItemStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedSlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.List;

/** Run as a server entrypoint with Create and Fabric API; the runner owns server shutdown. */
public class SlottedCapacityProbe implements DedicatedServerModInitializer {
    private static int failures;
    private static int checks;

    @Override
    public void onInitializeServer() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            check("fluid single nonzero", () -> equal(324_000,
                FluidInventoryWrapper.of(fluids(324_000)).getMaxAmountPerStack()));
            check("fluid heterogeneous maximum last", () -> equal(324_000,
                FluidInventoryWrapper.of(fluids(0, 81_000, 162_000, 324_000)).getMaxAmountPerStack()));
            check("fluid heterogeneous maximum first", () -> equal(324_000,
                FluidInventoryWrapper.of(fluids(324_000, 81_000, 162_000, 0)).getMaxAmountPerStack()));
            check("fluid no slots", () -> equal(0,
                FluidInventoryWrapper.of(fluids()).getMaxAmountPerStack()));
            check("fluid zero capacity", () -> equal(0,
                FluidInventoryWrapper.of(fluids(0, 0)).getMaxAmountPerStack()));
            check("fluid slotted transfer", () -> fluidTransfer(fluids(0, 81_000, 324_000)));
            check("fluid Direct control", () -> fluidTransfer(
                new CombinedStorage<>(List.of(fluids(0, 81_000, 324_000)))));

            check("item single nonzero", () -> equal(64,
                InventoryWrapper.of(items(64)).getMaxStackSize()));
            check("item heterogeneous maximum last", () -> equal(64,
                InventoryWrapper.of(items(0, 16, 32, 64)).getMaxStackSize()));
            check("item heterogeneous maximum first", () -> equal(64,
                InventoryWrapper.of(items(64, 16, 32, 0)).getMaxStackSize()));
            check("item no slots", () -> equal(0,
                InventoryWrapper.of(items()).getMaxStackSize()));
            check("item zero capacity", () -> equal(0,
                InventoryWrapper.of(items(0, 0)).getMaxStackSize()));
            check("item slotted transfer", () -> itemTransfer(items(0, 16, 64)));
            check("item Direct control", () -> itemTransfer(
                new CombinedStorage<>(List.of(items(0, 16, 64)))));
            System.out.println("SLOTTED_CAPACITY_PROBE_RESULT failures=" + failures + " checks=" + checks);
        });
    }

    private static CombinedSlottedStorage<FluidVariant, SingleFluidStorage> fluids(int... capacities) {
        List<SingleFluidStorage> slots = new ArrayList<>();
        for (int capacity : capacities) {
            slots.add(SingleFluidStorage.withFixedCapacity(capacity, () -> {}));
        }
        return new CombinedSlottedStorage<>(slots);
    }

    private static CombinedSlottedStorage<ItemVariant, SingleItemStorage> items(int... capacities) {
        List<SingleItemStorage> slots = new ArrayList<>();
        for (int capacity : capacities) {
            slots.add(new SingleItemStorage() {
                @Override
                protected long getCapacity(ItemVariant variant) {
                    return capacity;
                }
            });
        }
        return new CombinedSlottedStorage<>(slots);
    }

    private static void fluidTransfer(Storage<FluidVariant> storage) {
        var inventory = FluidInventoryWrapper.of(storage);
        equal(0, amount(storage));
        equal(324_000, inventory.getMaxAmountPerStack());
        // Consumers size transfers from the wrapper's advertised capacity.
        FluidStack request = new FluidStack(Fluids.WATER, inventory.getMaxAmountPerStack());
        equal(324_000, inventory.countSpace(request));
        equal(0, amount(storage));
        equal(324_000, inventory.insert(request));
        equal(324_000, amount(storage));
        equal(324_000, inventory.count(request));
        equal(81_000, inventory.extract(new FluidStack(Fluids.WATER, 81_000)));
        equal(243_000, amount(storage));
        equal(243_000, inventory.extract(request));
        equal(0, amount(storage));
        equal(324_000, inventory.getMaxAmountPerStack());
    }

    private static void itemTransfer(Storage<ItemVariant> storage) {
        var inventory = (InventoryWrapper<?, ?>) InventoryWrapper.of(storage);
        equal(0, amount(storage));
        equal(64, inventory.getMaxStackSize());
        ItemStack request = new ItemStack(Items.STONE, inventory.getMaxStackSize());
        equal(64, inventory.countSpace(request));
        equal(0, amount(storage));
        equal(64, inventory.insert(request));
        equal(64, amount(storage));
        equal(64, inventory.count(request));
        equal(16, inventory.extract(new ItemStack(Items.STONE, 16)));
        equal(48, amount(storage));
        equal(48, inventory.extract(request));
        equal(0, amount(storage));
        equal(64, inventory.getMaxStackSize());
    }

    private static long amount(Storage<?> storage) {
        long total = 0;
        for (var view : storage) {
            total += view.getAmount();
        }
        return total;
    }

    private static void equal(long expected, long actual) {
        if (expected != actual) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }

    private static void check(String name, Runnable action) {
        checks++;
        try {
            action.run();
            System.out.println("SLOTTED_CAPACITY_PASS " + name);
        } catch (Throwable failure) {
            failures++;
            System.out.println("SLOTTED_CAPACITY_FAIL " + name);
            failure.printStackTrace(System.out);
        }
    }
}
