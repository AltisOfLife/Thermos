package org.bukkit.craftbukkit.util;

import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.craftbukkit.block.CraftBlockState;

import java.util.ArrayList;
import java.util.List;

public class BlockStateListPopulator {
    private final World world;
    private final List<BlockState> list;

    public BlockStateListPopulator(World world) {
        this(world, new ArrayList<BlockState>());
    }

    public BlockStateListPopulator(World world, List<BlockState> list) {
        this.world = world;
        this.list = list;
    }

    public void setTypeAndData(int x, int y, int z, net.minecraft.block.Block block, int data, int light) {
        CraftBlockState state = (CraftBlockState)world.getBlockAt(x, y, z).getState();
        state.setTypeId(net.minecraft.block.Block.getIdFromBlock(block));
        state.setRawData((byte) data);
        list.add(state);
    }
    public void setTypeId(int x, int y, int z, int type) {
        BlockState state = world.getBlockAt(x, y, z).getState();
        state.setTypeId(type);
        list.add(state);
    }

    public void setTypeUpdate(int x, int y, int z, net.minecraft.block.Block block) {
        this.setType(x, y, z, block);
    }

    public void setType(int x, int y, int z, net.minecraft.block.Block block) {
        BlockState state = world.getBlockAt(x, y, z).getState();
        state.setTypeId(net.minecraft.block.Block.getIdFromBlock(block));
        list.add(state);
    }

    public void updateList() {
        for (BlockState state : list) {
            state.update(true);
        }
    }

    public List<BlockState> getList() {
        return list;
    }

    public World getWorld() {
        return world;
    }
}
