// Copyright 2020 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.engine.network.internal;

import com.google.common.base.Objects;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.jboss.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.engine.core.Time;
import org.terasology.engine.entitySystem.Component;
import org.terasology.engine.entitySystem.entity.EntityManager;
import org.terasology.engine.entitySystem.entity.EntityRef;
import org.terasology.engine.entitySystem.event.Event;
import org.terasology.engine.entitySystem.metadata.EventLibrary;
import org.terasology.engine.entitySystem.metadata.EventMetadata;
import org.terasology.engine.entitySystem.metadata.NetworkEventType;
import org.terasology.engine.identity.PublicIdentityCertificate;
import org.terasology.engine.logic.characters.PredictionSystem;
import org.terasology.engine.logic.common.DisplayNameComponent;
import org.terasology.engine.logic.location.LocationComponent;
import org.terasology.engine.math.ChunkMath;
import org.terasology.math.geom.Vector3i;
import org.terasology.engine.network.Client;
import org.terasology.engine.network.ClientComponent;
import org.terasology.engine.network.ColorComponent;
import org.terasology.engine.network.NetMetricSource;
import org.terasology.engine.network.NetworkComponent;
import org.terasology.engine.network.serialization.ServerComponentFieldCheck;
import org.terasology.nui.Color;
import org.terasology.engine.persistence.serializers.EventSerializer;
import org.terasology.engine.persistence.serializers.NetworkEntitySerializer;
import org.terasology.engine.persistence.typeHandling.DeserializationException;
import org.terasology.engine.persistence.typeHandling.SerializationException;
import org.terasology.engine.protobuf.EntityData;
import org.terasology.engine.protobuf.NetData;
import org.terasology.engine.registry.CoreRegistry;
import org.terasology.engine.rendering.world.viewDistance.ViewDistance;
import org.terasology.engine.world.WorldChangeListener;
import org.terasology.engine.world.WorldProvider;
import org.terasology.engine.world.block.Block;
import org.terasology.engine.world.block.BlockComponent;
import org.terasology.engine.world.block.family.BlockFamily;
import org.terasology.engine.world.chunks.Chunk;

import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A remote client.
 *
 */
public class NetClient extends AbstractClient implements WorldChangeListener {
    private static final Logger logger = LoggerFactory.getLogger(NetClient.class);
    private static final float NET_TICK_RATE = 0.05f;

    private final Time time;
    private final NetworkSystemImpl networkSystem;
    private final Channel channel;
    private NetworkEntitySerializer entitySerializer;
    private EventSerializer eventSerializer;
    private EventLibrary eventLibrary;
    private final NetMetricSource metricSource;

    // Relevance
    private final Set<Vector3i> relevantChunks = Sets.newHashSet();
    private final TIntSet netRelevant = new TIntHashSet();

    // Entity replication data
    private final TIntSet netInitial = new TIntHashSet();
    private final TIntSet netDirty = new TIntHashSet();
    private final TIntSet netRemoved = new TIntHashSet();
    private final SetMultimap<Integer, Class<? extends Component>> dirtyComponents = LinkedHashMultimap.create();
    private final SetMultimap<Integer, Class<? extends Component>> addedComponents = LinkedHashMultimap.create();
    private final SetMultimap<Integer, Class<? extends Component>> removedComponents = LinkedHashMultimap.create();

    private String preferredName = "Player";
    private long lastReceivedTime;
    private ViewDistance viewDistance = ViewDistance.NEAR;
    private float chunkSendCounter = 1.0f;

    private final float chunkSendRate = 0.05469f;

    private final PublicIdentityCertificate identity;

    // Outgoing messages
    private final BlockingQueue<NetData.BlockChangeMessage> queuedOutgoingBlockChanges = Queues.newLinkedBlockingQueue();
    private final BlockingQueue<NetData.ExtraDataChangeMessage> queuedOutgoingExtraDataChanges = Queues.newLinkedBlockingQueue();
    private final List<NetData.EventMessage> queuedOutgoingEvents = Lists.newArrayList();
    private final List<BlockFamily> newlyRegisteredFamilies = Lists.newArrayList();

