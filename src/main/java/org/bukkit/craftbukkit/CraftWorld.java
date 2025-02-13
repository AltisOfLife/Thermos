package org.bukkit.craftbukkit;

import cpw.mods.fml.common.registry.EntityRegistry;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.IEntityLivingData;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.BlockSnapshot;
import org.apache.commons.lang.Validate;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.entity.CraftItem;
import org.bukkit.craftbukkit.entity.CraftLightningStrike;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.metadata.BlockMetadataStore;
import org.bukkit.craftbukkit.util.LongHash;
import org.bukkit.entity.*;
import org.bukkit.entity.minecart.PoweredMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.entity.minecart.*;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.world.SpawnChangeEvent;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.StandardMessenger;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.*;

public class CraftWorld implements World {
    //public static final int CUSTOM_DIMENSION_OFFSET = 10; // Cauldron - disabled

    private final net.minecraft.world.WorldServer world;
    private Environment environment;
    private final CraftServer server = (CraftServer) Bukkit.getServer();
    private ChunkGenerator generator; // Cauldron - remove final to workaround TC bug
    private final List<BlockPopulator> populators = new ArrayList<BlockPopulator>();
    private final BlockMetadataStore blockMetadata = new BlockMetadataStore(this);
    private int monsterSpawn = -1;
    private int animalSpawn = -1;
    private int waterAnimalSpawn = -1;
    private int ambientSpawn = -1;
    private int chunkLoadCount = 0;
    private int chunkGCTickCount;

    private static final Random rand = new Random();

    public CraftWorld(net.minecraft.world.WorldServer world, ChunkGenerator gen, Environment env) {
        this.world = world;
        this.generator = gen;

        environment = env;

        if (server.chunkGCPeriod > 0) {
            chunkGCTickCount = rand.nextInt(server.chunkGCPeriod);
        }
    }

    public Block getBlockAt(int x, int y, int z) {
        Chunk chunk = getChunkAt(x >> 4, z >> 4);
        return chunk == null ? null : chunk.getBlock(x & 0xF, y & 0xFF, z & 0xF);
    }

    public int getBlockTypeIdAt(int x, int y, int z) {
        return world.getTypeId(x, y, z);
    }

    public int getHighestBlockYAt(int x, int z) {
        if (!isChunkLoaded(x >> 4, z >> 4)) {
            loadChunk(x >> 4, z >> 4);
        }

        return world.getHeightValue(x, z);
    }

    public Location getSpawnLocation() {
        net.minecraft.util.ChunkCoordinates spawn = world.getSpawnPoint();
        return new Location(this, spawn.posX, spawn.posY, spawn.posZ);
    }

