package probe;

import com.mojang.authlib.GameProfile;
import com.zurrtum.create.AllBlocks;
import com.zurrtum.create.AllSynchedDatas;
import com.zurrtum.create.content.equipment.toolbox.ToolboxBlock;
import com.zurrtum.create.content.equipment.toolbox.ToolboxBlockEntity;
import com.zurrtum.create.infrastructure.player.FakePlayerEntity;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ColorCollection;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

public class ToolboxDyeProbe implements DedicatedServerModInitializer {
    private static int failures;
    private static final BlockPos POS = new BlockPos(108, 100, 104);

    @Override
    public void onInitializeServer() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ServerLevel level = server.overworld();
            for (DyeColor color : DyeColor.values()) check(level, color.getName(), color, false);
            check(level, "same color", DyeColor.BROWN, false);
            check(level, "crouching", DyeColor.RED, true);
            System.out.println("TOOLBOX_DYE_PROBE_RESULT failures=" + failures);
        });
    }

    private static void check(ServerLevel level, String name, DyeColor dyeColor, boolean crouching) {
        try {
            ColorCollection<? extends Block> toolboxes = AllBlocks.TOOLBOX;
            level.setBlockAndUpdate(POS, toolboxes.pick(DyeColor.BROWN).defaultBlockState()
                .setValue(ToolboxBlock.FACING, Direction.EAST)
                .setValue(BlockStateProperties.WATERLOGGED, true));
            ToolboxBlockEntity box = (ToolboxBlockEntity) level.getBlockEntity(POS);
            UUID id = UUID.randomUUID();
            box.setUniqueId(id);
            box.setCustomName(Component.literal("Dye probe toolbox"));
            box.inventory.setItem(0, new ItemStack(Items.IRON_INGOT, 37));
            box.inventory.setItem(19, new ItemStack(Items.DIAMOND, 5));
            ItemStack[] items = new ItemStack[box.inventory.getContainerSize()];
            ItemStack[] filters = new ItemStack[box.inventory.filters.size()];
            for (int i = 0; i < items.length; i++) items[i] = box.inventory.getItem(i).copy();
            for (int i = 0; i < filters.length; i++) filters[i] = box.inventory.filters.get(i).copy();
            expect(box.getColor() == DyeColor.BROWN, "initial color");
            FakePlayerEntity player = new FakePlayerEntity(level, new GameProfile(UUID.randomUUID(), "ToolboxDyeProbe"));
            player.setPos(POS.getX() + .5, POS.getY(), POS.getZ() + .5);
            player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 7));
            CompoundTag link = new CompoundTag();
            link.store("Pos", BlockPos.CODEC, POS);
            link.putInt("Slot", 0);
            AllSynchedDatas.TOOLBOX.get(player).put("0", link);
            box.connectPlayer(0, player, 0);
            ItemStack dye = new ItemStack(BuiltInRegistries.ITEM.getValue(
                Identifier.withDefaultNamespace(dyeColor.getName() + "_dye")), 2);
            player.setItemInHand(InteractionHand.OFF_HAND, dye);
            if (crouching) {
                player.setPose(Pose.CROUCHING);
                expect(player.isCrouching(), "crouching fixture");
            }
            InteractionResult result = level.getBlockState(POS).useItemOn(dye, level, player,
                InteractionHand.OFF_HAND, new BlockHitResult(Vec3.atCenterOf(POS), Direction.UP, POS, false));
            DyeColor expectedColor = crouching ? DyeColor.BROWN : dyeColor;
            expect(result == (crouching || dyeColor == DyeColor.BROWN
                ? InteractionResult.TRY_WITH_EMPTY_HAND : InteractionResult.SUCCESS), "interaction result");
            expect(level.getBlockState(POS).getBlock() == toolboxes.pick(expectedColor), "requested color");
            expect(level.getBlockEntity(POS) == box, "block entity identity");
            expect(box.getColor() == expectedColor, "cached color");
            expect(level.getBlockState(POS).getValue(ToolboxBlock.FACING) == Direction.EAST, "facing");
            expect(level.getBlockState(POS).getValue(BlockStateProperties.WATERLOGGED), "waterlogging");
            expect(id.equals(box.getUniqueId()), "UUID");
            expect(Component.literal("Dye probe toolbox").equals(box.getCustomName()), "name");
            for (int i = 0; i < items.length; i++) expect(ItemStack.matches(items[i], box.inventory.getItem(i)), "slot " + i);
            for (int i = 0; i < filters.length; i++) expect(ItemStack.matches(filters[i], box.inventory.filters.get(i)), "filter " + i);
            expect(link.equals(AllSynchedDatas.TOOLBOX.get(player).getCompoundOrEmpty("0")), "player link");
            expect(player.getInventory().getItem(0).getCount() == 7, "carried items");
            expect(dye.getCount() == 2, "dye count");
            box.unequipTracked();
            expect(!AllSynchedDatas.TOOLBOX.get(player).contains("0"), "connection can unequip");
            expect(player.getInventory().getItem(0).isEmpty() && box.inventory.getItem(0).getCount() == 44, "carried items returned");
            System.out.println("TOOLBOX_DYE_PROBE PASS " + name);
        } catch (Throwable failure) {
            failures++;
            System.out.println("TOOLBOX_DYE_PROBE FAIL " + name);
            failure.printStackTrace(System.out);
        } finally {
            level.setBlockAndUpdate(POS, Blocks.AIR.defaultBlockState());
        }
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