    private final Map<Vector3i, Chunk> readyChunks = Maps.newLinkedHashMap();
    private final Set<Vector3i> invalidatedChunks = Sets.newLinkedHashSet();


    // Incoming messages
    private final BlockingQueue<NetData.NetMessage> queuedIncomingMessage = Queues.newLinkedBlockingQueue();

    // Metrics
    private final AtomicInteger receivedMessages = new AtomicInteger();
    private final AtomicInteger receivedBytes = new AtomicInteger();
    private final AtomicInteger sentMessages = new AtomicInteger();
    private final AtomicInteger sentBytes = new AtomicInteger();
    private Color color;

    /**
     * Sets up a new net client with metrics, time, identity, and a world provider.
     * @param channel
     * @param networkSystem
     * @param identity Publice certificate for the client.
     */
    public NetClient(Channel channel, NetworkSystemImpl networkSystem, PublicIdentityCertificate identity) {
        this.channel = channel;
        metricSource = (NetMetricSource) channel.getPipeline().get(MetricRecordingHandler.NAME);
        this.networkSystem = networkSystem;
        this.time = CoreRegistry.get(Time.class);
        this.identity = identity;
        WorldProvider worldProvider = CoreRegistry.get(WorldProvider.class);
        if (worldProvider != null) {
            worldProvider.registerListener(this);
        }
    }

    @Override
    public String getName() {
        ClientComponent clientComp = getEntity().getComponent(ClientComponent.class);
        if (clientComp != null) {
            DisplayNameComponent displayInfo = clientComp.clientInfo.getComponent(DisplayNameComponent.class);
            if (displayInfo != null) {
                return displayInfo.name;
            }
        }
        return "Unknown Player";
    }

    @Override
    public Color getColor() {
        ClientComponent clientComp = getEntity().getComponent(ClientComponent.class);
        if (clientComp != null) {
            ColorComponent colorComp = clientComp.clientInfo.getComponent(ColorComponent.class);
            if (colorComp != null) {
                return colorComp.color;
            }
        }
        return color;
    }

    @Override
    public String getId() {
        return identity.getId();
    }

    public void setColor(Color color) {
        this.color = color;

        ClientComponent client = getEntity().getComponent(ClientComponent.class);
        if (client != null) {
            ColorComponent colorInfo = client.clientInfo.getComponent(ColorComponent.class);
            if (colorInfo != null) {
                colorInfo.color = color;
                client.clientInfo.saveComponent(colorInfo);
            }
        }

    }

    /**
     * @param preferredName the name the player would like to use.
     */
    public void setPreferredName(String preferredName) {
        this.preferredName = preferredName;
    }

    @Override
    public void disconnect() {
        super.disconnect();

        if (channel.isOpen()) {
            channel.close().awaitUninterruptibly();
        }

        WorldProvider worldProvider = CoreRegistry.get(WorldProvider.class);
        if (worldProvider != null) {
            worldProvider.unregisterListener(this);
        }
    }

    @Override
    public void update(boolean netTick) {
        if (netTick) {
            NetData.NetMessage.Builder message = NetData.NetMessage.newBuilder();
            message.setTime(time.getGameTimeInMs());
            sendRegisteredBlocks(message);
            sendChunkInvalidations(message);
            sendNewChunks(message);
            sendRemovedEntities(message);
            sendInitialEntities(message);
            sendDirtyEntities(message);
            sendEvents(message);
            send(message.build());
        }
        processReceivedMessages();
    }

    private void sendRegisteredBlocks(NetData.NetMessage.Builder message) {
        synchronized (newlyRegisteredFamilies) {
            for (BlockFamily family : newlyRegisteredFamilies) {
                NetData.BlockFamilyRegisteredMessage.Builder blockRegMessage = NetData.BlockFamilyRegisteredMessage.newBuilder();
                for (Block block : family.getBlocks()) {
                    blockRegMessage.addBlockUri(block.getURI().toString());
                    blockRegMessage.addBlockId(block.getId());
                }
                message.addBlockFamilyRegistered(blockRegMessage);
            }
            newlyRegisteredFamilies.clear();
        }
    }

