// Copyright 2020 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.cameras;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import static org.lwjgl.opengl.GL11.GL_PROJECTION;
import static org.lwjgl.opengl.GL11.glMatrixMode;

/**
 * Simple default camera.
 *
 */
public class OrthographicCamera extends Camera {

    private final float top;
    private final float bottom;
    private final float left;
    private final float right;

    public OrthographicCamera(float left, float right, float top, float bottom) {
        super();

        this.top = top;
        this.bottom = bottom;
        this.left = left;
        this.right = right;
        this.zNear = -1000.0f;
        this.zFar = 1000.0f;
    }

    @Override
    public boolean isBobbingAllowed() {
        return false;
    }

    @Override
    public void loadProjectionMatrix() {
        glMatrixMode(GL_PROJECTION);
        GL11.glLoadMatrix(getProjectionMatrix().get(BufferUtils.createFloatBuffer(16)));
        glMatrixMode(GL11.GL_MODELVIEW);
    }

    @Override
    public void loadModelViewMatrix() {
        glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadMatrix(getViewMatrix().get(BufferUtils.createFloatBuffer(16)));
    }

    @Override
    public void loadNormalizedModelViewMatrix() {
        glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadMatrix(getNormViewMatrix().get(BufferUtils.createFloatBuffer(16)));
    }

    @Override
    public void update(float deltaT) {
        super.update(deltaT);
        updateMatrices();
    }

    @Override
    public void updateMatrices() {
        updateMatrices(activeFov);
    }

    @Override
    public void updateMatrices(float fov) {
        prevViewProjectionMatrix.set(viewProjectionMatrix);

        // Nothing to do...
        if (cachedPosition.equals(getPosition()) && cachedViewigDirection.equals(viewingDirection)
            && cachedZFar == zFar && cachedZNear == zNear) {
            return;
        }

        projectionMatrix.setOrtho(left, right, bottom, top, zNear, zFar);
        viewMatrix.setLookAt(0f, 0.0f, 0f, viewingDirection.x, viewingDirection.y, viewingDirection.z, up.x, up.y,
            up.z);
        normViewMatrix.setLookAt(0f, 0f, 0f, viewingDirection.x, viewingDirection.y, viewingDirection.z, up.x, up.y,
            up.z);

        projectionMatrix.mul(viewMatrix, viewProjectionMatrix);
        viewProjectionMatrix.invert(inverseViewProjectionMatrix);

        // Used for dirty checks
        cachedPosition.set(getPosition());
        cachedViewigDirection.set(viewingDirection);
        cachedZFar = zFar;
        cachedZNear = zNear;

        updateFrustum();
    }

    @Override
    public ViewFrustum getViewFrustumReflected() {
        throw new RuntimeException("Not yet implemented!");
    }
}