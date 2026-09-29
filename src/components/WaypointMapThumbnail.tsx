import React, { useState, useMemo, useEffect, useRef } from 'react';
import { SensorState, UserPreferences, Waypoint } from '../types/sensors';
import { calculateBearingDegrees, calculateDistanceMeters, getCardinalDirection } from '../utils/calculations';
import { GlassCard } from './GlassCard';
import { Compass, ZoomIn, ZoomOut, Map as MapIcon } from 'lucide-react';

interface Props {
  sensors: SensorState;
  preferences: UserPreferences;
  waypoints: Waypoint[];
  targetWaypoint: Waypoint | null;
  onSelectTarget: (id: string | null) => void;
  onNavigateToCompass: () => void;
}

export const WaypointMapThumbnail: React.FC<Props> = ({
  sensors,
  preferences,
  waypoints,
  targetWaypoint,
  onSelectTarget,
  onNavigateToCompass,
}) => {
  const [mapStyle, setMapStyle] = useState<'dark_map' | 'tactical_radar'>('dark_map');
  const [zoomLevel, setZoomLevel] = useState<number>(14); // Web Mercator zoom (11 to 18)
  const [isTileLoaded, setIsTileLoaded] = useState<boolean>(true);
  const [hoveredWaypoint, setHoveredWaypoint] = useState<Waypoint | null>(null);

  const currentLat = sensors.latitude ?? 37.7749;
  const currentLon = sensors.longitude ?? -122.4194;
  const currentHeading = preferences.northMode === 'true' ? sensors.trueHeading : sensors.heading;

  // Compute map center and target stats
  const { targetDistMeters, targetBearingDeg, centerLat, centerLon, autoZoom } = useMemo(() => {
    if (targetWaypoint) {
      const dist = calculateDistanceMeters(
        currentLat,
        currentLon,
        targetWaypoint.latitude,
        targetWaypoint.longitude
      );
      const bearing = calculateBearingDegrees(
        currentLat,
        currentLon,
        targetWaypoint.latitude,
        targetWaypoint.longitude
      );

      // Center midway between current pos and target
      const midLat = (currentLat + targetWaypoint.latitude) / 2;
      const midLon = (currentLon + targetWaypoint.longitude) / 2;

      // Determine appropriate zoom level based on distance
      let idealZoom = 15;
      if (dist > 20000) idealZoom = 10;
      else if (dist > 10000) idealZoom = 11;
      else if (dist > 5000) idealZoom = 12;
      else if (dist > 2500) idealZoom = 13;
      else if (dist > 1000) idealZoom = 14;
      else if (dist > 400) idealZoom = 15;
      else if (dist > 150) idealZoom = 16;
      else idealZoom = 17;

      return {
        targetDistMeters: dist,
        targetBearingDeg: bearing,
        centerLat: midLat,
        centerLon: midLon,
        autoZoom: idealZoom,
      };
    }

    // Default center on current location
    return {
      targetDistMeters: null,
      targetBearingDeg: null,
      centerLat: currentLat,
      centerLon: currentLon,
      autoZoom: 15,
    };
  }, [currentLat, currentLon, targetWaypoint]);

  // Adjust zoom when target changes
  useEffect(() => {
    setZoomLevel(autoZoom);
  }, [autoZoom, targetWaypoint?.id]);

  const containerRef = useRef<HTMLDivElement>(null);
  const [mapWidth, setMapWidth] = useState<number>(360);

  useEffect(() => {
    if (!containerRef.current) return;
    const updateWidth = () => {
      if (containerRef.current) {
        const w = containerRef.current.clientWidth;
        if (w > 0) setMapWidth(w);
      }
    };
    updateWidth();
    window.addEventListener('resize', updateWidth);
    return () => window.removeEventListener('resize', updateWidth);
  }, []);

  // Slippy map tile calculations
  const { tileX, tileY, offsetXPixels, offsetYPixels } = useMemo(() => {
    const n = Math.pow(2, zoomLevel);
    const xExact = ((centerLon + 180) / 360) * n;
    const latRad = (centerLat * Math.PI) / 180;
    const yExact = ((1 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2) * n;

    const baseTileX = Math.floor(xExact);
    const baseTileY = Math.floor(yExact);

    // Pixel offset from tile top-left (each tile is 256x256 px)
    const offX = (xExact - baseTileX) * 256;
    const offY = (yExact - baseTileY) * 256;

    return {
      tileX: baseTileX,
      tileY: baseTileY,
      offsetXPixels: offX,
      offsetYPixels: offY,
    };
  }, [centerLat, centerLon, zoomLevel]);

  // Viewport dimensions for map container
  const MAP_HEIGHT = 190;

  // Convert (lat, lon) to SVG container pixel coordinates relative to center
  const projectCoordToCanvas = (lat: number, lon: number) => {
    const n = Math.pow(2, zoomLevel);
    const xExact = ((lon + 180) / 360) * n;
    const latRad = (lat * Math.PI) / 180;
    const yExact = ((1 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2) * n;

    const centerXExact = ((centerLon + 180) / 360) * n;
    const centerLatRad = (centerLat * Math.PI) / 180;
    const centerYExact = ((1 - Math.log(Math.tan(centerLatRad) + 1 / Math.cos(centerLatRad)) / Math.PI) / 2) * n;

    const pixelDeltaX = (xExact - centerXExact) * 256;
    const pixelDeltaY = (yExact - centerYExact) * 256;

    return {
      x: mapWidth / 2 + pixelDeltaX,
      y: MAP_HEIGHT / 2 + pixelDeltaY,
    };
  };

  const currentPosCanvas = projectCoordToCanvas(currentLat, currentLon);
  const targetPosCanvas = targetWaypoint
    ? projectCoordToCanvas(targetWaypoint.latitude, targetWaypoint.longitude)
    : null;

  // Scale indicator representation (approximate meters per pixel at this latitude and zoom)
  const metersPerPixel = (156543.03392 * Math.cos((centerLat * Math.PI) / 180)) / Math.pow(2, zoomLevel);
  const scaleBarWidthPx = 60;
  const scaleBarMeters = Math.round(scaleBarWidthPx * metersPerPixel);

  return (
    <GlassCard className="w-full !p-0 overflow-hidden border-cyan-500/30 shadow-[0_8px_25px_rgba(0,0,0,0.5)]">
      {/* Map Header Bar */}
      <div className="flex items-center justify-between px-3.5 py-2.5 bg-black/40 border-b border-white/[0.08] backdrop-blur-md">
        <div className="flex items-center space-x-2">
          <MapIcon className="w-4 h-4 text-cyan-400" />
          <span className="text-xs font-bold text-white uppercase tracking-wider">
            Position & Target
          </span>
          {targetWaypoint && (
            <span className="text-[11px] px-1.5 py-0.5 rounded-full bg-cyan-500/20 text-cyan-300 border border-cyan-500/40 font-mono">
              Target Locked
            </span>
          )}
        </div>

        <div className="flex items-center space-x-1.5">
          {/* Style Toggle (Dark Tile Map vs Tactical Radar) */}
          <button
            onClick={() => setMapStyle(mapStyle === 'dark_map' ? 'tactical_radar' : 'dark_map')}
            className={`px-2 py-0.5 rounded-lg text-[11px] font-semibold border transition-all whitespace-nowrap ${
              mapStyle === 'dark_map'
                ? 'bg-cyan-500/20 border-cyan-500/40 text-cyan-300'
                : 'bg-white/10 border-white/15 text-slate-300'
            }`}
            title="Toggle between Carto Dark Tile Map and Tactical Radar Grid"
          >
            {mapStyle === 'dark_map' ? 'Dark Basemap' : 'Tactical Radar'}
          </button>

          {/* Zoom controls */}
          <div className="flex bg-white/10 rounded-lg p-0.5 border border-white/10">
            <button
              onClick={() => setZoomLevel((z) => Math.min(18, z + 1))}
              disabled={zoomLevel >= 18}
              className="p-1 text-slate-300 hover:text-white disabled:opacity-30"
              title="Zoom In"
            >
              <ZoomIn className="w-3 h-3" />
            </button>
            <button
              onClick={() => setZoomLevel((z) => Math.max(9, z - 1))}
              disabled={zoomLevel <= 9}
              className="p-1 text-slate-300 hover:text-white disabled:opacity-30"
              title="Zoom Out"
            >
              <ZoomOut className="w-3 h-3" />
            </button>
          </div>
        </div>
      </div>

      {/* Main Map Viewport Area */}
      <div
        ref={containerRef}
        className="relative w-full h-[190px] bg-[#0c101a] overflow-hidden select-none"
        style={{ height: `${MAP_HEIGHT}px` }}
      >
        {/* No position yet: say so instead of showing a placeholder location */}
        {sensors.latitude === null && (
          <div className="absolute inset-0 z-30 flex flex-col items-center justify-center gap-1 bg-[#0c101a]/85 backdrop-blur-[2px] text-center px-6 animate-fade-in">
            <MapIcon className="w-5 h-5 text-slate-500" />
            <span className="text-xs font-semibold text-slate-300">Waiting for GPS</span>
            <span className="text-[11px] text-slate-500">Your position appears here once location is available.</span>
          </div>
        )}

        {/* Layer 1: Dark Carto / OSM Tiles (if Dark Map mode is enabled) */}
        {mapStyle === 'dark_map' && (
          <div
            className="absolute inset-0 pointer-events-none transition-opacity duration-500"
            style={{
              width: `${mapWidth}px`,
              height: `${MAP_HEIGHT}px`,
            }}
          >
            {/* Extended Tile Grid to cover viewport */}
            {[-1, 0, 1].map((dy) =>
              [-2, -1, 0, 1, 2].map((dx) => {
                const curX = tileX + dx;
                const curY = tileY + dy;
                // CartoDB Dark Matter tile source
                const tileUrl = `https://basemaps.cartocdn.com/rastertiles/dark_all/${zoomLevel}/${curX}/${curY}.png`;
                const posX = mapWidth / 2 - offsetXPixels + dx * 256;
                const posY = MAP_HEIGHT / 2 - offsetYPixels + dy * 256;

                return (
                  <img
                    key={`${curX}-${curY}`}
                    src={tileUrl}
                    alt=""
                    onError={() => setIsTileLoaded(false)}
                    className="absolute max-w-none opacity-85"
                    style={{
                      left: `${posX}px`,
                      top: `${posY}px`,
                      width: '256px',
                      height: '256px',
                    }}
                  />
                );
              })
            )}
          </div>
        )}

        {/* Layer 2: Tactical Radar Topographic Grid & Distance Rings */}
        <div className="absolute inset-0 pointer-events-none">
          {/* Subtle grid pattern */}
          <div className="w-full h-full opacity-15 bg-[radial-gradient(#38bdf8_1px,transparent_1px)] [background-size:24px_24px]" />

          {/* Tactical crosshair axes */}
          <div className="absolute left-1/2 top-0 bottom-0 w-[1px] bg-cyan-400/20" />
          <div className="absolute top-1/2 left-0 right-0 h-[1px] bg-cyan-400/20" />

          {/* Radial radar distance rings centered on current location */}
          <svg className="absolute inset-0 w-full h-full" viewBox={`0 0 ${mapWidth} ${MAP_HEIGHT}`}>
            {/* Concentric rings around user */}
            <circle
              cx={currentPosCanvas.x}
              cy={currentPosCanvas.y}
              r="40"
              fill="none"
              stroke="rgba(56,189,248,0.15)"
              strokeWidth="1"
              strokeDasharray="3 3"
            />
            <circle
              cx={currentPosCanvas.x}
              cy={currentPosCanvas.y}
              r="80"
              fill="none"
              stroke="rgba(56,189,248,0.12)"
              strokeWidth="1"
              strokeDasharray="4 4"
            />
            <circle
              cx={currentPosCanvas.x}
              cy={currentPosCanvas.y}
              r="130"
              fill="none"
              stroke="rgba(56,189,248,0.08)"
              strokeWidth="1"
            />

            {/* Course track line from Current Pos to Target Waypoint */}
            {targetPosCanvas && (
              <g>
                {/* Glow underlay */}
                <line
                  x1={currentPosCanvas.x}
                  y1={currentPosCanvas.y}
                  x2={targetPosCanvas.x}
                  y2={targetPosCanvas.y}
                  stroke="#38bdf8"
                  strokeWidth="3"
                  strokeOpacity="0.3"
                />
                {/* Animated dash line */}
                <line
                  x1={currentPosCanvas.x}
                  y1={currentPosCanvas.y}
                  x2={targetPosCanvas.x}
                  y2={targetPosCanvas.y}
                  stroke="#38bdf8"
                  strokeWidth="1.5"
                  strokeDasharray="5 4"
                />

                {/* Midpoint distance badge */}
                <g
                  transform={`translate(${
                    (currentPosCanvas.x + targetPosCanvas.x) / 2
                  }, ${
                    (currentPosCanvas.y + targetPosCanvas.y) / 2 - 10
                  })`}
                >
                  <rect
                    x="-32"
                    y="-9"
                    width="64"
                    height="18"
                    rx="6"
                    fill="rgba(8,11,20,0.85)"
                    stroke="rgba(56,189,248,0.5)"
                    strokeWidth="1"
                  />
                  <text
                    x="0"
                    y="3"
                    fill="#38bdf8"
                    fontSize="9"
                    fontWeight="bold"
                    fontFamily="monospace"
                    textAnchor="middle"
                  >
                    {targetDistMeters! >= 1000
                      ? `${(targetDistMeters! / 1000).toFixed(1)}km`
                      : `${Math.round(targetDistMeters!)}m`}
                  </text>
                </g>
              </g>
            )}

            {/* Render Other Waypoint Pins */}
            {waypoints.map((wp) => {
              const pos = projectCoordToCanvas(wp.latitude, wp.longitude);
              const isTarget = targetWaypoint?.id === wp.id;

              return (
                <g
                  key={wp.id}
                  transform={`translate(${pos.x}, ${pos.y})`}
                  className="cursor-pointer transition-all hover:scale-125"
                  onClick={() => onSelectTarget(wp.id)}
                  onMouseEnter={() => setHoveredWaypoint(wp)}
                  onMouseLeave={() => setHoveredWaypoint(null)}
                >
                  {/* Waypoint ring and dot */}
                  <circle
                    cx="0"
                    cy="0"
                    r={isTarget ? 9 : 6}
                    fill={wp.color}
                    fillOpacity={isTarget ? 0.35 : 0.2}
                    stroke={wp.color}
                    strokeWidth={isTarget ? 2 : 1}
                  />
                  <circle cx="0" cy="0" r={isTarget ? 4 : 2.5} fill={wp.color} />

                  {/* Waypoint Label */}
                  <text
                    x="0"
                    y={isTarget ? -14 : -10}
                    fill="#ffffff"
                    fontSize="8.5"
                    fontWeight={isTarget ? 'bold' : 'normal'}
                    textAnchor="middle"
                    filter="drop-shadow(0 1px 3px rgba(0,0,0,0.8))"
                  >
                    {wp.name}
                  </text>
                </g>
              );
            })}

            {/* Current Position Marker (Blue beacon + Heading Cone) */}
            <g transform={`translate(${currentPosCanvas.x}, ${currentPosCanvas.y})`}>
              {/* Heading Cone Field of View */}
              <path
                d="M 0,0 L -16,-36 A 40 40 0 0 1 16,-36 Z"
                fill="url(#headingConeGrad)"
                transform={`rotate(${currentHeading})`}
                opacity="0.6"
              />

              <defs>
                <linearGradient id="headingConeGrad" x1="0%" y1="100%" x2="0%" y2="0%">
                  <stop offset="0%" stopColor="#38bdf8" stopOpacity="0.4" />
                  <stop offset="100%" stopColor="#38bdf8" stopOpacity="0.0" />
                </linearGradient>
              </defs>

              {/* Pulsing ring */}
              <circle cx="0" cy="0" r="10" fill="#38bdf8" fillOpacity="0.2" className="animate-ping" />
              <circle cx="0" cy="0" r="6" fill="#38bdf8" stroke="#ffffff" strokeWidth="1.5" />
              <circle cx="0" cy="0" r="2" fill="#ffffff" />
            </g>
          </svg>
        </div>

        {/* Floating Mini Compass Rose (Top Right) */}
        <div className="absolute top-2.5 right-2.5 z-20 pointer-events-none">
          <div className="relative w-8 h-8 rounded-full bg-black/60 backdrop-blur-md border border-white/20 flex items-center justify-center shadow">
            <Compass
              className="w-5 h-5 text-cyan-400 transition-transform duration-100 ease-out"
              style={{ transform: `rotate(${-currentHeading}deg)` }}
            />
            <span className="absolute -top-1.5 text-[11px] font-black text-rose-500 font-mono">N</span>
          </div>
        </div>

        {/* Floating Scale Bar (Bottom Left) */}
        <div className="absolute bottom-2 left-2.5 z-20 pointer-events-none flex flex-col items-start bg-black/60 backdrop-blur-md px-2 py-1 rounded-lg border border-white/10 text-[11px] font-mono text-slate-300">
          <div className="flex items-center space-x-1">
            <div className="h-[2px] bg-cyan-400" style={{ width: `${scaleBarWidthPx}px` }} />
            <span>{scaleBarMeters >= 1000 ? `${(scaleBarMeters / 1000).toFixed(1)} km` : `${scaleBarMeters} m`}</span>
          </div>
        </div>

        {/* Floating Target Telemetry Capsule (Bottom Right) */}
        {targetWaypoint && targetDistMeters !== null && (
          <div className="absolute bottom-2 right-2.5 z-20 flex items-center space-x-2 bg-black/70 backdrop-blur-md px-2.5 py-1 rounded-xl border border-cyan-500/40 text-[11px] font-mono text-cyan-300">
            <span>{Math.round(targetBearingDeg!)}° {getCardinalDirection(targetBearingDeg!)}</span>
            <span>•</span>
            <span className="font-bold text-white">
              {targetDistMeters >= 1000
                ? `${(targetDistMeters / 1000).toFixed(2)} km`
                : `${Math.round(targetDistMeters)} m`}
            </span>
          </div>
        )}
      </div>

      {/* Target Quick Actions Footer */}
      {targetWaypoint ? (
        <div className="flex items-center justify-between px-3.5 py-2.5 bg-cyan-950/20 border-t border-cyan-500/20 text-xs">
          <div className="flex items-center space-x-2">
            <div
              className="w-2.5 h-2.5 rounded-full shadow-[0_0_6px_currentColor]"
              style={{ backgroundColor: targetWaypoint.color }}
            />
            <span className="font-bold text-white">{targetWaypoint.name}</span>
            <span className="text-[11px] text-slate-400 font-mono">
              ({targetWaypoint.latitude.toFixed(4)}°, {targetWaypoint.longitude.toFixed(4)}°)
            </span>
          </div>

          <div className="flex items-center space-x-2">
            <button
              onClick={onNavigateToCompass}
              className="flex items-center space-x-1 px-3 py-1 rounded-xl bg-cyan-500 hover:bg-cyan-400 text-slate-950 text-xs font-bold transition-all shadow active:scale-95"
            >
              <Compass className="w-3.5 h-3.5" />
              <span>Track on Dial</span>
            </button>
            <button
              onClick={() => onSelectTarget(null)}
              className="text-xs text-slate-400 hover:text-white px-1.5 py-0.5 rounded-lg"
            >
              Clear
            </button>
          </div>
        </div>
      ) : (
        <div className="px-3.5 py-2 bg-white/[0.02] border-t border-white/[0.05] text-[11px] text-slate-400 flex items-center justify-between">
          <span>Tap any waypoint below to lock target & illustrate course vector</span>
          <span className="font-mono text-cyan-400">{waypoints.length} markers</span>
        </div>
      )}
    </GlassCard>
  );
};