    private void sendNewChunks(NetData.NetMessage.Builder message) {
        if (!readyChunks.isEmpty()) {
            chunkSendCounter += chunkSendRate * NET_TICK_RATE * networkSystem.getBandwidthPerClient();
            if (chunkSendCounter > 1.0f) {
                chunkSendCounter -= 1.0f;
                Vector3i center = new Vector3i();
                LocationComponent loc = getEntity().getComponent(ClientComponent.class).character.getComponent(LocationComponent.class);
                if (loc != null&& !Float.isNaN(loc.getWorldPosition().x)) {
                    center.set(ChunkMath.calcChunkPos(new Vector3i(loc.getWorldPosition(), RoundingMode.HALF_UP)));
                }
                Vector3i pos = null;
                int distance = Integer.MAX_VALUE;
                for (Vector3i chunkPos : readyChunks.keySet()) {
                    int chunkDistance = chunkPos.distanceSquared(center);
                    if (pos == null || chunkDistance < distance) {
                        pos = chunkPos;
                        distance = chunkDistance;
                    }
                }
                Chunk chunk = readyChunks.remove(pos);
                relevantChunks.add(pos);
                message.addChunkInfo(chunk.encode());
            }
        } else {
            chunkSendCounter = 1.0f;
        }
    }

    private void sendChunkInvalidations(NetData.NetMessage.Builder message) {
        Iterator<Vector3i> i = invalidatedChunks.iterator();
        while (i.hasNext()) {
            Vector3i pos = i.next();
            i.remove();
            relevantChunks.remove(pos);
            message.addInvalidateChunk(NetData.InvalidateChunkMessage.newBuilder().setPos(NetMessageUtil.convert(pos)));
        }
        invalidatedChunks.clear();
    }

    public void setNetInitial(int netId) {
        netInitial.add(netId);
    }

    public void setNetRemoved(int netId) {
        if (!netInitial.remove(netId)) {
            netRemoved.add(netId);
        }
        dirtyComponents.keySet().remove(netId);
        addedComponents.keySet().remove(netId);
        removedComponents.keySet().remove(netId);
        netDirty.remove(netId);
        netRelevant.remove(netId);
    }

    public void setComponentAdded(int networkId, Class<? extends Component> component) {
        if (netRelevant.contains(networkId) && !netInitial.contains(networkId)) {
            if (removedComponents.remove(networkId, component)) {
                dirtyComponents.put(networkId, component);
            } else {
                addedComponents.put(networkId, component);
                netDirty.add(networkId);
            }
        }
    }

    public void setComponentRemoved(int networkId, Class<? extends Component> component) {
        if (netRelevant.contains(networkId) && !netInitial.contains(networkId)) {
            if (!addedComponents.remove(networkId, component)) {
                removedComponents.put(networkId, component);
                if (!dirtyComponents.remove(networkId, component)) {
                    netDirty.add(networkId);
                }
            }
        }
    }

    public void setComponentDirty(int netId, Class<? extends Component> componentType) {
        if (netRelevant.contains(netId) && !netInitial.contains(netId) && !addedComponents.get(netId).contains(componentType)) {
            dirtyComponents.put(netId, componentType);
            netDirty.add(netId);
        }
    }

    public void connected(EntityManager entityManager, NetworkEntitySerializer newEntitySerializer,
                          EventSerializer newEventSerializer, EventLibrary newEventLibrary) {
        this.entitySerializer = newEntitySerializer;
        this.eventSerializer = newEventSerializer;
        this.eventLibrary = newEventLibrary;

        createEntity(preferredName, color, entityManager);
    }

    @Override
    public void send(Event event, EntityRef target) {
        try {
            BlockComponent blockComp = target.getComponent(BlockComponent.class);
            if (blockComp != null) {
                if (relevantChunks.contains(ChunkMath.calcChunkPos(blockComp.position))) {
                    queuedOutgoingEvents.add(NetData.EventMessage.newBuilder()
                            .setTargetBlockPos(NetMessageUtil.convert(blockComp.position))
                            .setEvent(eventSerializer.serialize(event)).build());
                }
            } else {
                NetworkComponent networkComponent = target.getComponent(NetworkComponent.class);
                if (networkComponent != null) {
                    if (netRelevant.contains(networkComponent.getNetworkId()) || netInitial.contains(networkComponent.getNetworkId())) {
                        queuedOutgoingEvents.add(NetData.EventMessage.newBuilder()
                                .setTargetId(networkComponent.getNetworkId())
                                .setEvent(eventSerializer.serialize(event)).build());
                    }
                }
            }
        } catch (SerializationException e) {
            logger.error("Failed to serialize event", e);
        }
    }

