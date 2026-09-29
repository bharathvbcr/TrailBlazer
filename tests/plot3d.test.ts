import test, { describe } from 'node:test';
import assert from 'node:assert';
import {
  calculateMagnitude,
  calculateGForce,
  getGForceColor,
  project3DTo2D,
  generateGroundGrid,
  generateCircle3D,
  getPresetCamera,
  clamp,
  STANDARD_GRAVITY,
} from '../src/utils/plot3d.ts';
import type { Camera3D } from '../src/utils/plot3d.ts';

describe('Accelerometer 3D Plot Math & Utilities', () => {
  test('calculateMagnitude handles normal, zero, and non-finite values safely', () => {
    // 3-4-5 triangle in 3D: (0, 3, 4) -> 5
    assert.strictEqual(calculateMagnitude(0, 3, 4), 5);
    // Standard resting gravity vector: (0, 0, 9.80665)
    assert.strictEqual(calculateMagnitude(0, 0, STANDARD_GRAVITY), STANDARD_GRAVITY);
    // Origin
    assert.strictEqual(calculateMagnitude(0, 0, 0), 0);
    // Non-finite values should not crash or return NaN
    assert.strictEqual(calculateMagnitude(NaN, 0, 0), 0);
    assert.strictEqual(calculateMagnitude(Infinity, 0, 0), 0);
    assert.strictEqual(calculateMagnitude(-3, 0, 4), 5);
  });

  test('calculateGForce converts acceleration correctly', () => {
    assert.strictEqual(calculateGForce(STANDARD_GRAVITY), 1.0);
    assert.strictEqual(calculateGForce(0), 0.0);
    assert.strictEqual(calculateGForce(STANDARD_GRAVITY * 2), 2.0);
    assert.strictEqual(calculateGForce(NaN), 0.0);
    assert.strictEqual(calculateGForce(-10), 0.0);
  });

  test('getGForceColor maps colors across severity thresholds', () => {
    assert.strictEqual(getGForceColor(1.0), '#06b6d4'); // Nominal (<1.25)
    assert.strictEqual(getGForceColor(1.2), '#06b6d4');
    assert.strictEqual(getGForceColor(1.5), '#f59e0b'); // Warning (1.25 - 2.0)
    assert.strictEqual(getGForceColor(2.5), '#f43f5e'); // Critical (>2.0)
  });

  test('project3DTo2D projects origin (0,0,0) directly to center', () => {
    const camera: Camera3D = {
      yawDeg: 0,
      pitchDeg: 0,
      zoom: 1,
      center: { x: 200, y: 150 },
      scale: 10,
    };
    const projected = project3DTo2D({ x: 0, y: 0, z: 0 }, camera);
    assert.strictEqual(Math.round(projected.x), 200);
    assert.strictEqual(Math.round(projected.y), 150);
  });

  test('project3DTo2D projects positive Z upwards (decreasing screen Y)', () => {
    const camera: Camera3D = {
      yawDeg: 0,
      pitchDeg: 0,
      zoom: 1,
      center: { x: 200, y: 150 },
      scale: 10,
    };
    const projected = project3DTo2D({ x: 0, y: 0, z: 10 }, camera);
    // In screen coordinates, up is smaller Y
    assert.ok(projected.y < 150, `Expected Y < 150, got ${projected.y}`);
    assert.strictEqual(Math.round(projected.x), 200);
  });

  test('project3DTo2D handles gimbal bounds without NaN', () => {
    const camera: Camera3D = {
      yawDeg: 3600,
      pitchDeg: 120, // Should be clamped
      zoom: 1,
      center: { x: 100, y: 100 },
      scale: 10,
    };
    const projected = project3DTo2D({ x: 5, y: 5, z: 5 }, camera);
    assert.ok(Number.isFinite(projected.x));
    assert.ok(Number.isFinite(projected.y));
    assert.ok(Number.isFinite(projected.depth));
  });

  test('generateGroundGrid produces valid segments', () => {
    const grid = generateGroundGrid(10, 5);
    assert.ok(grid.length > 0);
    // All ground plane points must have z = 0
    for (const line of grid) {
      assert.strictEqual(line.p1.z, 0);
      assert.strictEqual(line.p2.z, 0);
    }
  });

  test('generateCircle3D generates closed circular paths', () => {
    const circleXY = generateCircle3D(9.8, 'XY', 16);
    assert.strictEqual(circleXY.length, 17); // 16 segments + 1 closing point
    assert.strictEqual(circleXY[0].z, 0);

    const circleXZ = generateCircle3D(9.8, 'XZ', 16);
    assert.strictEqual(circleXZ[0].y, 0);

    const circleYZ = generateCircle3D(9.8, 'YZ', 16);
    assert.strictEqual(circleYZ[0].x, 0);
  });

  test('getPresetCamera returns expected presets', () => {
    const iso = getPresetCamera('iso');
    assert.strictEqual(iso.yawDeg, -40);
    assert.strictEqual(iso.pitchDeg, 26);

    const top = getPresetCamera('top');
    assert.strictEqual(top.yawDeg, 0);
    assert.strictEqual(top.pitchDeg, 89);
  });

  test('clamp bounds values properly', () => {
    assert.strictEqual(clamp(5, 0, 10), 5);
    assert.strictEqual(clamp(-5, 0, 10), 0);
    assert.strictEqual(clamp(15, 0, 10), 10);
    assert.strictEqual(clamp(NaN, 0, 10), 0);
  });
});