    public boolean setSpawnLocation(int x, int y, int z) {
        try {
            Location previousLocation = getSpawnLocation();
            world.worldInfo.setSpawnPosition(x, y, z);

            // Notify anyone who's listening.
            SpawnChangeEvent event = new SpawnChangeEvent(this, previousLocation);
            server.getPluginManager().callEvent(event);

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public Chunk getChunkAt(int x, int z) {
        net.minecraft.world.chunk.Chunk chunk = this.world.theChunkProviderServer.loadChunk(x, z);
        return chunk == null ? null : chunk.bukkitChunk;
    }

    public Chunk getChunkAt(Block block) {
        return getChunkAt(block.getX() >> 4, block.getZ() >> 4);
    }

    public boolean isChunkLoaded(int x, int z) {
        return world.theChunkProviderServer.chunkExists(x, z);
    }

    public Chunk[] getLoadedChunks() {
        Object[] chunks = world.theChunkProviderServer.loadedChunkHashMap_KC.rawVanilla().values().toArray();
        org.bukkit.Chunk[] craftChunks = new CraftChunk[chunks.length];

        for (int i = 0; i < chunks.length; i++) {
            net.minecraft.world.chunk.Chunk chunk = (net.minecraft.world.chunk.Chunk) chunks[i];
            craftChunks[i] = chunk.bukkitChunk;
        }

        return craftChunks;
    }

    public void loadChunk(int x, int z) {
        loadChunk(x, z, true);
    }

    public boolean unloadChunk(Chunk chunk) {
        return unloadChunk(chunk.getX(), chunk.getZ());
    }

    public boolean unloadChunk(int x, int z) {
        return unloadChunk(x, z, true);
    }

    public boolean unloadChunk(int x, int z, boolean save) {
        return unloadChunk(x, z, save, false);
    }

    public boolean unloadChunkRequest(int x, int z) {
        return unloadChunkRequest(x, z, true);
    }

    public boolean unloadChunkRequest(int x, int z, boolean safe) {
        // Cauldron start - use same logic as processChunkGC
        // If in use, skip it
        if (isChunkInUse(x, z)) {
            return false;
        }

        // Already unloading?
        if (world.theChunkProviderServer.chunksToUnload.contains(x, z)) {
            return true;
        }
        // Cauldron end

        world.theChunkProviderServer.unloadChunksIfNotNearSpawn(x, z);

        return true;
    }

    public boolean unloadChunk(int x, int z, boolean save, boolean safe) {
        // Cauldron start - queue chunk for unload, fixes startup issues with IC2
        // If in use, skip it
        if (isChunkInUse(x, z)) {
            return false;
        }

        // Already unloading?
        if (world.theChunkProviderServer.chunksToUnload.contains(x, z)) {
            return true;
        }

        world.theChunkProviderServer.unloadChunksIfNotNearSpawn(x, z);
        // Cauldron end

        return true;
    }

    public boolean regenerateChunk(int x, int z) {
        unloadChunk(x, z, false, false);

        //world.theChunkProviderServer.chunksToUnload.remove(x, z); // Cauldron - this is handled in unloadChunksIfNotNearSpawn

        net.minecraft.world.chunk.Chunk chunk = null;

        if (world.theChunkProviderServer.currentChunkProvider == null) {
            chunk = world.theChunkProviderServer.defaultEmptyChunk;
        } else {
            chunk = world.theChunkProviderServer.currentChunkProvider.provideChunk(x, z);
        }

        chunkLoadPostProcess(chunk, x, z);

        refreshChunk(x, z);

        return chunk != null;
    }

    public boolean refreshChunk(int x, int z) {
        if (!isChunkLoaded(x, z)) {
            return false;
        }

        int px = x << 4;
        int pz = z << 4;

        // If there are more than 64 updates to a chunk at once, it will update all 'touched' sections within the chunk
        // And will include biome data if all sections have been 'touched'
        // This flags 65 blocks distributed across all the sections of the chunk, so that everything is sent, including biomes
        int height = getMaxHeight() / 16;
        for (int idx = 0; idx < 64; idx++) {
            world.markBlockForUpdate(px + (idx / height), ((idx % height) * 16), pz);
        }
        world.markBlockForUpdate(px + 15, (height * 16) - 1, pz + 15);

        return true;
    }

    public boolean isChunkInUse(int x, int z) {
        // Cauldron start
        if (world.getPlayerManager().isChunkInUse(x, z) || world.isActiveChunk(x, z)) {
            return true;
        }
        return false;
        // Cauldron end
    }

    public boolean loadChunk(int x, int z, boolean generate) {
        chunkLoadCount++;
        if (generate) {
            // Use the default variant of loadChunk when generate == true.
            return world.theChunkProviderServer.loadChunk(x, z) != null;
        }

        world.theChunkProviderServer.chunksToUnload.remove(x, z);
        //net.minecraft.world.chunk.Chunk chunk = world.theChunkProviderServer.loadedChunkHashMap_TH.get(LongHash.toLong(x, z));
        net.minecraft.world.chunk.Chunk chunk = world.theChunkProviderServer.loadedChunkHashMap_KC.rawThermos().get(x,z); //Thermos replacement for line above

        if (chunk == null) {
            world.timings.syncChunkLoadTimer.startTiming(); // Spigot
            chunk = world.theChunkProviderServer.safeLoadChunk(x, z);

            chunkLoadPostProcess(chunk, x, z);
            world.timings.syncChunkLoadTimer.stopTiming(); // Spigot
        }
        return chunk != null;
    }

    private void chunkLoadPostProcess(net.minecraft.world.chunk.Chunk chunk, int x, int z) {
        if (chunk != null) {
            world.theChunkProviderServer.loadedChunkHashMap_KC.add(LongHash.toLong(x, z), chunk); // Passes to chunkt_TH
            world.theChunkProviderServer.loadedChunks.add(chunk); // Cauldron - vanilla compatibility
            chunk.onChunkLoad();

            if (!chunk.isTerrainPopulated && world.theChunkProviderServer.chunkExists(x + 1, z + 1) && world.theChunkProviderServer.chunkExists(x, z + 1) && world.theChunkProviderServer.chunkExists(x + 1, z)) {
                world.theChunkProviderServer.populate(world.theChunkProviderServer, x, z);
            }

            if (world.theChunkProviderServer.chunkExists(x - 1, z) && !world.theChunkProviderServer.provideChunk(x - 1, z).isTerrainPopulated && world.theChunkProviderServer.chunkExists(x - 1, z + 1) && world.theChunkProviderServer.chunkExists(x, z + 1) && world.theChunkProviderServer.chunkExists(x - 1, z)) {
                world.theChunkProviderServer.populate(world.theChunkProviderServer, x - 1, z);
            }

            if (world.theChunkProviderServer.chunkExists(x, z - 1) && !world.theChunkProviderServer.provideChunk(x, z - 1).isTerrainPopulated && world.theChunkProviderServer.chunkExists(x + 1, z - 1) && world.theChunkProviderServer.chunkExists(x, z - 1) && world.theChunkProviderServer.chunkExists(x + 1, z)) {
                world.theChunkProviderServer.populate(world.theChunkProviderServer, x, z - 1);
            }

            if (world.theChunkProviderServer.chunkExists(x - 1, z - 1) && !world.theChunkProviderServer.provideChunk(x - 1, z - 1).isTerrainPopulated && world.theChunkProviderServer.chunkExists(x - 1, z - 1) && world.theChunkProviderServer.chunkExists(x, z - 1) && world.theChunkProviderServer.chunkExists(x - 1, z)) {
                world.theChunkProviderServer.populate(world.theChunkProviderServer, x - 1, z - 1);
            }
        }
    }

    public boolean isChunkLoaded(Chunk chunk) {
        return isChunkLoaded(chunk.getX(), chunk.getZ());
    }

    public void loadChunk(Chunk chunk) {
        loadChunk(chunk.getX(), chunk.getZ());
        ((CraftChunk) getChunkAt(chunk.getX(), chunk.getZ())).getHandle().bukkitChunk = chunk;
    }

    public net.minecraft.world.WorldServer getHandle() {
        return world;
    }

    public org.bukkit.entity.Item dropItem(Location loc, ItemStack item) {
        Validate.notNull(item, "Cannot drop a Null item.");
        Validate.isTrue(item.getTypeId() != 0, "Cannot drop AIR.");
        net.minecraft.entity.item.EntityItem entity = new net.minecraft.entity.item.EntityItem(world, loc.getX(), loc.getY(), loc.getZ(), CraftItemStack.asNMSCopy(item));
        entity.delayBeforeCanPickup = 10;
        world.spawnEntityInWorld(entity);
        // TODO this is inconsistent with how Entity.getBukkitEntity() works.
        // However, this entity is not at the moment backed by a server entity class so it may be left.
        return new CraftItem(world.getServer(), entity);
    }

    public org.bukkit.entity.Item dropItemNaturally(Location loc, ItemStack item) {
        double xs = world.rand.nextFloat() * 0.7F + (1.0F - 0.7F) * 0.5D;
        double ys = world.rand.nextFloat() * 0.7F + (1.0F - 0.7F) * 0.5D;
        double zs = world.rand.nextFloat() * 0.7F + (1.0F - 0.7F) * 0.5D;
        loc = loc.clone();
        loc.setX(loc.getX() + xs);
        loc.setY(loc.getY() + ys);
        loc.setZ(loc.getZ() + zs);
        return dropItem(loc, item);
    }

    public Arrow spawnArrow(Location loc, Vector velocity, float speed, float spread) {
        Validate.notNull(loc, "Can not spawn arrow with a null location");
        Validate.notNull(velocity, "Can not spawn arrow with a null velocity");

        net.minecraft.entity.projectile.EntityArrow arrow = new net.minecraft.entity.projectile.EntityArrow(world);
        arrow.setLocationAndAngles(loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        arrow.setThrowableHeading(velocity.getX(), velocity.getY(), velocity.getZ(), speed, spread);
        world.spawnEntityInWorld(arrow);
        return (Arrow) arrow.getBukkitEntity();
    }

    @Deprecated
    public LivingEntity spawnCreature(Location loc, CreatureType creatureType) {
        return spawnCreature(loc, creatureType.toEntityType());
    }

    @Deprecated
    public LivingEntity spawnCreature(Location loc, EntityType creatureType) {
        Validate.isTrue(creatureType.isAlive(), "EntityType not instance of LivingEntity");
        return (LivingEntity) spawnEntity(loc, creatureType);
    }

    public Entity spawnEntity(Location loc, EntityType entityType) {
        // Cauldron start - handle custom entity spawns from plugins
        if (EntityRegistry.entityClassMap.get(entityType.getName()) != null)
        {
            net.minecraft.entity.Entity entity = null;
            entity = getEntity(EntityRegistry.entityClassMap.get(entityType.getName()), world);
            if (entity != null)
            {
                entity.setLocationAndAngles(loc.getX(), loc.getY(), loc.getZ(), 0, 0);
                world.addEntity(entity, SpawnReason.CUSTOM);
                return entity.getBukkitEntity();
            }
        }
        // Cauldron end
        return spawn(loc, entityType.getEntityClass());
    }

    // Cauldron start
    public net.minecraft.entity.Entity getEntity(Class<? extends net.minecraft.entity.Entity> clazz, net.minecraft.world.World world)
    {
        net.minecraft.entity.EntityLiving entity = null;
        try
        {
            entity = (net.minecraft.entity.EntityLiving) clazz.getConstructor(new Class[] { net.minecraft.world.World.class }).newInstance(new Object[] { world });
        }
        catch (Throwable throwable)
        {
        }
        return entity;
    }
    // Cauldron end

    public LightningStrike strikeLightning(Location loc) {
        net.minecraft.entity.effect.EntityLightningBolt lightning = new net.minecraft.entity.effect.EntityLightningBolt(world, loc.getX(), loc.getY(), loc.getZ());
        world.addWeatherEffect(lightning);
        return new CraftLightningStrike(server, lightning);
    }

    public LightningStrike strikeLightningEffect(Location loc) {
        net.minecraft.entity.effect.EntityLightningBolt lightning = new net.minecraft.entity.effect.EntityLightningBolt(world, loc.getX(), loc.getY(), loc.getZ(), true);
        world.addWeatherEffect(lightning);
        return new CraftLightningStrike(server, lightning);
    }

    public boolean generateTree(Location loc, TreeType type) {
        net.minecraft.world.gen.feature.WorldGenerator gen;
        switch (type) {
        case BIG_TREE:
            gen = new net.minecraft.world.gen.feature.WorldGenBigTree(true);
            break;
        case BIRCH:
            gen = new net.minecraft.world.gen.feature.WorldGenForest(true, false);
            break;
        case REDWOOD:
            gen = new net.minecraft.world.gen.feature.WorldGenTaiga2(true);
            break;
        case TALL_REDWOOD:
            gen = new net.minecraft.world.gen.feature.WorldGenTaiga1();
            break;
        case JUNGLE:
            gen = new net.minecraft.world.gen.feature.WorldGenMegaJungle(true, 10, 20, 3, 3); // Magic values as in BlockSapling
            break;
        case SMALL_JUNGLE:
            gen = new net.minecraft.world.gen.feature.WorldGenTrees(true, 4 + rand.nextInt(7), 3, 3, false);
            break;
        case COCOA_TREE:
            gen = new net.minecraft.world.gen.feature.WorldGenTrees(true, 4 + rand.nextInt(7), 3, 3, true);
            break;
        case JUNGLE_BUSH:
            gen = new net.minecraft.world.gen.feature.WorldGenShrub(3, 0);
            break;
        case RED_MUSHROOM:
            gen = new net.minecraft.world.gen.feature.WorldGenBigMushroom(1);
            break;
        case BROWN_MUSHROOM:
            gen = new net.minecraft.world.gen.feature.WorldGenBigMushroom(0);
            break;
        case SWAMP:
            gen = new net.minecraft.world.gen.feature.WorldGenSwamp();
            break;
        case ACACIA:
            gen = new net.minecraft.world.gen.feature.WorldGenSavannaTree(true);
            break;
        case DARK_OAK:
            gen = new net.minecraft.world.gen.feature.WorldGenCanopyTree(true);
            break;
        case MEGA_REDWOOD:
            gen = new net.minecraft.world.gen.feature.WorldGenMegaPineTree(true, rand.nextBoolean());
            break;
        case TALL_BIRCH:
            gen = new net.minecraft.world.gen.feature.WorldGenForest(true, true);
            break;
        case TREE:
        default:
            gen = new net.minecraft.world.gen.feature.WorldGenTrees(true);
            break;
        }

        return gen.generate(world, rand, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public boolean generateTree(Location loc, TreeType type, BlockChangeDelegate delegate) {
        net.minecraft.world.gen.feature.WorldGenerator gen;
        switch (type) {
        case BIG_TREE:
            gen = new net.minecraft.world.gen.feature.WorldGenBigTree(true);
            break;
        case BIRCH:
            gen = new net.minecraft.world.gen.feature.WorldGenForest(true, false);
            break;
        case REDWOOD:
            gen = new net.minecraft.world.gen.feature.WorldGenTaiga2(true);
            break;
        case TALL_REDWOOD:
            gen = new net.minecraft.world.gen.feature.WorldGenTaiga1();
            break;
        case JUNGLE:
            gen = new net.minecraft.world.gen.feature.WorldGenMegaJungle(true, 10, 20, 3, 3); // Magic values as in BlockSapling
            break;
        case SMALL_JUNGLE:
            gen = new net.minecraft.world.gen.feature.WorldGenTrees(true, 4 + rand.nextInt(7), 3, 3, false);
            break;
        case JUNGLE_BUSH:
            gen = new net.minecraft.world.gen.feature.WorldGenShrub(3, 0);
            break;
        case RED_MUSHROOM:
            gen = new net.minecraft.world.gen.feature.WorldGenBigMushroom(1);
            break;
        case BROWN_MUSHROOM:
            gen = new net.minecraft.world.gen.feature.WorldGenBigMushroom(0);
            break;
        case SWAMP:
            gen = new net.minecraft.world.gen.feature.WorldGenSwamp();
            break;
        case ACACIA:
            gen = new net.minecraft.world.gen.feature.WorldGenSavannaTree(true);
            break;
        case DARK_OAK:
            gen = new net.minecraft.world.gen.feature.WorldGenCanopyTree(true);
            break;
        case MEGA_REDWOOD:
            gen = new net.minecraft.world.gen.feature.WorldGenMegaPineTree(true, rand.nextBoolean());
            break;
        case TALL_BIRCH:
            gen = new net.minecraft.world.gen.feature.WorldGenForest(true, true);
            break;
        case TREE:
        default:
            gen = new net.minecraft.world.gen.feature.WorldGenTrees(true);
            break;
        }

        world.captureTreeGeneration = true;
        world.captureBlockSnapshots = true;
        boolean grownTree = gen.generate(world, rand, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        world.captureBlockSnapshots = false;
        world.captureTreeGeneration = false;
        if (grownTree) { // Copy block data to delegate
            for (BlockSnapshot blocksnapshot : world.capturedBlockSnapshots) {
                int x = blocksnapshot.x;
                int y = blocksnapshot.y;
                int z = blocksnapshot.z;
                net.minecraft.block.Block oldBlock = world.getBlock(x, y, z); 
                int newId = net.minecraft.block.Block.getIdFromBlock(blocksnapshot.replacedBlock);
                int data = blocksnapshot.meta;
                int flag = blocksnapshot.flag;
                delegate.setTypeIdAndData(x, y, z, newId, data);
                net.minecraft.block.Block newBlock = world.getBlock(x, y, z); 
                world.markAndNotifyBlock(x, y, z, null, oldBlock, newBlock, flag);
            }
            world.capturedBlockSnapshots.clear();
            return true;
        }
        else {
            world.capturedBlockSnapshots.clear();
            return false;
        }
    }

    public net.minecraft.tileentity.TileEntity getTileEntityAt(final int x, final int y, final int z) {
        return world.getTileEntity(x, y, z);
    }

    public String getName() {
        return world.worldInfo.getWorldName();
    }

    @Deprecated
    public long getId() {
        return world.worldInfo.getSeed();
    }

    public UUID getUID() {
        return world.getSaveHandler().getUUID();
    }

    @Override
    public String toString() {
        return "CraftWorld{name=" + getName() + '}';
    }

    public long getTime() {
        long time = getFullTime() % 24000;
        if (time < 0) time += 24000;
        return time;
    }

    public void setTime(long time) {
        long margin = (time - getFullTime()) % 24000;
        if (margin < 0) margin += 24000;
        setFullTime(getFullTime() + margin);
    }

    public long getFullTime() {
        return world.getWorldTime();
    }

    public void setFullTime(long time) {
        world.setWorldTime(time);

        // Forces the client to update to the new time immediately
        for (Player p : getPlayers()) {
            CraftPlayer cp = (CraftPlayer) p;
            if (cp.getHandle().playerNetServerHandler == null) continue;

            cp.getHandle().playerNetServerHandler.sendPacket(new net.minecraft.network.play.server.S03PacketTimeUpdate(cp.getHandle().worldObj.getTotalWorldTime(), cp.getHandle().getPlayerTime(), cp.getHandle().worldObj.getGameRules().getGameRuleBooleanValue("doDaylightCycle")));
        }
    }

    public boolean createExplosion(double x, double y, double z, float power) {
        return createExplosion(x, y, z, power, false, true);
    }

    public boolean createExplosion(double x, double y, double z, float power, boolean setFire) {
        return createExplosion(x, y, z, power, setFire, true);
    }

    public boolean createExplosion(double x, double y, double z, float power, boolean setFire, boolean breakBlocks) {
        return !world.newExplosion(null, x, y, z, power, setFire, breakBlocks).wasCanceled;
    }

    public boolean createExplosion(Location loc, float power) {
        return createExplosion(loc, power, false);
    }

    public boolean createExplosion(Location loc, float power, boolean setFire) {
        return createExplosion(loc.getX(), loc.getY(), loc.getZ(), power, setFire);
    }

    public Environment getEnvironment() {
        return environment;
    }

    public void setEnvironment(Environment env) {
        if (environment != env) {
            environment = env;
            world.provider = net.minecraft.world.WorldProvider.getProviderForDimension(environment.getId());
        }
    }

    public Block getBlockAt(Location location) {
        return getBlockAt(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public int getBlockTypeIdAt(Location location) {
        return getBlockTypeIdAt(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public int getHighestBlockYAt(Location location) {
        return getHighestBlockYAt(location.getBlockX(), location.getBlockZ());
    }

    public Chunk getChunkAt(Location location) {
        return getChunkAt(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    public ChunkGenerator getGenerator() {
        return generator;
    }

    // Cauldron start - allow generator to be set if null in order to fix TC issue with overworld
    public void setGenerator(ChunkGenerator generator) {
        if (this.generator == null) {
            this.generator = generator;
        }
    }
    // Cauldron end

    public List<BlockPopulator> getPopulators() {
        return populators;
    }

    public Block getHighestBlockAt(int x, int z) {
        return getBlockAt(x, getHighestBlockYAt(x, z), z);
    }

    public Block getHighestBlockAt(Location location) {
        return getHighestBlockAt(location.getBlockX(), location.getBlockZ());
    }

    public Biome getBiome(int x, int z) {
        return CraftBlock.biomeBaseToBiome(this.world.getBiomeGenForCoords(x, z));
    }

    public void setBiome(int x, int z, Biome bio) {
        net.minecraft.world.biome.BiomeGenBase bb = CraftBlock.biomeToBiomeBase(bio);
        if (this.world.blockExists(x, 0, z)) {
            net.minecraft.world.chunk.Chunk chunk = this.world.getChunkFromBlockCoords(x, z);

            if (chunk != null) {
                byte[] biomevals = chunk.getBiomeArray();
                biomevals[((z & 0xF) << 4) | (x & 0xF)] = (byte)bb.biomeID;
            }
        }
    }

    public double getTemperature(int x, int z) {
        return this.world.getBiomeGenForCoords(x, z).temperature;
    }

    public double getHumidity(int x, int z) {
        return this.world.getBiomeGenForCoords(x, z).rainfall;
    }

    public List<Entity> getEntities() {
        List<Entity> list = new ArrayList<Entity>();

        for (Object o : world.loadedEntityList) {
            if (o instanceof net.minecraft.entity.Entity) {
                net.minecraft.entity.Entity mcEnt = (net.minecraft.entity.Entity) o;
                Entity bukkitEntity = mcEnt.getBukkitEntity();

                // Assuming that bukkitEntity isn't null
                if (bukkitEntity != null) {
                    list.add(bukkitEntity);
                }
            }
        }

        return list;
    }

    public List<LivingEntity> getLivingEntities() {
        List<LivingEntity> list = new ArrayList<LivingEntity>();

        for (Object o : world.loadedEntityList) {
            if (o instanceof net.minecraft.entity.Entity) {
                net.minecraft.entity.Entity mcEnt = (net.minecraft.entity.Entity) o;
                Entity bukkitEntity = mcEnt.getBukkitEntity();

                // Assuming that bukkitEntity isn't null
                if (bukkitEntity != null && bukkitEntity instanceof LivingEntity) {
                    list.add((LivingEntity) bukkitEntity);
                }
            }
        }

        return list;
    }

    @SuppressWarnings("unchecked")
    @Deprecated
    public <T extends Entity> Collection<T> getEntitiesByClass(Class<T>... classes) {
        return (Collection<T>)getEntitiesByClasses(classes);
    }

    @SuppressWarnings("unchecked")
    public <T extends Entity> Collection<T> getEntitiesByClass(Class<T> clazz) {
        Collection<T> list = new ArrayList<T>();

        for (Object entity: world.loadedEntityList) {
            if (entity instanceof net.minecraft.entity.Entity) {
                Entity bukkitEntity = ((net.minecraft.entity.Entity) entity).getBukkitEntity();

                if (bukkitEntity == null) {
                    continue;
                }

                Class<?> bukkitClass = bukkitEntity.getClass();

                if (clazz.isAssignableFrom(bukkitClass)) {
                    list.add((T) bukkitEntity);
                }
            }
        }

        return list;
    }

    public Collection<Entity> getEntitiesByClasses(Class<?>... classes) {
        Collection<Entity> list = new ArrayList<Entity>();

        for (Object entity: world.loadedEntityList) {
            if (entity instanceof net.minecraft.entity.Entity) {
                Entity bukkitEntity = ((net.minecraft.entity.Entity) entity).getBukkitEntity();

                if (bukkitEntity == null) {
                    continue;
                }

                Class<?> bukkitClass = bukkitEntity.getClass();

                for (Class<?> clazz : classes) {
                    if (clazz.isAssignableFrom(bukkitClass)) {
                        list.add(bukkitEntity);
                        break;
                    }
                }
            }
        }

        return list;
    }

    public List<Player> getPlayers() {
        List<Player> list = new ArrayList<Player>();

        for (Object o : world.loadedEntityList) {
            if (o instanceof net.minecraft.entity.Entity) {
                net.minecraft.entity.Entity mcEnt = (net.minecraft.entity.Entity) o;
                Entity bukkitEntity = mcEnt.getBukkitEntity();

                if ((bukkitEntity != null) && (bukkitEntity instanceof Player)) {
                    list.add((Player) bukkitEntity);
                }
            }
        }

        return list;
    }

    public void save() {
        this.server.checkSaveState();
        try {
            boolean oldSave = world.levelSaving;

            world.levelSaving = false;
            world.saveAllChunks(true, null);

            world.levelSaving = oldSave;
        } catch (net.minecraft.world.MinecraftException ex) {
            ex.printStackTrace();
        }
    }

    public boolean isAutoSave() {
        return !world.levelSaving;
    }

    public void setAutoSave(boolean value) {
        world.levelSaving = !value;
    }

    public void setDifficulty(Difficulty difficulty) {
        this.getHandle().difficultySetting = net.minecraft.world.EnumDifficulty.getDifficultyEnum(difficulty.getValue());
    }

    public Difficulty getDifficulty() {
        return Difficulty.getByValue(this.getHandle().difficultySetting.ordinal());
    }

    public BlockMetadataStore getBlockMetadata() {
        return blockMetadata;
    }

    public boolean hasStorm() {
        return world.worldInfo.isRaining();
    }

    public void setStorm(boolean hasStorm) {
        CraftServer server = world.getServer();

        WeatherChangeEvent weather = new WeatherChangeEvent(this, hasStorm);
        server.getPluginManager().callEvent(weather);
        if (!weather.isCancelled()) {
            world.worldInfo.setRaining(hasStorm);

            // These numbers are from Minecraft
            if (hasStorm) {
                setWeatherDuration(rand.nextInt(12000) + 12000);
            } else {
                setWeatherDuration(rand.nextInt(168000) + 12000);
            }
        }
    }

    public int getWeatherDuration() {
        return world.worldInfo.getRainTime();
    }

    public void setWeatherDuration(int duration) {
        world.worldInfo.setRainTime(duration);
    }

    public boolean isThundering() {
        return hasStorm() && world.worldInfo.isThundering();
    }

    public void setThundering(boolean thundering) {
        if (thundering && !hasStorm()) setStorm(true);
        CraftServer server = world.getServer();

        ThunderChangeEvent thunder = new ThunderChangeEvent(this, thundering);
        server.getPluginManager().callEvent(thunder);
        if (!thunder.isCancelled()) {
            world.worldInfo.setThundering(thundering);

            // These numbers are from Minecraft
            if (thundering) {
                setThunderDuration(rand.nextInt(12000) + 3600);
            } else {
                setThunderDuration(rand.nextInt(168000) + 12000);
            }
        }
    }

    public int getThunderDuration() {
        return world.worldInfo.getThunderTime();
    }

    public void setThunderDuration(int duration) {
        world.worldInfo.setThunderTime(duration);
    }

    public long getSeed() {
        return world.worldInfo.getSeed();
    }

    public boolean getPVP() {
        return world.pvpMode;
    }

    public void setPVP(boolean pvp) {
        world.pvpMode = pvp;
    }

    public void playEffect(Player player, Effect effect, int data) {
        playEffect(player.getLocation(), effect, data, 0);
    }

    public void playEffect(Location location, Effect effect, int data) {
        playEffect(location, effect, data, 64);
    }

    public <T> void playEffect(Location loc, Effect effect, T data) {
        playEffect(loc, effect, data, 64);
    }

    public <T> void playEffect(Location loc, Effect effect, T data, int radius) {
        if (data != null) {
            Validate.isTrue(data.getClass().equals(effect.getData()), "Wrong kind of data for this effect!");
        } else {
            Validate.isTrue(effect.getData() == null, "Wrong kind of data for this effect!");
        }

        if (data != null && data.getClass().equals( org.bukkit.material.MaterialData.class )) {
            org.bukkit.material.MaterialData materialData = (org.bukkit.material.MaterialData) data;
            Validate.isTrue( materialData.getItemType().isBlock(), "Material must be block" );
            spigot().playEffect( loc, effect, materialData.getItemType().getId(), materialData.getData(), 0, 0, 0, 1, 1, radius );
        } else {
            int dataValue = data == null ? 0 : CraftEffect.getDataValue( effect, data );
            playEffect( loc, effect, dataValue, radius );
        }
    }

    public void playEffect(Location location, Effect effect, int data, int radius) {
        spigot().playEffect( location, effect, data, 0, 0, 0, 0, 1, 1, radius );
    }

    public <T extends Entity> T spawn(Location location, Class<T> clazz) throws IllegalArgumentException {
        return spawn(location, clazz, SpawnReason.CUSTOM);
    }

    public FallingBlock spawnFallingBlock(Location location, org.bukkit.Material material, byte data) throws IllegalArgumentException {
        Validate.notNull(location, "Location cannot be null");
        Validate.notNull(material, "Material cannot be null");
        Validate.isTrue(material.isBlock(), "Material must be a block");

        double x = location.getBlockX() + 0.5;
        double y = location.getBlockY() + 0.5;
        double z = location.getBlockZ() + 0.5;

        net.minecraft.entity.item.EntityFallingBlock entity = new net.minecraft.entity.item.EntityFallingBlock(world, x, y, z, net.minecraft.block.Block.getBlockById(material.getId()), data);
        entity.field_145812_b = 1; // ticksLived

        world.addEntity(entity, SpawnReason.CUSTOM);
        return (FallingBlock) entity.getBukkitEntity();
    }

    public FallingBlock spawnFallingBlock(Location location, int blockId, byte blockData) throws IllegalArgumentException {
        return spawnFallingBlock(location, org.bukkit.Material.getMaterial(blockId), blockData);
    }

    @SuppressWarnings("unchecked")
    public <T extends Entity> T spawn(Location location, Class<T> clazz, SpawnReason reason) throws IllegalArgumentException {
        if (location == null || clazz == null) {
            throw new IllegalArgumentException("Location or entity class cannot be null");
        }

        net.minecraft.entity.Entity entity = null;

        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        float pitch = location.getPitch();
        float yaw = location.getYaw();

        // order is important for some of these
        if (Boat.class.isAssignableFrom(clazz)) {
            entity = new net.minecraft.entity.item.EntityBoat(world, x, y, z);
        } else if (FallingBlock.class.isAssignableFrom(clazz)) {
            x = location.getBlockX();
            y = location.getBlockY();
            z = location.getBlockZ();
            int type = world.getTypeId((int) x, (int) y, (int) z);
            int data = world.getBlockMetadata((int) x, (int) y, (int) z);

            entity = new net.minecraft.entity.item.EntityFallingBlock(world, x + 0.5, y + 0.5, z + 0.5, net.minecraft.block.Block.getBlockById(type), data);
        } else if (Projectile.class.isAssignableFrom(clazz)) {
            if (Snowball.class.isAssignableFrom(clazz)) {
                entity = new net.minecraft.entity.projectile.EntitySnowball(world, x, y, z);
            } else if (Egg.class.isAssignableFrom(clazz)) {
                entity = new net.minecraft.entity.projectile.EntityEgg(world, x, y, z);
            } else if (Arrow.class.isAssignableFrom(clazz)) {
                entity = new net.minecraft.entity.projectile.EntityArrow(world);
                entity.setLocationAndAngles(x, y, z, 0, 0);
            } else if (ThrownExpBottle.class.isAssignableFrom(clazz)) {
                entity = new net.minecraft.entity.item.EntityExpBottle(world);
                entity.setLocationAndAngles(x, y, z, 0, 0);
            } else if (EnderPearl.class.isAssignableFrom(clazz)) {
                entity = new net.minecraft.entity.item.EntityEnderPearl(world);
                entity.setLocationAndAngles(x, y, z, 0, 0);
            } else if (ThrownPotion.class.isAssignableFrom(clazz)) {
                entity = new net.minecraft.entity.projectile.EntityPotion(world, x, y, z, CraftItemStack.asNMSCopy(new ItemStack(org.bukkit.Material.POTION, 1)));
            } else if (Fireball.class.isAssignableFrom(clazz)) {
                if (SmallFireball.class.isAssignableFrom(clazz)) {
                    entity = new net.minecraft.entity.projectile.EntitySmallFireball(world);
                } else if (WitherSkull.class.isAssignableFrom(clazz)) {
                    entity = new net.minecraft.entity.projectile.EntityWitherSkull(world);
                } else {
                    entity = new net.minecraft.entity.projectile.EntityLargeFireball(world);
                }
                entity.setLocationAndAngles(x, y, z, yaw, pitch);
                Vector direction = location.getDirection().multiply(10);
                ((net.minecraft.entity.projectile.EntityFireball) entity).setDirection(direction.getX(), direction.getY(), direction.getZ());
            }
        } else if (Minecart.class.isAssignableFrom(clazz)) {
            if (PoweredMinecart.class.isAssignableFrom(clazz)) {
                entity = new net.minecraft.entity.item.EntityMinecartFurnace(world, x, y, z);
            } else if (StorageMinecart.class.isAssignableFrom(clazz)) {
                entity = new net.minecraft.entity.item.EntityMinecartChest(world, x, y, z);
            } else if (ExplosiveMinecart.class.isAssignableFrom(clazz)) {
                entity = new net.minecraft.entity.item.EntityMinecartTNT(world, x, y, z);
            } else if (HopperMinecart.class.isAssignableFrom(clazz)) {
                entity = new net.minecraft.entity.item.EntityMinecartHopper(world, x, y, z);
            } else if (SpawnerMinecart.class.isAssignableFrom(clazz)) {
                entity = new net.minecraft.entity.ai.EntityMinecartMobSpawner(world, x, y, z);
            } else { // Default to rideable minecart for pre-rideable compatibility
                entity = new net.minecraft.entity.item.EntityMinecartEmpty(world, x, y, z);
            }
        } else if (EnderSignal.class.isAssignableFrom(clazz)) {
            entity = new net.minecraft.entity.item.EntityEnderEye(world, x, y, z);
        } else if (EnderCrystal.class.isAssignableFrom(clazz)) {
            entity = new net.minecraft.entity.item.EntityEnderCrystal(world);
            entity.setLocationAndAngles(x, y, z, 0, 0);
        } else if (LivingEntity.class.isAssignableFrom(clazz)) {
            if (Chicken.class.isAssignableFrom(clazz)) {
                entity = new net.minecraft.entity.passive.EntityChicken(world);
            } else if (Cow.class.isAssignableFrom(clazz)) {
                if (MushroomCow.class.isAssignableFrom(clazz)) {
                    entity = new net.minecraft.entity.passive.EntityMooshroom(world);
                } else {
                    entity = new net.minecraft.entity.passive.EntityCow(world);
                }
            } else if (Golem.class.isAssignableFrom(clazz)) {
                if (Snowman.class.isAssignableFrom(clazz)) {
                    entity = new net.minecraft.entity.monster.EntitySnowman(world);
                } else if (IronGolem.class.isAssignableFrom(clazz)) {
                    entity = new net.minecraft.entity.monster.EntityIronGolem(world);
                }
            } else if (Creeper.class.isAssignableFrom(clazz)) {
                entity = new net.minecraft.entity.monster.EntityCreeper(world);
            } else if (Ghast.class.isAssignableFrom(clazz)) {
                entity = new net.minecraft.entity.monster.EntityGhast(world);
            } else if (Pig.class.isAssignableFrom(clazz)) {
                entity = new net.minecraft.entity.passive.EntityPig(world);
            } else if (Player.class.isAssignableFrom(clazz)) {
                // need a net server handler for this one
            } else if (Sheep.class.isAssignableFrom(clazz)) {
                entity = new net.minecraft.entity.passive.EntitySheep(world);
            } else if (Horse.class.isAssignableFrom(clazz)) {
                entity = new net.minecraft.entity.passive.EntityHorse(world);
            } else if (Skeleton.class.isAssignableFrom(clazz)) {
                entity = new net.minecraft.entity.monster.EntitySkeleton(world);
            } else if (Slime.class.isAssignableFrom(clazz)) {
                if (MagmaCube.class.isAssignableFrom(clazz)) {
                    entity = new net.minecraft.entity.monster.EntityMagmaCube(world);
                } else {
                    entity = new net.minecraft.entity.monster.EntitySlime(world);
                }
            } else if (Spider.class.isAssignableFrom(clazz)) {
                if (CaveSpider.class.isAssignableFrom(clazz)) {
                    entity = new net.minecraft.entity.monster.EntityCaveSpider(world);
                } else {
                    entity = new net.minecraft.entity.monster.EntitySpider(world);
                }
            } else if (Squid.class.isAssignableFrom(clazz)) {
                entity = new net.minecraft.entity.passive.EntitySquid(world);
            } else if (Tameable.class.isAssignableFrom(clazz)) {
                if (Wolf.class.isAssignableFrom(clazz)) {
                    entity = new net.minecraft.entity.passive.EntityWolf(world);
                } else if (Ocelot.class.isAssignableFrom(clazz)) {
                    entity = new net.minecraft.entity.passive.EntityOcelot(world);
                }
            } else if (PigZombie.class.isAssignableFrom(clazz)) {
                entity = new net.minecraft.entity.monster.EntityPigZombie(world);
            } else if (Zombie.class.isAssignableFrom(clazz)) {
                entity = new net.minecraft.entity.monster.EntityZombie(world);
            } else if (Giant.class.isAssignableFrom(clazz)) {
                entity = new net.minecraft.entity.monster.EntityGiantZombie(world);
            } else if (Silverfish.class.isAssignableFrom(clazz)) {
                entity = new net.minecraft.entity.monster.EntitySilverfish(world);
            } else if (Enderman.class.isAssignableFrom(clazz)) {
                entity = new net.minecraft.entity.monster.EntityEnderman(world);
            } else if (Blaze.class.isAssignableFrom(clazz)) {
                entity = new net.minecraft.entity.monster.EntityBlaze(world);
            } else if (Villager.class.isAssignableFrom(clazz)) {
                entity = new net.minecraft.entity.passive.EntityVillager(world);
            } else if (Witch.class.isAssignableFrom(clazz)) {
                entity = new net.minecraft.entity.monster.EntityWitch(world);
            } else if (Wither.class.isAssignableFrom(clazz)) {
                entity = new net.minecraft.entity.boss.EntityWither(world);
            } else if (ComplexLivingEntity.class.isAssignableFrom(clazz)) {
                if (EnderDragon.class.isAssignableFrom(clazz)) {
                    entity = new net.minecraft.entity.boss.EntityDragon(world);
                }
            } else if (Ambient.class.isAssignableFrom(clazz)) {
                if (Bat.class.isAssignableFrom(clazz)) {
                    entity = new net.minecraft.entity.passive.EntityBat(world);
                }
            }

            if (entity != null) {
                entity.setPositionAndRotation(x, y, z, yaw, pitch);
            }
        } else if (Hanging.class.isAssignableFrom(clazz)) {
            Block block = getBlockAt(location);
            BlockFace face = BlockFace.SELF;
            if (block.getRelative(BlockFace.EAST).getTypeId() == 0) {
                face = BlockFace.EAST;
            } else if (block.getRelative(BlockFace.NORTH).getTypeId() == 0) {
                face = BlockFace.NORTH;
            } else if (block.getRelative(BlockFace.WEST).getTypeId() == 0) {
                face = BlockFace.WEST;
            } else if (block.getRelative(BlockFace.SOUTH).getTypeId() == 0) {
                face = BlockFace.SOUTH;
            }
            int dir;
            switch (face) {
            case SOUTH:
            default:
                dir = 0;
                break;
            case WEST:
                dir = 1;
                break;
            case NORTH:
                dir = 2;
                break;
            case EAST:
                dir = 3;
                break;
            }

            if (Painting.class.isAssignableFrom(clazz)) {
                entity = new net.minecraft.entity.item.EntityPainting(world, (int) x, (int) y, (int) z, dir);
            } else if (ItemFrame.class.isAssignableFrom(clazz)) {
                entity = new net.minecraft.entity.item.EntityItemFrame(world, (int) x, (int) y, (int) z, dir);
            } else if (LeashHitch.class.isAssignableFrom(clazz)) {
                entity = new net.minecraft.entity.EntityLeashKnot(world, (int) x, (int) y, (int) z);
                entity.forceSpawn = true;
            }

            if (entity != null && !((net.minecraft.entity.EntityHanging) entity).onValidSurface()) {
                throw new IllegalArgumentException("Cannot spawn hanging entity for " + clazz.getName() + " at " + location);
            }
        } else if (TNTPrimed.class.isAssignableFrom(clazz)) {
            entity = new net.minecraft.entity.item.EntityTNTPrimed(world, x, y, z, null);
        } else if (ExperienceOrb.class.isAssignableFrom(clazz)) {
            entity = new net.minecraft.entity.item.EntityXPOrb(world, x, y, z, 0);
        } else if (Weather.class.isAssignableFrom(clazz)) {
            // not sure what this can do
            if (LightningStrike.class.isAssignableFrom(clazz)) {
                entity = new net.minecraft.entity.effect.EntityLightningBolt(world, x, y, z);
                // what is this, I don't even
            }
        } else if (Firework.class.isAssignableFrom(clazz)) {
            entity = new net.minecraft.entity.item.EntityFireworkRocket(world, x, y, z, null);
        }

        if (entity != null) {
            if (entity instanceof EntityLiving) {
                ((EntityLiving) entity).onSpawnWithEgg((IEntityLivingData) null);
            }

            world.addEntity(entity, reason);
            return (T) entity.getBukkitEntity();
        }

        throw new IllegalArgumentException("Cannot spawn an entity for " + clazz.getName());
    }

    public ChunkSnapshot getEmptyChunkSnapshot(int x, int z, boolean includeBiome, boolean includeBiomeTempRain) {
        return CraftChunk.getEmptyChunkSnapshot(x, z, this, includeBiome, includeBiomeTempRain);
    }

    public void setSpawnFlags(boolean allowMonsters, boolean allowAnimals) {
        world.setAllowedSpawnTypes(allowMonsters, allowAnimals);
    }

    public boolean getAllowAnimals() {
        return world.spawnPeacefulMobs;
    }

    public boolean getAllowMonsters() {
        return world.spawnHostileMobs;
    }

    public int getMaxHeight() {
        return world.getHeight();
    }

    public int getSeaLevel() {
        return 64;
    }

    public boolean getKeepSpawnInMemory() {
        return world.keepSpawnInMemory;
    }

    public void setKeepSpawnInMemory(boolean keepLoaded) {
        world.keepSpawnInMemory = keepLoaded;
        // Grab the worlds spawn chunk
        net.minecraft.util.ChunkCoordinates chunkcoordinates = this.world.getSpawnPoint();
        int chunkCoordX = chunkcoordinates.posX >> 4;
        int chunkCoordZ = chunkcoordinates.posZ >> 4;
        // Cycle through the 25x25 Chunks around it to load/unload the chunks.
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                if (keepLoaded) {
                    loadChunk(chunkCoordX + x, chunkCoordZ + z);
                } else {
                    if (isChunkLoaded(chunkCoordX + x, chunkCoordZ + z)) {
                        if (this.getHandle().getChunkFromChunkCoords(chunkCoordX + x, chunkCoordZ + z) instanceof net.minecraft.world.chunk.EmptyChunk) {
                            unloadChunk(chunkCoordX + x, chunkCoordZ + z, false);
                        } else {
                            unloadChunk(chunkCoordX + x, chunkCoordZ + z);
                        }
                    }
                }
            }
        }
    }

    @Override
    public int hashCode() {
        return getUID().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }

        final CraftWorld other = (CraftWorld) obj;

        return this.getUID() == other.getUID();
    }

    public File getWorldFolder() {
        return ((net.minecraft.world.storage.SaveHandler) world.getSaveHandler()).getWorldDirectory();
    }

    public void sendPluginMessage(Plugin source, String channel, byte[] message) {
        StandardMessenger.validatePluginMessage(server.getMessenger(), source, channel, message);

        for (Player player : getPlayers()) {
            player.sendPluginMessage(source, channel, message);
        }
    }

    public Set<String> getListeningPluginChannels() {
        Set<String> result = new HashSet<String>();

        for (Player player : getPlayers()) {
            result.addAll(player.getListeningPluginChannels());
        }

        return result;
    }

    public org.bukkit.WorldType getWorldType() {
        return org.bukkit.WorldType.getByName(world.getWorldInfo().getTerrainType().getWorldTypeName());
    }

    public boolean canGenerateStructures() {
        return world.getWorldInfo().isMapFeaturesEnabled();
    }

    public long getTicksPerAnimalSpawns() {
        return world.ticksPerAnimalSpawns;
    }

    public void setTicksPerAnimalSpawns(int ticksPerAnimalSpawns) {
        world.ticksPerAnimalSpawns = ticksPerAnimalSpawns;
    }

    public long getTicksPerMonsterSpawns() {
        return world.ticksPerMonsterSpawns;
    }

    public void setTicksPerMonsterSpawns(int ticksPerMonsterSpawns) {
        world.ticksPerMonsterSpawns = ticksPerMonsterSpawns;
    }

    public void setMetadata(String metadataKey, MetadataValue newMetadataValue) {
        server.getWorldMetadata().setMetadata(this, metadataKey, newMetadataValue);
    }

    public List<MetadataValue> getMetadata(String metadataKey) {
        return server.getWorldMetadata().getMetadata(this, metadataKey);
    }

    public boolean hasMetadata(String metadataKey) {
        return server.getWorldMetadata().hasMetadata(this, metadataKey);
    }

    public void removeMetadata(String metadataKey, Plugin owningPlugin) {
        server.getWorldMetadata().removeMetadata(this, metadataKey, owningPlugin);
    }

    public int getMonsterSpawnLimit() {
        if (monsterSpawn < 0) {
            return server.getMonsterSpawnLimit();
        }

        return monsterSpawn;
    }

    public void setMonsterSpawnLimit(int limit) {
        monsterSpawn = limit;
    }

    public int getAnimalSpawnLimit() {
        if (animalSpawn < 0) {
            return server.getAnimalSpawnLimit();
        }

        return animalSpawn;
    }

    public void setAnimalSpawnLimit(int limit) {
        animalSpawn = limit;
    }

    public int getWaterAnimalSpawnLimit() {
        if (waterAnimalSpawn < 0) {
            return server.getWaterAnimalSpawnLimit();
        }

        return waterAnimalSpawn;
    }

    public void setWaterAnimalSpawnLimit(int limit) {
        waterAnimalSpawn = limit;
    }

    public int getAmbientSpawnLimit() {
        if (ambientSpawn < 0) {
            return server.getAmbientSpawnLimit();
        }

        return ambientSpawn;
    }

    public void setAmbientSpawnLimit(int limit) {
        ambientSpawn = limit;
    }


    public void playSound(Location loc, Sound sound, float volume, float pitch) {
        if (loc == null || sound == null) return;

        double x = loc.getX();
        double y = loc.getY();
        double z = loc.getZ();

        getHandle().playSoundEffect(x, y, z, CraftSound.getSound(sound), volume, pitch);
    }

    public String getGameRuleValue(String rule) {
        return getHandle().getGameRules().getGameRuleStringValue(rule);
    }

    public boolean setGameRuleValue(String rule, String value) {
        // No null values allowed
        if (rule == null || value == null) return false;

        if (!isGameRule(rule)) return false;

        getHandle().getGameRules().setOrCreateGameRule(rule, value);
        return true;
    }

    public String[] getGameRules() {
        return getHandle().getGameRules().getRules();
    }

    public boolean isGameRule(String rule) {
        return getHandle().getGameRules().hasRule(rule);
    }

    public void processChunkGC() {
         chunkGCTickCount++;

        if (chunkLoadCount >= server.chunkGCLoadThresh && server.chunkGCLoadThresh > 0) {
            chunkLoadCount = 0;
        } else if (chunkGCTickCount >= server.chunkGCPeriod && server.chunkGCPeriod > 0) {
            chunkGCTickCount = 0;
        } else {
            return;
        }

        final net.minecraft.world.gen.ChunkProviderServer cps = world.theChunkProviderServer;
        for(net.minecraft.world.chunk.Chunk chunk : world.theChunkProviderServer.loadedChunkHashMap_KC.rawVanilla().values())
        {
            // If in use, skip it
            if (isChunkInUse(chunk.xPosition, chunk.zPosition)) {
                continue;
            }

            // Already unloading?
            if (cps.chunksToUnload.contains(chunk.xPosition, chunk.zPosition)) {
                continue;
            }

            // Add unload request
            cps.unloadChunksIfNotNearSpawn(chunk.xPosition, chunk.zPosition);
            continue;        	
        }
    }

    // Spigot start
    private final Spigot spigot = new Spigot()
    {
        @Override
        public void playEffect( Location location, Effect effect, int id, int data, float offsetX, float offsetY, float offsetZ, float speed, int particleCount, int radius )
        {
            Validate.notNull( location, "Location cannot be null" );
            Validate.notNull( effect, "Effect cannot be null" );
            Validate.notNull( location.getWorld(), "World cannot be null" );
            net.minecraft.network.Packet packet;
            if ( effect.getType() != Effect.Type.PARTICLE )
            {
                int packetData = effect.getId();
                packet = new net.minecraft.network.play.server.S28PacketEffect( packetData, location.getBlockX(), location.getBlockY(), location.getBlockZ(), id, false );
            } else
            {
                StringBuilder particleFullName = new StringBuilder();
                particleFullName.append( effect.getName() );
                if ( effect.getData() != null && ( effect.getData().equals( net.minecraft.block.material.Material.class ) || effect.getData().equals( org.bukkit.material.MaterialData.class ) ) )
                {
                    particleFullName.append( '_' ).append( id );
                }
                if ( effect.getData() != null && effect.getData().equals( org.bukkit.material.MaterialData.class ) )
                {
                    particleFullName.append( '_' ).append( data );
                }
                packet = new net.minecraft.network.play.server.S2APacketParticles( particleFullName.toString(), (float) location.getX(), (float) location.getY(), (float) location.getZ(), offsetX, offsetY, offsetZ, speed, particleCount );
            }
            int distance;
            radius *= radius;
            for ( Player player : getPlayers() )
            {
                if ( ( (CraftPlayer) player ).getHandle().playerNetServerHandler == null )
                {
                    continue;
                }
                if ( !location.getWorld().equals( player.getWorld() ) )
                {
                    continue;
                }
                distance = (int) player.getLocation().distanceSquared( location );
                if ( distance <= radius )
                {
                    ( (CraftPlayer) player ).getHandle().playerNetServerHandler.sendPacket( packet );
                }
            }
        }

        @Override
        public void playEffect( Location location, Effect effect )
        {
            CraftWorld.this.playEffect( location, effect, 0 );
        }
    };

    public Spigot spigot()
    {
        return spigot;
    }
    
    // Spigot end
    public WorldServer getWorldServer()
    {
    	return this.world;
    }    
}
