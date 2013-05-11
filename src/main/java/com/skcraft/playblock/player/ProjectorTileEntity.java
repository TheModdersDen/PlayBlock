package com.skcraft.playblock.player;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.logging.Level;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.INetworkManager;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.Packet132TileEntityData;
import net.minecraft.network.packet.Packet250CustomPayload;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;

import com.skcraft.playblock.PlayBlock;
import com.skcraft.playblock.util.AccessList;
import com.skcraft.playblock.util.MathUtils;
import com.skcraft.playblock.util.Validate;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.network.PacketDispatcher;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * The tile entity for the projector block.
 */
public class ProjectorTileEntity extends TileEntity {
    
    public static final String INTERNAL_NAME = "PlayBlockProjector";

    private static final int MAX_RANGE = 64;
    private static final int MIN_BUFFER_RANGE = 5;
    private static final int MAX_SCREEN_SIZE = 64;
    private static final int MAX_VIDEO_SIZE = 850;

    private String uri = "";
    private float width = 1;
    private float height = 1;
    private float triggerRange = 0;
    private float fadeRange = MIN_BUFFER_RANGE;

    private boolean hasPlayableUri = false;

    private final AccessList accessList = new AccessList();
    private MediaManager mediaManager;
    private MediaRenderer renderer;
    private boolean withinRange = false;
    private long playStartTime = 0;
    private long updateReceivedTime = 0;
    private String lastUri;
    private float rendererWidth;
    private float rendererHeight;

    /**
     * Construct a new instance of the projector tile entity.
     */
    public ProjectorTileEntity() {
        Side side = FMLCommonHandler.instance().getEffectiveSide();

        if (side == Side.CLIENT) {
            mediaManager = PlayBlock.getClientRuntime().getMediaManager();
        }
    }

    /**
     * Construct a new instance of the projector tile entity from an existing
     * tile entity, copying the X, Y, and Z coordinates.
     */
    ProjectorTileEntity(ProjectorTileEntity old) {
        xCoord = old.xCoord;
        yCoord = old.yCoord;
        zCoord = old.zCoord;
    }

    /**
     * Get the access list.
     * 
     * @return the access list
     */
    public AccessList getAccessList() {
        return accessList;
    }

    /**
     * Get the width of the screen.
     * 
     * @return the width
     */
    public float getWidth() {
        return width;
    }

    /**
     * Get the URI of the stream.
     * 
     * @return the URI
     */
    public String getUri() {
        return uri;
    }

    /**
     * Get the URI of the stream.
     * 
     * @param uri the URI
     */
    public void setUri(String uri) {
        Validate.notNull(uri);
        this.uri = MediaResolver.cleanUri(uri);
        hasPlayableUri = MediaResolver.canPlayUri(uri);
    }

    /**
     * Return whether the URI is playable.
     * 
     * @return true if playable
     */
    public boolean hasPlayableUri() {
        return hasPlayableUri;
    }

    /**
     * Set the width of the screen.
     * 
     * @param width the width
     */
    public void setWidth(float width) {
        this.width = MathUtils.clamp(width, 1, MAX_SCREEN_SIZE);
    }

    /**
     * Get the width of the screen.
     * 
     * @return the height
     */
    public float getHeight() {
        return height;
    }

    /**
     * Set the height of the screen.
     * 
     * @param height the height
     */
    public void setHeight(float height) {
        this.height = MathUtils.clamp(height, 1, MAX_SCREEN_SIZE);
    }

    /**
     * Get the renderer assigned to this tile entity.
     * 
     * @return the renderer, or possibly null
     */
    @SideOnly(Side.CLIENT)
    public MediaRenderer getRenderer() {
        return renderer;
    }

    /**
     * Gets the range (in blocks) at which the player will activate and start playing.
     * 
     * @see #getTriggerRangeSq() get the squared version, which is faster
     * @return the range in blocks
     */
    public float getTriggerRange() {
        return Math.round(Math.sqrt(triggerRange) * 100 / 100);
    }

    /**
     * Gets the range (in blocks) at which the player will activate and start playing.
     * 
     * @return the range in blocks, squared
     */
    public float getTriggerRangeSq() {
        return triggerRange;
    }

