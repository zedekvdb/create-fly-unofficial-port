package probe;

import com.zurrtum.create.AllDataComponents;
import com.zurrtum.create.infrastructure.fluids.FluidStack;
import com.zurrtum.create.infrastructure.transfer.FluidInventoryStorage;
import com.zurrtum.create.infrastructure.transfer.SingleFluidStackStorage;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.material.Fluids;

public class FluidMatchingProbe implements DedicatedServerModInitializer {
    private int failures;
    private int checks;

    private static class Tank extends SingleFluidStackStorage {
        private FluidStack stack;

        Tank(int amount, int capacity) {
            stack = new FluidStack(Fluids.WATER, amount);
            stack.set(AllDataComponents.FLUID_MAX_CAPACITY, capacity);
        }

        @Override protected FluidStack getStack() { return stack; }
        @Override protected void setStack(FluidStack value) { stack = value; }
    }

    @Override
    public void onInitializeServer() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            check("nonempty transfer ignores different capacities", () -> {
                Tank source = new Tank(1000, 324_000);
                Tank target = new Tank(10, 81_000);
                try (var tx = Transaction.openOuter()) {
                    long inserted = target.insert(source.getResource(), 100, tx);
                    expect(inserted == 100, "insert amount");
                    expect(source.extract(source.getResource(), inserted, tx) == inserted, "source extraction");
                    tx.commit();
                }
                expectTank(source, 900, 324_000);
                expectTank(target, 110, 81_000);
                try (var tx = Transaction.openOuter()) {
                    long extracted = target.extract(source.getResource(), 40, tx);
                    expect(extracted == 40, "extraction with different capacity");
                    expect(source.insert(target.getResource(), extracted, tx) == extracted, "return insertion");
                    tx.commit();
                }
                expectTank(source, 940, 324_000);
                expectTank(target, 70, 81_000);
                expect(source.getAmount() + target.getAmount() == 1010, "transfer conservation");
            });
            check("plain water into capacity-only storage", () -> {
                Tank tank = new Tank(10, 81_000);
                try (var tx = Transaction.openOuter()) {
                    expect(tank.insert(FluidVariant.of(Fluids.WATER), 100, tx) == 100, "plain insertion");
                    expect(tank.extract(FluidVariant.of(Fluids.WATER), 30, tx) == 30, "plain extraction");
                    tx.commit();
                }
                expectTank(tank, 80, 81_000);
            });
            check("zero component fluids match", () -> matching(
                new FluidStack(Fluids.WATER, 1), new FluidStack(Fluids.WATER, 2), true));
            check("capacity-only and plain fluids match both ways", () -> matching(
                new Tank(1, 81_000).stack, new FluidStack(Fluids.WATER, 1), true));
            check("same real component ignores capacity both ways", () -> {
                Tank left = named(new Tank(10, 81_000), "water");
                Tank right = named(new Tank(10, 324_000), "water");
                matching(left.stack, right.stack, true);
                try (var tx = Transaction.openOuter()) {
                    expect(left.insert(right.getResource(), 5, tx) == 5, "named insertion");
                    expect(left.extract(right.getResource(), 3, tx) == 3, "named extraction");
                    tx.commit();
                }
                expectTank(left, 12, 81_000);
                expectTank(right, 10, 324_000);
                expect(Component.literal("water").equals(left.stack.get(DataComponents.CUSTOM_NAME)), "name preserved");
            });
            check("different real component values reject both ways", () -> {
                Tank left = named(new Tank(10, 81_000), "left");
                Tank right = named(new Tank(10, 324_000), "right");
                matching(left.stack, right.stack, false);
                rejected(left, right.getResource());
                rejected(right, left.getResource());
            });
            check("extra real component rejects both ways", () -> {
                Tank named = named(new Tank(10, 81_000), "water");
                Tank plain = new Tank(10, 324_000);
                matching(named.stack, plain.stack, false);
                rejected(named, plain.getResource());
                rejected(plain, named.getResource());
            });
            check("plain variant rejects named storage", () -> {
                Tank tank = named(new Tank(10, 81_000), "water");
                matching(tank.stack, new FluidStack(Fluids.WATER, 1), false);
                rejected(tank, FluidVariant.of(Fluids.WATER));
            });
            check("different fluid rejects", () -> {
                Tank tank = new Tank(10, 81_000);
                matching(tank.stack, new FluidStack(Fluids.LAVA, 1), false);
                rejected(tank, FluidVariant.of(Fluids.LAVA));
            });
            check("uncommitted insertion rolls back", () -> {
                Tank tank = new Tank(10, 81_000);
                try (var tx = Transaction.openOuter()) {
                    expect(tank.insert(new Tank(1, 324_000).getResource(), 100, tx) == 100, "simulated insertion");
                    expectTank(tank, 110, 81_000);
                }
                expectTank(tank, 10, 81_000);
            });
            check("uncommitted extraction rolls back", () -> {
                Tank tank = new Tank(100, 81_000);
                try (var tx = Transaction.openOuter()) {
                    expect(tank.extract(new Tank(1, 324_000).getResource(), 40, tx) == 40, "simulated extraction");
                    expectTank(tank, 60, 81_000);
                }
                expectTank(tank, 100, 81_000);
            });
            check("insert clamps at receiving capacity", () -> {
                Tank tank = new Tank(80_990, 81_000);
                FluidVariant resource = new Tank(1, 324_000).getResource();
                try (var tx = Transaction.openOuter()) {
                    expect(tank.insert(resource, 100, tx) == 10, "remaining capacity");
                    expect(tank.insert(resource, 1, tx) == 0, "full tank");
                    tx.commit();
                }
                expectTank(tank, 81_000, 81_000);
            });
            check("extract clamps at stored amount", () -> {
                Tank tank = new Tank(10, 81_000);
                try (var tx = Transaction.openOuter()) {
                    expect(tank.extract(new Tank(1, 324_000).getResource(), 100, tx) == 10, "available amount");
                    tx.commit();
                }
                expect(tank.getAmount() == 0 && tank.isResourceBlank(), "storage drained");
            });
            System.out.println("FLUID_MATCHING_PROBE_RESULT failures=" + failures + " checks=" + checks);
        });
    }

    private static Tank named(Tank tank, String name) {
        tank.stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return tank;
    }

    private static void matching(FluidStack left, FluidStack right, boolean expected) {
        expect(FluidInventoryStorage.matches(variant(left), right) == expected, "variant match left to right");
        expect(FluidInventoryStorage.matches(variant(right), left) == expected, "variant match right to left");
        expect(FluidStack.areFluidsAndComponentsEqualIgnoreCapacity(left, right) == expected, "stack match left to right");
        expect(FluidStack.areFluidsAndComponentsEqualIgnoreCapacity(right, left) == expected, "stack match right to left");
    }

    private static FluidVariant variant(FluidStack stack) {
        return FluidVariant.of(stack.getFluid(), stack.getComponentChanges());
    }

    private static void rejected(Tank tank, FluidVariant resource) {
        long amount = tank.getAmount();
        long capacity = tank.getCapacity();
        try (var tx = Transaction.openOuter()) {
            expect(tank.insert(resource, 1, tx) == 0, "mismatched insertion");
            expect(tank.extract(resource, 1, tx) == 0, "mismatched extraction");
            tx.commit();
        }
        expectTank(tank, amount, capacity);
    }

    private static void expectTank(Tank tank, long amount, long capacity) {
        expect(tank.getAmount() == amount, "stored amount: " + tank.getAmount() + " expected " + amount);
        expect(tank.getCapacity() == capacity, "stored capacity: " + tank.getCapacity() + " expected " + capacity);
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private void check(String name, Runnable action) {
        checks++;
        try { action.run(); }
        catch (Throwable failure) {
            failures++;
            System.out.println("FLUID_MATCHING_FAIL " + name);
            failure.printStackTrace(System.out);
        }
    }
}
