/**
 * Pure 3D projection, vector mathematics, and geometry utilities for Accelerometer 3D Plots.
 * Operates without external 3D libraries for maximum performance, zero bundle bloat,
 * and reliable sub-millisecond execution.
 */

export interface Point3D {
  x: number;
  y: number;
  z: number;
}

export interface Point2D {
  x: number;
  y: number;
  depth: number;
}

export interface Camera3D {
  yawDeg: number; // Rotation around vertical Z axis (azimuth, in degrees)
  pitchDeg: number; // Elevation angle above XY plane (pitch, in degrees)
  zoom: number; // Zoom multiplier (1.0 = normal)
  center: { x: number; y: number };
  scale: number; // Pixels per m/s²
  perspectiveDistance?: number;
}

export type ViewPreset = 'iso' | 'top' | 'front' | 'side';

export const STANDARD_GRAVITY = 9.80665; // m/s²

/**
 * Clamps a number between min and max.
 */
export function clamp(val: number, min: number, max: number): number {
  if (isNaN(val)) return min;
  return Math.max(min, Math.min(max, val));
}

/**
 * Calculates Euclidean 3D vector magnitude: sqrt(x² + y² + z²).
 * Safely handles NaN, null, and non-finite inputs.
 */
export function calculateMagnitude(x: number, y: number, z: number): number {
  const safeX = Number.isFinite(x) ? x : 0;
  const safeY = Number.isFinite(y) ? y : 0;
  const safeZ = Number.isFinite(z) ? z : 0;
  return Math.sqrt(safeX * safeX + safeY * safeY + safeZ * safeZ);
}

/**
 * Converts acceleration magnitude (m/s²) to G-force (1G ≈ 9.80665 m/s²).
 */
export function calculateGForce(magnitudeMs2: number): number {
  if (!Number.isFinite(magnitudeMs2) || magnitudeMs2 < 0) return 0;
  return magnitudeMs2 / STANDARD_GRAVITY;
}

/**
 * Maps G-force value to a color string adhering to AeroGlass styling:
 * - Cyan/Emerald (< 1.25G): Normal nominal gravity
 * - Amber (1.25G - 2.0G): Elevated motion / G-force
 * - Rose/Red (> 2.0G): High impact / aggressive acceleration
 */
export function getGForceColor(gForce: number): string {
  if (gForce > 2.0) return '#f43f5e'; // Rose 500
  if (gForce > 1.25) return '#f59e0b'; // Amber 500
  return '#06b6d4'; // Cyan 500
}

/**
 * Projects a 3D point (in m/s² or custom units) into 2D screen coordinates using
 * perspective-aware isometric projection.
 */
export function project3DTo2D(point: Point3D, camera: Camera3D): Point2D {
  const px = Number.isFinite(point.x) ? point.x : 0;
  const py = Number.isFinite(point.y) ? point.y : 0;
  const pz = Number.isFinite(point.z) ? point.z : 0;

  // Convert yaw and pitch to radians
  const yawRad = ((camera.yawDeg % 360) * Math.PI) / 180;
  // Clamp pitch between -89.9 and 89.9 to avoid gimbal singularities
  const pitchClamped = clamp(camera.pitchDeg, -89.9, 89.9);
  const pitchRad = (pitchClamped * Math.PI) / 180;

  // Step 1: Rotate around vertical Z axis (yaw)
  const cosYaw = Math.cos(yawRad);
  const sinYaw = Math.sin(yawRad);
  const x1 = px * cosYaw - py * sinYaw;
  const y1 = px * sinYaw + py * cosYaw;
  const z1 = pz;

  // Step 2: Rotate around horizontal X axis (pitch / elevation)
  const cosPitch = Math.cos(pitchRad);
  const sinPitch = Math.sin(pitchRad);
  const x2 = x1;
  const y2 = y1 * cosPitch - z1 * sinPitch;
  const z2 = y1 * sinPitch + z1 * cosPitch;

  // Step 3: Perspective scaling
  const D = camera.perspectiveDistance ?? 600;
  const zoom = Math.max(0.1, camera.zoom);
  const scale = camera.scale * zoom;
  // Dampen perspective denominator to prevent extreme warping
  const persp = D / Math.max(30, D + y2 * scale * 0.25);

  const screenX = camera.center.x + x2 * scale * persp;
  const screenY = camera.center.y - z2 * scale * persp;

  return {
    x: Number.isFinite(screenX) ? screenX : camera.center.x,
    y: Number.isFinite(screenY) ? screenY : camera.center.y,
    depth: y2,
  };
}

/**
 * Generates 3D grid line segments for the ground plane (XY at z = 0).
 */
export function generateGroundGrid(
  extent: number = 20,
  step: number = 5
): Array<{ p1: Point3D; p2: Point3D }> {
  const lines: Array<{ p1: Point3D; p2: Point3D }> = [];
  const safeExtent = Math.max(1, Math.abs(extent));
  const safeStep = Math.max(0.5, Math.abs(step));

  for (let c = -safeExtent; c <= safeExtent; c += safeStep) {
    // Parallel to Y axis
    lines.push({
      p1: { x: c, y: -safeExtent, z: 0 },
      p2: { x: c, y: safeExtent, z: 0 },
    });
    // Parallel to X axis
    lines.push({
      p1: { x: -safeExtent, y: c, z: 0 },
      p2: { x: safeExtent, y: c, z: 0 },
    });
  }

  return lines;
}

/**
 * Generates 3D points forming a circle in a given orthogonal plane (XY, XZ, or YZ).
 * Useful for the 1G gravity shell, reference rings, and orientation planes.
 */
export function generateCircle3D(
  radius: number,
  plane: 'XY' | 'XZ' | 'YZ',
  segments: number = 48,
  center: Point3D = { x: 0, y: 0, z: 0 }
): Point3D[] {
  const points: Point3D[] = [];
  const numSegments = Math.max(8, segments);
  const r = Math.max(0, radius);

  for (let i = 0; i <= numSegments; i++) {
    const angle = (i * 2 * Math.PI) / numSegments;
    const cos = Math.cos(angle);
    const sin = Math.sin(angle);

    if (plane === 'XY') {
      points.push({
        x: center.x + r * cos,
        y: center.y + r * sin,
        z: center.z,
      });
    } else if (plane === 'XZ') {
      points.push({
        x: center.x + r * cos,
        y: center.y,
        z: center.z + r * sin,
      });
    } else {
      // YZ plane
      points.push({
        x: center.x,
        y: center.y + r * cos,
        z: center.z + r * sin,
      });
    }
  }

  return points;
}

/**
 * Camera angle presets for fast switching.
 */
export function getPresetCamera(preset: ViewPreset): { yawDeg: number; pitchDeg: number } {
  switch (preset) {
    case 'top':
      return { yawDeg: 0, pitchDeg: 89 };
    case 'front':
      return { yawDeg: 0, pitchDeg: 0 };
    case 'side':
      return { yawDeg: 90, pitchDeg: 0 };
    case 'iso':
    default:
      return { yawDeg: -40, pitchDeg: 26 };
  }
}