    /**
     * Sets the range (in blocks) at which the player will activate and start playing.
     * 
     * @param range the range in blocks
     */
    public void setTriggerRange(float range) {
        float v = MathUtils.clamp(range, 1, MAX_RANGE);
        triggerRange = v * v; // Store values squared
        ensureProperBuffer();
    }

    /**
     * Gets the range (in blocks) at which the player will stop playing if it is
     * currently playing.
     * 
     * @see #getFadeRangeSq() get the squared version, which is faster
     * @return the range
     */
    public float getFadeRange() {
        return Math.round(Math.sqrt(fadeRange) * 100 / 100);
    }

    /**
     * Gets the range (in blocks) at which the player will stop playing if it is
     * currently playing.
     * 
     * @return the range in blocks, squared
     */
    public float getFadeRangeSq() {
        return fadeRange;
    }

    /**
     * Sets the range (in blocks) at which the player will stop playing if it is
     * currently playing.
     * 
     * @param range range in blocks, squared
     */
    public void setFadeRange(float range) {
        float v = MathUtils.clamp(range, 1, MAX_RANGE + MIN_BUFFER_RANGE);
        fadeRange = v * v; // Store values squared
        ensureProperBuffer();
    }
    
    /**
     * This changes the fade distance appropriately to ensure that there is at least
     * a {@value #MIN_BUFFER_RANGE} block distance difference between the trigger
     * distance and the fade distance.
     */
    private void ensureProperBuffer() {
        float min = getTriggerRange() + MIN_BUFFER_RANGE;
        if (getFadeRange() < min) {
            // Do not call setFadeRange()!
            fadeRange = min * min; // Store values squared
        }
    }
    
    /**
     * Return whether a renderer has been assigned to this projector.
     * 
     * @return true if there is a renderer
     */
    private boolean hasRenderer() {
        return renderer != null;
    }

    /**
     * Return whether the client is within range of viewing this screen.
     * 
     * @return true if within range
     */
    public boolean isWithinRange() {
        return withinRange;
    }
    
    /**
     * Get the start time (in milliseconds) when play started.
     * 
     * @return the play start time
     */
    public long getPlayStartTime() {
        return playStartTime;
    }

    /**
     * Get the time (in milliseconds) when an update was received.
     * 
     * @return the update time.
     */
    public long getUpdateReceivedTime() {
        return updateReceivedTime;
    }

    /**
     * Acquire a renderer and start playing the video if possible.
     */
    @SideOnly(Side.CLIENT)
    private void setupRenderer() {
        int videoWidth = (int) Math.min(MAX_VIDEO_SIZE, width * 64);
        int videoHeight = (int) Math.min(MAX_VIDEO_SIZE, height * 64);
        renderer = mediaManager.acquireRenderer(videoWidth, videoHeight);
    }
    
    /**
     * Tries to play the media on this projector.
     * 
     * <p>A renderer will be acquired, or a new one will be setup if the width
     * and height have changed.</p>
     */
    @SideOnly(Side.CLIENT)
    private void tryPlayingMedia() {
        if (!hasRenderer()) {
            setupRenderer();
        } else if (rendererWidth != getWidth() || rendererHeight != getHeight()) {
            // Width or height change? Re-make the renderer
            release();
            setupRenderer();
        }

        if (hasPlayableUri()) {
            // Store these values in case the renderer needs to change
            rendererWidth = getWidth();
            rendererHeight = getHeight();
            if (lastUri == null || !lastUri.equals(uri)) { // Only change the media if we need to
                lastUri = uri;
                renderer.playMedia(uri);
                playStartTime = System.currentTimeMillis();
                mediaManager.setCurrentTime(System.currentTimeMillis() - updateReceivedTime);
            }
        }
    }