    @Override
    public ViewDistance getViewDistance() {
        return viewDistance;
    }

    @Override
    public boolean isLocal() {
        return false;
    }

    void send(NetData.NetMessage data) {
        logger.trace("Sending packet with size {}", data.getSerializedSize());
        sentMessages.incrementAndGet();
        sentBytes.addAndGet(data.getSerializedSize());
        channel.write(data);
    }

    @Override
    public void onChunkRelevant(Vector3i pos, Chunk chunk) {
        invalidatedChunks.remove(pos);
        readyChunks.put(pos, chunk);
    }

    @Override
    public void onChunkIrrelevant(Vector3i pos) {
        readyChunks.remove(pos);
        invalidatedChunks.add(pos);
    }

    @Override
    public void onBlockChanged(Vector3i pos, Block newBlock, Block originalBlock) {
        Vector3i chunkPos = ChunkMath.calcChunkPos(pos);
        if (relevantChunks.contains(chunkPos)) {
            queuedOutgoingBlockChanges.add(NetData.BlockChangeMessage.newBuilder()
                    .setPos(NetMessageUtil.convert(pos))
                    .setNewBlock(newBlock.getId())
                    .build());
        }
    }

    @Override
    public void onExtraDataChanged(int i, Vector3i pos, int newData, int oldData) {
        Vector3i chunkPos = ChunkMath.calcChunkPos(pos);
        if (relevantChunks.contains(chunkPos)) {
            queuedOutgoingExtraDataChanges.add(NetData.ExtraDataChangeMessage.newBuilder()
                    .setIndex(i)
                    .setPos(NetMessageUtil.convert(pos))
                    .setNewData(newData)
                    .build());
        }
    }

    private void processReceivedMessages() {
        List<NetData.NetMessage> messages = Lists.newArrayListWithExpectedSize(queuedIncomingMessage.size());
        queuedIncomingMessage.drainTo(messages);
        for (NetData.NetMessage message : messages) {
            if (message.hasTime() && message.getTime() > lastReceivedTime) {
                lastReceivedTime = message.getTime();
            }
            processEntityUpdates(message);
            processEvents(message);

        }
    }

    private void sendEvents(NetData.NetMessage.Builder message) {
        List<NetData.BlockChangeMessage> blockChanges = Lists.newArrayListWithExpectedSize(queuedOutgoingBlockChanges.size());
        queuedOutgoingBlockChanges.drainTo(blockChanges);
        message.addAllBlockChange(blockChanges);
        
        List<NetData.ExtraDataChangeMessage> extraDataChanges = Lists.newArrayListWithExpectedSize(queuedOutgoingExtraDataChanges.size());
        queuedOutgoingExtraDataChanges.drainTo(extraDataChanges);
        message.addAllExtraDataChange(extraDataChanges);

        message.addAllEvent(queuedOutgoingEvents);
        queuedOutgoingEvents.clear();
    }

    private void processEntityUpdates(NetData.NetMessage message) {
        for (NetData.UpdateEntityMessage updateMessage : message.getUpdateEntityList()) {

            EntityRef currentEntity = networkSystem.getEntity(updateMessage.getNetId());
            if (networkSystem.getOwner(currentEntity) == this) {
                entitySerializer.deserializeOnto(currentEntity, updateMessage.getEntity(), new ServerComponentFieldCheck(false, true));
            }
        }
    }

