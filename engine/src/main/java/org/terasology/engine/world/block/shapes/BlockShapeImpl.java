// Copyright 2020 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.world.block.shapes;

import com.google.common.collect.Maps;
import org.terasology.engine.math.Pitch;
import org.terasology.engine.math.Roll;
import org.terasology.engine.math.Rotation;
import org.terasology.engine.math.Side;
import org.terasology.engine.math.Yaw;
import org.terasology.engine.physics.shapes.CollisionShape;
import org.terasology.engine.utilities.collection.EnumBooleanMap;
import org.terasology.engine.world.block.BlockPart;
import org.terasology.gestalt.assets.AssetType;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.math.geom.Vector3f;

import java.util.EnumMap;
import java.util.Map;

/**
 *
 */
public class BlockShapeImpl extends BlockShape {

    private String displayName;
    private final EnumMap<BlockPart, BlockMeshPart> meshParts = Maps.newEnumMap(BlockPart.class);
    private final EnumBooleanMap<Side> fullSide = new EnumBooleanMap<>(Side.class);
    private CollisionShape baseCollisionShape;
    private final Vector3f baseCollisionOffset = new Vector3f();
    private boolean yawSymmetric;
    private boolean pitchSymmetric;
    private boolean rollSymmetric;

    private final Map<Rotation, CollisionShape> collisionShape = Maps.newHashMap();

    public BlockShapeImpl(ResourceUrn urn, AssetType<?, BlockShapeData> assetType, BlockShapeData data) {
        super(urn, assetType);
        reload(data);
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public BlockMeshPart getMeshPart(BlockPart part) {
        return meshParts.get(part);
    }

    @Override
    public boolean isBlockingSide(Side side) {
        return fullSide.get(side);
    }

    @Override
    protected void doReload(BlockShapeData data) {
        collisionShape.clear();
        displayName = data.getDisplayName();
        for (BlockPart part : BlockPart.values()) {
            this.meshParts.put(part, data.getMeshPart(part));
        }
        for (Side side : Side.getAllSides()) {
            this.fullSide.put(side, data.isBlockingSide(side));
        }
        this.baseCollisionShape = data.getCollisionShape();
        this.baseCollisionOffset.set(data.getCollisionOffset());
        collisionShape.put(Rotation.none(), baseCollisionShape);

        yawSymmetric = data.isYawSymmetric();
        pitchSymmetric = data.isPitchSymmetric();
        rollSymmetric = data.isRollSymmetric();
    }

    @Override
    public CollisionShape getCollisionShape(Rotation rot) {
        Rotation simplifiedRot = applySymmetry(rot);
        CollisionShape result = collisionShape.get(simplifiedRot);
        if (result == null && baseCollisionShape != null) {
            result = baseCollisionShape.rotate(simplifiedRot.getQuat4f());
            collisionShape.put(simplifiedRot, result);
        }
        return result;
    }

    @Override
    public Vector3f getCollisionOffset(Rotation rot) {
        Rotation simplifiedRot = applySymmetry(rot);
        if (simplifiedRot.equals(Rotation.none())) {
            return new Vector3f(baseCollisionOffset);
        }
        return simplifiedRot.getQuat4f().rotate(baseCollisionOffset, new Vector3f());
    }

    @Override
    public boolean isCollisionYawSymmetric() {
        return yawSymmetric;
    }

    private Rotation applySymmetry(Rotation rot) {
        return Rotation.rotate(yawSymmetric ? Yaw.NONE : rot.getYaw(), pitchSymmetric ? Pitch.NONE : rot.getPitch(),
                rollSymmetric ? Roll.NONE : rot.getRoll());
    }

}