    /**
     * Detach the renderer from this instance and also stop the video.
     */
    @SideOnly(Side.CLIENT)
    private void release() {
        if (renderer != null) {
            mediaManager.release(renderer);
            renderer = null;
            lastUri = null;
        }
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void onChunkUnload() {
        release();
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void invalidate() {
        super.invalidate();
        release();
    }

    @Override
    public boolean canUpdate() {
        return true;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void updateEntity() {
        // Have to check to see whether this needs to activate
        if (this.worldObj.isRemote) {
            // Currently playing
            if (withinRange) {
                double distance = Minecraft.getMinecraft().thePlayer
                        .getDistanceSq(xCoord, yCoord, zCoord);
                
                // Passed the fade distance?
                if (distance >= getFadeRangeSq()) {
                    withinRange = false;
                    release();
                }
            
            // Currently not playing
            } else {
                if (mediaManager.hasNoRenderer()) {
                    double distance = Minecraft.getMinecraft().thePlayer
                            .getDistanceSq(xCoord, yCoord, zCoord);
                    
                    // Start the media
                    if (distance <= getTriggerRangeSq()) {
                        if (mediaManager.isSupported()) {
                            tryPlayingMedia();
                        }
                        
                        withinRange = true;
                    }
                }
            }
        }
    }

    /**
     * Create the update packet that is sent to the server.
     * 
     * @return the update packet
     */
    public Packet getUpdatePacket() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);

        try {
            data.writeInt(xCoord);
            data.writeInt(yCoord);
            data.writeInt(zCoord);
            data.writeUTF(getUri());
            data.writeFloat(getWidth());
            data.writeFloat(getHeight());
            data.writeFloat(getTriggerRange());
            data.writeFloat(getFadeRange());
        } catch (IOException e) {
            PlayBlock.log(Level.WARNING, "Failed to send update packet to the server");
        }

        Packet250CustomPayload packet = new Packet250CustomPayload(
                PlayBlock.CHANNEL_ID, bytes.toByteArray());
        return packet;
    }

    /**
     * Read the incoming data of an update packet destined for this tile entity.
     * 
     * @param player the player 
     * @param stream the data stream
     */
    public void acceptClientUpdate(EntityPlayer player, DataInputStream stream) {
        if (getAccessList().checkAndForget(player)) {
            try {
                // These values are validated
                setUri(stream.readUTF());
                setWidth(stream.readFloat());
                setHeight(stream.readFloat());
                setTriggerRange(stream.readFloat());
                setFadeRange(stream.readFloat());
            } catch (Throwable t) {
                PlayBlock.log(Level.WARNING, "Failed to handle update packet sent from " +
                		"the client");
            }
            
            updateReceivedTime = System.currentTimeMillis();
            // Now let's send the updates to players around the area
            PacketDispatcher.sendPacketToAllAround(xCoord, yCoord, zCoord, 250,
                    worldObj.provider.dimensionId, getDescriptionPacket());
        } else {
            player.sendChatToPlayer("Sorry, you don't have permission " +
            		"to modify that projector.");
        }
    }

    @Override
    public Packet getDescriptionPacket() {
        NBTTagCompound tag = new NBTTagCompound();
        writeToClientNBT(tag);
        return new Packet132TileEntityData(xCoord, yCoord, zCoord, -1, tag);
    }

    @Override
    public void onDataPacket(INetworkManager net,
            Packet132TileEntityData packet) {
        // Process data from the server
        if (this.worldObj.isRemote) {
            readFromClientNBT(packet.customParam1);
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        writeToClientNBT(tag);
    }

    /**
     * Write NBT tags that can also be sent to the client.
     * 
     * @param tag the tag
     */
    private void writeToClientNBT(NBTTagCompound tag) {
        tag.setString("uri", getUri());
        tag.setFloat("width", getWidth());
        tag.setFloat("height", getHeight());
        tag.setFloat("triggerRange", getTriggerRange());
        tag.setFloat("fadeRange", getFadeRange());
        tag.setLong("updateTime", updateReceivedTime);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        readFromClientNBT(tag);
    }

    /**
     * Read NBT tags that can also be read from the client.
     * 
     * @param tag the tag
     */
    private void readFromClientNBT(NBTTagCompound tag) {
        setUri(tag.getString("uri"));
        setWidth(tag.getFloat("width"));
        setHeight(tag.getFloat("height"));
        setTriggerRange(tag.getFloat("triggerRange"));
        setFadeRange(tag.getFloat("fadeRange"));
        updateReceivedTime = tag.getLong("updateTime");

        if (hasRenderer()) {
            tryPlayingMedia();
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public AxisAlignedBB getRenderBoundingBox() {
        // TODO: May want to use a less expansive render AABB
        return INFINITE_EXTENT_AABB;
    }

}
