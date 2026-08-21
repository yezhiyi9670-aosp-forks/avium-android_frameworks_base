/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.display.color;

import static com.android.server.display.color.DisplayTransformManager.LEVEL_COLOR_MATRIX_EVEN_DIMMER;

import android.content.Context;
import android.hardware.display.ColorDisplayManager;
import android.opengl.Matrix;
import android.util.Slog;

import com.android.internal.R;

import java.io.PrintWriter;
import java.util.Arrays;

/**
 * Control the color transform for bright color reduction.
 */
public class EvenDimmerTintController extends TintController {

    private final float[] mMatrix = new float[16];
    private final float[] mCoefficients = new float[3];
    private final float[] mLinearCoefficients = new float[3];

    private boolean hasSetUp = false;
    private int mStrength;

    @Override
    public void setUp(Context context, boolean needsLinear) {
        final String[] coefficients = context.getResources().getStringArray(
                needsLinear ? R.array.config_reduceBrightColorsCoefficients
                        : R.array.config_reduceBrightColorsCoefficientsNonlinear);
        for (int i = 0; i < 3 && i < coefficients.length; i++) {
            mCoefficients[i] = Float.parseFloat(coefficients[i]);
        }
        final String[] linearCoefficients = context.getResources().getStringArray(
                R.array.config_reduceBrightColorsCoefficients);
        for (int i = 0; i < 3 && i < linearCoefficients.length; i++) {
            mLinearCoefficients[i] = Float.parseFloat(linearCoefficients[i]);
        }
        hasSetUp = true;
    }

    @Override
    public float[] getMatrix() {
        return isActivated() ? Arrays.copyOf(mMatrix, mMatrix.length)
                : ColorDisplayService.MATRIX_IDENTITY;
    }

    @Override
    public void setMatrix(int strengthLevel) {
        // Clamp to valid range.
        if (strengthLevel < 0) {
            strengthLevel = 0;
        } else if (strengthLevel > 100) {
            strengthLevel = 100;
        }
        Slog.d(ColorDisplayService.TAG, "Setting even dimmer dimming level: " + strengthLevel);
        mStrength = strengthLevel;

        Matrix.setIdentityM(mMatrix, 0);

        // All three (r,g,b) components are equal and calculated with the same formula.
        final float componentValue = computeComponentValue(strengthLevel);
        mMatrix[0] = componentValue;
        mMatrix[5] = componentValue;
        mMatrix[10] = componentValue;
    }

    private float clamp(float value) {
        if (value > 1f) {
            return 1f;
        } else if (value < 0f) {
            return 0f;
        }
        return value;
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println("    mStrength = " + mStrength);
    }

    @Override
    public int getLevel() {
        return LEVEL_COLOR_MATRIX_EVEN_DIMMER;
    }

    @Override
    public boolean isAvailable(Context context) {
        return ColorDisplayManager.isColorTransformAccelerated(context);
    }

    @Override
    public void setActivated(Boolean isActivated) {
        super.setActivated(isActivated);
        Slog.i(ColorDisplayService.TAG, (isActivated != null && isActivated)
                ? "Turning on even dimmer dimming" : "Turning off even dimmer dimming");
    }

    public int getStrength() {
        return mStrength;
    }

    /** Returns the offset factor at Ymax. */
    public float getOffsetFactor() {
        // Strength terms drop out as strength --> 1, leaving the coefficients.
        float offset = mLinearCoefficients[0] + mLinearCoefficients[1] + mLinearCoefficients[2];
        // ColorDisplayServiceInternal#fetchEvenDimmerSpline determines if the tint controller
        // is not ready yet by checking if the return value is zero.
        // So when not set up yet, return zero as-is, and when set up, always return non zero
        // even if the curve reaches zero.
        if(hasSetUp && offset < 1e-5f) {
            return 1e-5f;
        }
        return offset;
    }

    /**
     * Returns the effective brightness (in nits), which has been adjusted to account for the effect
     * of the bright color reduction.
     */
    public float getAdjustedBrightness(float nits) {
        float component = computeLinearComponentValue(mStrength);
        // ColorDisplayServiceInternal#fetchEvenDimmerSpline determines if the tint controller
        // is not ready yet by checking if the return value is zero.
        // So when not set up yet, return zero as-is, and when set up, always return non zero
        // even if the curve reaches zero.
        if(hasSetUp && component < 1e-5f) {
            return 1e-5f * nits;
        }
        return component * nits;
    }

    /**
     * Returns the effective brightness (in nits), which has been adjusted to account for the effect
     * of the bright color reduction.
     */
    public float getAdjustedNitsForStrength(float nits, int strength) {
        float component = computeLinearComponentValue(strength);
        // ColorDisplayServiceInternal#fetchEvenDimmerSpline determines if the tint controller
        // is not ready yet by checking if the return value is zero.
        // So when not set up yet, return zero as-is, and when set up, always return non zero
        // even if the curve reaches zero.
        if(hasSetUp && component < 1e-5f) {
            return 1e-5f * nits;
        }
        return component * nits;
    }

    private float computeComponentValue(int strengthLevel) {
        final float percentageStrength = strengthLevel / 100f;
        final float squaredPercentageStrength = percentageStrength * percentageStrength;
        return clamp(
                squaredPercentageStrength * mCoefficients[0] + percentageStrength * mCoefficients[1]
                        + mCoefficients[2]);
    }

    private float computeLinearComponentValue(int strengthLevel) {
        final float percentageStrength = strengthLevel / 100f;
        final float squaredPercentageStrength = percentageStrength * percentageStrength;
        return clamp(
                squaredPercentageStrength * mLinearCoefficients[0] + percentageStrength * mLinearCoefficients[1]
                        + mLinearCoefficients[2]);
    }
}
