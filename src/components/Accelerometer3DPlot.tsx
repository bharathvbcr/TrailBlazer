import React, { useRef, useEffect, useState, useCallback, useMemo } from 'react';
import {
  Point3D,
  Camera3D,
  project3DTo2D,
  generateGroundGrid,
  generateCircle3D,
  getPresetCamera,
  calculateMagnitude,
  calculateGForce,
  getGForceColor,
  clamp,
  STANDARD_GRAVITY,
  ViewPreset,
} from '../utils/plot3d';
import {
  RotateCcw,
  ZoomIn,
  ZoomOut,
  Orbit,
  Layers,
  Box,
  Eye,
} from 'lucide-react';

interface Props {
  accelX: number;
  accelY: number;
  accelZ: number;
  gForce?: number;
  peakG?: number | null;
  className?: string;
  height?: number;
  onResetPeak?: () => void;
}

const MAX_TRAIL_LENGTH = 50;

export const Accelerometer3DPlot: React.FC<Props> = ({
  accelX,
  accelY,
  accelZ,
  gForce: propGForce,
  peakG = null,
  className = '',
  height = 280,
  onResetPeak,
}) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);

  // Camera orientation & zoom state
  const [yawDeg, setYawDeg] = useState<number>(-40);
  const [pitchDeg, setPitchDeg] = useState<number>(26);
  const [zoom, setZoom] = useState<number>(1.0);
  const [autoRotate, setAutoRotate] = useState<boolean>(false);
  const [showTrail, setShowTrail] = useState<boolean>(true);
  const [show1GSphere, setShow1GSphere] = useState<boolean>(true);
  const [activePreset, setActivePreset] = useState<ViewPreset | 'custom'>('iso');

  // Interactive drag state
  const isDraggingRef = useRef<boolean>(false);
  const pointerStartRef = useRef<{ x: number; y: number; yaw: number; pitch: number }>({
    x: 0,
    y: 0,
    yaw: -40,
    pitch: 26,
  });

  // Recent 3D acceleration points for trajectory trail
  const trailRef = useRef<Point3D[]>([]);

  // Calculate live magnitude and G-force
  const magnitude = useMemo(
    () => calculateMagnitude(accelX, accelY, accelZ),
    [accelX, accelY, accelZ]
  );
  const gForce = propGForce ?? calculateGForce(magnitude);
  const gColor = getGForceColor(gForce);

  // Append to trail on new sensor input
  useEffect(() => {
    if (!showTrail) return;
    const trail = trailRef.current;
    trail.push({ x: accelX, y: accelY, z: accelZ });
    if (trail.length > MAX_TRAIL_LENGTH) {
      trail.shift();
    }
  }, [accelX, accelY, accelZ, showTrail]);

  // Clear trail handler
  const handleClearTrail = useCallback(() => {
    trailRef.current = [];
  }, []);

  // View preset handler
  const handleSelectPreset = useCallback((preset: ViewPreset) => {
    const angles = getPresetCamera(preset);
    setYawDeg(angles.yawDeg);
    setPitchDeg(angles.pitchDeg);
    setActivePreset(preset);
  }, []);

  // Reset to default isometric
  const handleResetCamera = useCallback(() => {
    handleSelectPreset('iso');
    setZoom(1.0);
  }, [handleSelectPreset]);

  // Pointer drag interactions for 3D orbiting
  const handlePointerDown = (e: React.PointerEvent) => {
    isDraggingRef.current = true;
    pointerStartRef.current = {
      x: e.clientX,
      y: e.clientY,
      yaw: yawDeg,
      pitch: pitchDeg,
    };
    (e.target as HTMLElement).setPointerCapture(e.pointerId);
  };

  const handlePointerMove = (e: React.PointerEvent) => {
    if (!isDraggingRef.current) return;
    const dx = e.clientX - pointerStartRef.current.x;
    const dy = e.clientY - pointerStartRef.current.y;

    // Orbit sensitivity
    const newYaw = (pointerStartRef.current.yaw + dx * 0.6) % 360;
    const newPitch = clamp(pointerStartRef.current.pitch - dy * 0.5, -85, 85);

    setYawDeg(newYaw);
    setPitchDeg(newPitch);
    setActivePreset('custom');
  };

  const handlePointerUp = (e: React.PointerEvent) => {
    isDraggingRef.current = false;
    try {
      (e.target as HTMLElement).releasePointerCapture(e.pointerId);
    } catch {
      // ignore
    }
  };

  // Mouse wheel zoom
  const handleWheel = (e: React.WheelEvent) => {
    e.preventDefault();
    setZoom((prev) => clamp(prev - e.deltaY * 0.0015, 0.4, 3.0));
  };

  // Auto-rotate tick loop
  useEffect(() => {
    if (!autoRotate) return;
    let animId: number;
    const loop = () => {
      if (!isDraggingRef.current) {
        setYawDeg((prev) => (prev + 0.35) % 360);
      }
      animId = requestAnimationFrame(loop);
    };
    animId = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(animId);
  }, [autoRotate]);

  // Pre-generate static 3D reference geometry
  const groundGrid = useMemo(() => generateGroundGrid(20, 5), []);
  const ring1G_XY = useMemo(() => generateCircle3D(STANDARD_GRAVITY, 'XY', 48), []);
  const ring1G_XZ = useMemo(() => generateCircle3D(STANDARD_GRAVITY, 'XZ', 48), []);
  const ring1G_YZ = useMemo(() => generateCircle3D(STANDARD_GRAVITY, 'YZ', 48), []);
  const ring5_XY = useMemo(() => generateCircle3D(5, 'XY', 32), []);
  const ring15_XY = useMemo(() => generateCircle3D(15, 'XY', 32), []);

  // Main 3D Canvas Rendering Loop
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const width = canvas.clientWidth || 340;
    const canvasHeight = height;
    const dpr = window.devicePixelRatio || 1;

    canvas.width = width * dpr;
    canvas.height = canvasHeight * dpr;
    ctx.scale(dpr, dpr);

    // Camera setup
    // Base scale: fit ±20 m/s² into viewport comfortably
    const baseScale = Math.min(width, canvasHeight) / 52;
    const camera: Camera3D = {
      yawDeg,
      pitchDeg,
      zoom,
      center: { x: width / 2, y: canvasHeight / 2 + 10 },
      scale: baseScale,
      perspectiveDistance: 550,
    };

    // Clear background with soft gradient
    ctx.clearRect(0, 0, width, canvasHeight);

    // 1. Draw subtle ambient ground plane glow
    const centerScreen = project3DTo2D({ x: 0, y: 0, z: 0 }, camera);
    const bgGrad = ctx.createRadialGradient(
      centerScreen.x,
      centerScreen.y,
      5,
      centerScreen.x,
      centerScreen.y,
      Math.max(width, canvasHeight) * 0.7
    );
    bgGrad.addColorStop(0, 'rgba(6, 182, 212, 0.04)');
    bgGrad.addColorStop(0.6, 'rgba(15, 23, 42, 0.01)');
    bgGrad.addColorStop(1, 'rgba(0, 0, 0, 0)');
    ctx.fillStyle = bgGrad;
    ctx.fillRect(0, 0, width, canvasHeight);

    // 2. Draw ground grid (XY plane at z = 0)
    ctx.lineWidth = 1;
    ctx.strokeStyle = 'rgba(255, 255, 255, 0.06)';
    ctx.beginPath();
    for (const line of groundGrid) {
      const p1 = project3DTo2D(line.p1, camera);
      const p2 = project3DTo2D(line.p2, camera);
      ctx.moveTo(p1.x, p1.y);
      ctx.lineTo(p2.x, p2.y);
    }
    ctx.stroke();

    // 3. Draw concentric ground reference rings (5, 15 m/s²)
    const drawRing = (points: Point3D[], color: string, dashed: boolean = false) => {
      ctx.strokeStyle = color;
      ctx.lineWidth = 1;
      if (dashed) {
        ctx.setLineDash([3, 4]);
      } else {
        ctx.setLineDash([]);
      }
      ctx.beginPath();
      for (let i = 0; i < points.length; i++) {
        const pt = project3DTo2D(points[i], camera);
        if (i === 0) ctx.moveTo(pt.x, pt.y);
        else ctx.lineTo(pt.x, pt.y);
      }
      ctx.stroke();
      ctx.setLineDash([]);
    };

    drawRing(ring5_XY, 'rgba(255, 255, 255, 0.08)', true);
    drawRing(ring15_XY, 'rgba(255, 255, 255, 0.08)', true);

    // 4. Draw 1G Earth Gravity Reference Shell (XY, XZ, YZ rings)
    if (show1GSphere) {
      // Ground 1G circle
      drawRing(ring1G_XY, 'rgba(6, 182, 212, 0.28)', true);
      // Vertical 1G circles
      drawRing(ring1G_XZ, 'rgba(20, 184, 166, 0.18)', true);
      drawRing(ring1G_YZ, 'rgba(245, 158, 11, 0.18)', true);

      // Label 1G ring on ground plane
      const label1G = project3DTo2D({ x: STANDARD_GRAVITY, y: 0, z: 0 }, camera);
      ctx.font = '9px monospace';
      ctx.fillStyle = 'rgba(6, 182, 212, 0.65)';
      ctx.fillText('1G (9.8m/s²)', label1G.x + 4, label1G.y + 3);
    }

    // 5. Draw Coordinate Axes (-20 to +20 m/s²)
    const AXIS_MAX = 20;

    const drawAxis = (
      dir: 'X' | 'Y' | 'Z',
      label: string,
      color: string,
      posEnd: Point3D,
      negEnd: Point3D
    ) => {
      const pZero = project3DTo2D({ x: 0, y: 0, z: 0 }, camera);
      const pPos = project3DTo2D(posEnd, camera);
      const pNeg = project3DTo2D(negEnd, camera);

      // Negative axis (dashed faint)
      ctx.strokeStyle = 'rgba(255, 255, 255, 0.12)';
      ctx.lineWidth = 1;
      ctx.setLineDash([2, 3]);
      ctx.beginPath();
      ctx.moveTo(pZero.x, pZero.y);
      ctx.lineTo(pNeg.x, pNeg.y);
      ctx.stroke();
      ctx.setLineDash([]);

      // Positive axis (solid colored line)
      ctx.strokeStyle = color;
      ctx.lineWidth = 1.75;
      ctx.beginPath();
      ctx.moveTo(pZero.x, pZero.y);
      ctx.lineTo(pPos.x, pPos.y);
      ctx.stroke();

      // Axis endpoint node
      ctx.fillStyle = color;
      ctx.beginPath();
      ctx.arc(pPos.x, pPos.y, 2.5, 0, Math.PI * 2);
      ctx.fill();

      // Axis label
      ctx.font = '10px ui-monospace, monospace';
      ctx.fillStyle = color;
      ctx.fillText(label, pPos.x + 6, pPos.y + 3);
    };

    // +X (Lateral - Cyan)
    drawAxis(
      'X',
      '+X Lateral',
      '#06b6d4',
      { x: AXIS_MAX, y: 0, z: 0 },
      { x: -AXIS_MAX, y: 0, z: 0 }
    );
    // +Y (Longitudinal - Teal)
    drawAxis(
      'Y',
      '+Y Longit.',
      '#14b8a6',
      { x: 0, y: AXIS_MAX, z: 0 },
      { x: 0, y: -AXIS_MAX, z: 0 }
    );
    // +Z (Vertical / Normal - Amber)
    drawAxis(
      'Z',
      '+Z Normal',
      '#f59e0b',
      { x: 0, y: 0, z: AXIS_MAX },
      { x: 0, y: 0, z: -AXIS_MAX }
    );

    // 6. Draw 3D Motion Trajectory Trail
    if (showTrail && trailRef.current.length > 1) {
      const trail = trailRef.current;
      for (let i = 1; i < trail.length; i++) {
        const pPrev = project3DTo2D(trail[i - 1], camera);
        const pCurr = project3DTo2D(trail[i], camera);
        const progress = i / trail.length; // 0 (oldest) to 1 (newest)
        const alpha = Math.pow(progress, 1.8) * 0.75;

        ctx.strokeStyle = `rgba(6, 182, 212, ${alpha})`;
        ctx.lineWidth = 1.2 + progress * 1.5;
        ctx.beginPath();
        ctx.moveTo(pPrev.x, pPrev.y);
        ctx.lineTo(pCurr.x, pCurr.y);
        ctx.stroke();
      }
    }

    // 7. Vector tip and drop-shadow projections
    const tip3D: Point3D = { x: accelX, y: accelY, z: accelZ };
    const tipProj = project3DTo2D(tip3D, camera);
    const groundProj = project3DTo2D({ x: accelX, y: accelY, z: 0 }, camera);
    const xGroundProj = project3DTo2D({ x: accelX, y: 0, z: 0 }, camera);
    const yGroundProj = project3DTo2D({ x: 0, y: accelY, z: 0 }, camera);

    // Drop line from vector tip to ground plane (Z-height reference)
    ctx.strokeStyle = 'rgba(255, 255, 255, 0.35)';
    ctx.lineWidth = 1;
    ctx.setLineDash([3, 3]);
    ctx.beginPath();
    ctx.moveTo(tipProj.x, tipProj.y);
    ctx.lineTo(groundProj.x, groundProj.y);
    ctx.stroke();

    // Ground projection lines to X and Y axes
    ctx.strokeStyle = 'rgba(6, 182, 212, 0.25)';
    ctx.beginPath();
    ctx.moveTo(groundProj.x, groundProj.y);
    ctx.lineTo(xGroundProj.x, xGroundProj.y);
    ctx.moveTo(groundProj.x, groundProj.y);
    ctx.lineTo(yGroundProj.x, yGroundProj.y);
    ctx.stroke();
    ctx.setLineDash([]);

    // Ground shadow spot at (accelX, accelY, 0)
    ctx.fillStyle = 'rgba(6, 182, 212, 0.3)';
    ctx.beginPath();
    ctx.ellipse(groundProj.x, groundProj.y, 4 * zoom, 2.5 * zoom, 0, 0, Math.PI * 2);
    ctx.fill();

    // 8. Main 3D Acceleration Vector (Origin to Tip)
    const originProj = project3DTo2D({ x: 0, y: 0, z: 0 }, camera);

    // Vector outer glow
    ctx.strokeStyle = gColor;
    ctx.lineWidth = 3.5;
    ctx.shadowColor = gColor;
    ctx.shadowBlur = 8;
    ctx.beginPath();
    ctx.moveTo(originProj.x, originProj.y);
    ctx.lineTo(tipProj.x, tipProj.y);
    ctx.stroke();
    ctx.shadowBlur = 0; // reset shadow

    // Vector inner bright core
    ctx.strokeStyle = '#ffffff';
    ctx.lineWidth = 1.25;
    ctx.beginPath();
    ctx.moveTo(originProj.x, originProj.y);
    ctx.lineTo(tipProj.x, tipProj.y);
    ctx.stroke();

    // Origin sphere
    ctx.fillStyle = 'rgba(255, 255, 255, 0.7)';
    ctx.beginPath();
    ctx.arc(originProj.x, originProj.y, 3, 0, Math.PI * 2);
    ctx.fill();

    // Tip sphere with halo
    ctx.fillStyle = gColor;
    ctx.beginPath();
    ctx.arc(tipProj.x, tipProj.y, 5 * Math.min(1.5, zoom), 0, Math.PI * 2);
    ctx.fill();

    ctx.fillStyle = '#ffffff';
    ctx.beginPath();
    ctx.arc(tipProj.x, tipProj.y, 2, 0, Math.PI * 2);
    ctx.fill();

    // 9. Floating coordinate tooltip near vector tip
    ctx.font = '10px ui-monospace, monospace';
    const tipText = `${magnitude.toFixed(1)} m/s² (${gForce.toFixed(2)}G)`;
    ctx.fillStyle = 'rgba(15, 23, 42, 0.85)';
    const textWidth = ctx.measureText(tipText).width;
    const boxX = tipProj.x + 8;
    const boxY = tipProj.y - 18;

    ctx.fillRect(boxX - 3, boxY - 10, textWidth + 6, 14);
    ctx.strokeStyle = 'rgba(255, 255, 255, 0.15)';
    ctx.strokeRect(boxX - 3, boxY - 10, textWidth + 6, 14);

    ctx.fillStyle = gColor;
    ctx.fillText(tipText, boxX, boxY);
  }, [
    accelX,
    accelY,
    accelZ,
    magnitude,
    gForce,
    gColor,
    yawDeg,
    pitchDeg,
    zoom,
    height,
    showTrail,
    show1GSphere,
    groundGrid,
    ring1G_XY,
    ring1G_XZ,
    ring1G_YZ,
    ring5_XY,
    ring15_XY,
  ]);

  return (
    <div
      ref={containerRef}
      className={`relative w-full rounded-2xl overflow-hidden bg-slate-950/60 border border-white/10 select-none ${className}`}
      style={{ height }}
    >
      {/* 3D Interactive Canvas */}
      <canvas
        ref={canvasRef}
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        onPointerCancel={handlePointerUp}
        onWheel={handleWheel}
        className="w-full h-full block cursor-grab active:cursor-grabbing touch-none"
        title="3D Accelerometer Vector · Drag to rotate, scroll to zoom"
      />

      {/* Top Floating Controls Bar */}
      <div className="absolute top-2.5 left-2.5 right-2.5 flex items-center justify-between pointer-events-none">
        {/* Preset Camera View Buttons */}
        <div className="flex items-center gap-1 bg-black/40 backdrop-blur-md p-1 rounded-xl border border-white/10 pointer-events-auto">
          {(['iso', 'top', 'front', 'side'] as ViewPreset[]).map((preset) => (
            <button
              key={preset}
              onClick={() => handleSelectPreset(preset)}
              className={`px-2 py-0.5 rounded-lg text-[10px] font-mono uppercase font-semibold transition-all ${
                activePreset === preset
                  ? 'bg-cyan-500/25 text-cyan-300 border border-cyan-500/40 shadow-sm'
                  : 'text-slate-400 hover:text-white hover:bg-white/10'
              }`}
            >
              {preset}
            </button>
          ))}
        </div>

        {/* Action Toggles: Orbit, 1G Sphere, Trail, Zoom */}
        <div className="flex items-center gap-1 bg-black/40 backdrop-blur-md p-1 rounded-xl border border-white/10 pointer-events-auto">
          {/* Auto-Rotate Toggle */}
          <button
            onClick={() => setAutoRotate(!autoRotate)}
            title={autoRotate ? 'Pause auto-rotation' : 'Start auto-rotation'}
            className={`p-1 rounded-lg text-xs transition-colors ${
              autoRotate
                ? 'bg-cyan-500/30 text-cyan-300'
                : 'text-slate-400 hover:text-white hover:bg-white/10'
            }`}
          >
            <Orbit className="w-3.5 h-3.5" />
          </button>

          {/* 1G Reference Shell Toggle */}
          <button
            onClick={() => setShow1GSphere(!show1GSphere)}
            title={show1GSphere ? 'Hide 1G Gravity Sphere' : 'Show 1G Gravity Sphere'}
            className={`p-1 rounded-lg text-xs transition-colors ${
              show1GSphere
                ? 'bg-amber-500/20 text-amber-300'
                : 'text-slate-400 hover:text-white hover:bg-white/10'
            }`}
          >
            <Box className="w-3.5 h-3.5" />
          </button>

          {/* 3D Trail Toggle */}
          <button
            onClick={() => {
              if (showTrail) handleClearTrail();
              setShowTrail(!showTrail);
            }}
            title={showTrail ? 'Hide motion trail' : 'Show motion trail'}
            className={`p-1 rounded-lg text-xs transition-colors ${
              showTrail
                ? 'bg-teal-500/20 text-teal-300'
                : 'text-slate-400 hover:text-white hover:bg-white/10'
            }`}
          >
            <Layers className="w-3.5 h-3.5" />
          </button>

          <div className="w-px h-3 bg-white/15 mx-0.5" />

          {/* Zoom controls */}
          <button
            onClick={() => setZoom((z) => clamp(z + 0.2, 0.4, 3.0))}
            title="Zoom In"
            className="p-1 rounded-lg text-slate-400 hover:text-white hover:bg-white/10"
          >
            <ZoomIn className="w-3.5 h-3.5" />
          </button>
          <button
            onClick={() => setZoom((z) => clamp(z - 0.2, 0.4, 3.0))}
            title="Zoom Out"
            className="p-1 rounded-lg text-slate-400 hover:text-white hover:bg-white/10"
          >
            <ZoomOut className="w-3.5 h-3.5" />
          </button>

          {/* Reset Camera */}
          <button
            onClick={handleResetCamera}
            title="Reset 3D View"
            className="p-1 rounded-lg text-slate-400 hover:text-white hover:bg-white/10"
          >
            <RotateCcw className="w-3.5 h-3.5" />
          </button>
        </div>
      </div>

      {/* Bottom Telemetry Readout & Axis Legend HUD */}
      <div className="absolute bottom-2.5 left-2.5 right-2.5 flex items-end justify-between pointer-events-none">
        {/* Axis chips */}
        <div className="flex items-center gap-1.5 flex-wrap">
          <div className="px-2 py-0.5 rounded-lg bg-black/50 backdrop-blur-md border border-cyan-500/30 text-[11px] font-mono flex items-center gap-1.5">
            <span className="w-2 h-2 rounded-full bg-cyan-400 shrink-0" />
            <span className="text-slate-400 font-sans text-[10px]">X:</span>
            <span className="text-cyan-300 font-bold">
              {accelX >= 0 ? `+${accelX.toFixed(2)}` : accelX.toFixed(2)}
            </span>
          </div>

          <div className="px-2 py-0.5 rounded-lg bg-black/50 backdrop-blur-md border border-teal-500/30 text-[11px] font-mono flex items-center gap-1.5">
            <span className="w-2 h-2 rounded-full bg-teal-400 shrink-0" />
            <span className="text-slate-400 font-sans text-[10px]">Y:</span>
            <span className="text-teal-300 font-bold">
              {accelY >= 0 ? `+${accelY.toFixed(2)}` : accelY.toFixed(2)}
            </span>
          </div>

          <div className="px-2 py-0.5 rounded-lg bg-black/50 backdrop-blur-md border border-amber-500/30 text-[11px] font-mono flex items-center gap-1.5">
            <span className="w-2 h-2 rounded-full bg-amber-400 shrink-0" />
            <span className="text-slate-400 font-sans text-[10px]">Z:</span>
            <span className="text-amber-300 font-bold">
              {accelZ >= 0 ? `+${accelZ.toFixed(2)}` : accelZ.toFixed(2)}
            </span>
          </div>
        </div>

        {/* Live Vector G-Force Badge & Hint */}
        <div className="flex flex-col items-end gap-1">
          <div className="px-2.5 py-1 rounded-xl bg-black/60 backdrop-blur-md border border-white/15 flex items-baseline gap-1.5 shadow-lg">
            <span className="text-[10px] uppercase tracking-wider text-slate-400 font-sans font-semibold">
              G-Force:
            </span>
            <span
              className="text-sm font-black font-mono"
              style={{ color: gColor }}
            >
              {gForce.toFixed(2)} G
            </span>
          </div>
          <span className="text-[9px] text-slate-400/80 font-mono tracking-tight hidden sm:inline">
            Drag to orbit · Scroll to zoom
          </span>
        </div>
      </div>
    </div>
  );
};