    private void sendDirtyEntities(NetData.NetMessage.Builder message) {
        TIntIterator dirtyIterator = netDirty.iterator();
        while (dirtyIterator.hasNext()) {
            int netId = dirtyIterator.next();
            EntityRef entity = networkSystem.getEntity(netId);
            if (!entity.exists()) {
                logger.error("Sending non-existent entity update for netId {}", netId);
            }
            boolean isOwner = networkSystem.getOwner(entity) == this;
            EntityData.PackedEntity entityData = entitySerializer.serialize(entity, addedComponents.get(netId), dirtyComponents.get(netId), removedComponents.get(netId),
                    new ServerComponentFieldCheck(isOwner, false));
            if (entityData != null) {
                message.addUpdateEntity(NetData.UpdateEntityMessage.newBuilder().setEntity(entityData).setNetId(netId));
            }
        }
        netDirty.clear();
        addedComponents.clear();
        removedComponents.clear();
        dirtyComponents.clear();
    }

    private void sendRemovedEntities(NetData.NetMessage.Builder message) {
        TIntIterator initialIterator = netRemoved.iterator();
        while (initialIterator.hasNext()) {
            message.addRemoveEntity(NetData.RemoveEntityMessage.newBuilder().setNetId(initialIterator.next()));
        }
        netRemoved.clear();
    }

    private void sendInitialEntities(NetData.NetMessage.Builder message) {
        int[] initial = netInitial.toArray();
        netInitial.clear();
        Arrays.sort(initial);
        for (int netId : initial) {
            netRelevant.add(netId);
            EntityRef entity = networkSystem.getEntity(netId);
            if (!entity.hasComponent(NetworkComponent.class)) {
                logger.error("Sending net entity with no network component: {} - {}", netId, entity);
                continue;
            }
            // Note: Send owner->server fields on initial create
            Client owner = networkSystem.getOwner(entity);
            EntityData.PackedEntity entityData = entitySerializer.serialize(entity, true, new ServerComponentFieldCheck(owner == this, true)).build();
            NetData.CreateEntityMessage.Builder createMessage = NetData.CreateEntityMessage.newBuilder().setEntity(entityData);
            BlockComponent blockComponent = entity.getComponent(BlockComponent.class);
            if (blockComponent != null) {
                createMessage.setBlockPos(NetMessageUtil.convert(blockComponent.position));
            }
            message.addCreateEntity(createMessage);
        }

    }

    private void processEvents(NetData.NetMessage message) {
        boolean lagCompensated = false;
        PredictionSystem predictionSystem = CoreRegistry.get(PredictionSystem.class);
        for (NetData.EventMessage eventMessage : message.getEventList()) {
            try {
                Event event = eventSerializer.deserialize(eventMessage.getEvent());
                EventMetadata<?> metadata = eventLibrary.getMetadata(event.getClass());
                if (metadata.getNetworkEventType() != NetworkEventType.SERVER) {
                    logger.warn("Received non-server event '{}' from client '{}'", metadata, getName());
                    continue;
                }
                if (!lagCompensated && metadata.isLagCompensated()) {
                    if (predictionSystem != null) {
                        predictionSystem.lagCompensate(getEntity(), lastReceivedTime);
                    }
                    lagCompensated = true;
                }
                EntityRef target = EntityRef.NULL;
                if (eventMessage.hasTargetId()) {
                    target = networkSystem.getEntity(eventMessage.getTargetId());
                }
                if (target.exists()) {
                    if (Objects.equal(networkSystem.getOwner(target), this)) {
                        target.send(event);
                    } else {
                        logger.warn("Received event {} for non-owned entity {} from {}", event, target, this);
                    }
                }
            } catch (DeserializationException e) {
                logger.error("Failed to deserialize event", e);
            } catch (RuntimeException e) {
                logger.error("Error processing event", e);
            }
        }
        if (lagCompensated && predictionSystem != null) {
            predictionSystem.restoreToPresent();
        }
    }

    public void messageReceived(NetData.NetMessage message) {
        int serializedSize = message.getSerializedSize();
        receivedBytes.addAndGet(serializedSize);
        receivedMessages.incrementAndGet();
        queuedIncomingMessage.offer(message);
    }

    public NetMetricSource getMetrics() {
        return metricSource;
    }

    @Override
    public void setViewDistanceMode(ViewDistance distanceMode) {
        this.viewDistance = distanceMode;
    }

    public void blockFamilyRegistered(BlockFamily family) {
        synchronized (newlyRegisteredFamilies) {
            newlyRegisteredFamilies.add(family);
        }
    }
}