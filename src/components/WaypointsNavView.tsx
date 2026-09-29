import React, { useState, useEffect, useRef } from 'react';
import { SensorState, UserPreferences, Waypoint, TrackPoint, TrackSession } from '../types/sensors';
import {
  calculateBearingDegrees,
  calculateDistanceMeters,
  getCardinalDirection,
  convertAltitude,
  generateGpxString,
} from '../utils/calculations';
import { GlassCard } from './GlassCard';
import { Page, PageHeader, Toolbar, CardHeader, SectionLabel } from './Layout';
import { Modal } from './Modal';
import { WaypointMapThumbnail } from './WaypointMapThumbnail';
import { TrackElevationProfileChart } from './TrackElevationProfileChart';
import { TrackSpeedTrendChart } from './TrackSpeedTrendChart';
import {
  MapPin,
  Navigation,
  Plus,
  Trash2,
  Check,
  Compass,
  ArrowUpRight,
  Mountain,
  Download,
  Play,
  Square,
  Copy,
  Clock,
  Footprints,
  Map as MapIcon,
  Gauge,
  Activity,
} from 'lucide-react';

interface Props {
  sensors: SensorState;
  preferences: UserPreferences;
  waypoints: Waypoint[];
  onAddWaypoint: (wp: Omit<Waypoint, 'id' | 'timestamp'>) => void;
  onDeleteWaypoint: (id: string) => void;
  onSelectTarget: (id: string | null) => void;
  onNavigateToCompass: () => void;
  trackSession: TrackSession;
  onToggleTrackRecording: () => void;
  onSetSimulationSpeed?: (speedMs: number) => void;
  onUpdatePreferences?: (prefs: Partial<UserPreferences>) => void;
}

const COLOR_OPTIONS = [
  '#38bdf8', // cyan
  '#34d399', // emerald
  '#fbbf24', // amber
  '#f43f5e', // rose
  '#a855f7', // purple
];

export const WaypointsNavView: React.FC<Props> = ({
  sensors,
  preferences,
  waypoints,
  onAddWaypoint,
  onDeleteWaypoint,
  onSelectTarget,
  onNavigateToCompass,
  trackSession,
  onToggleTrackRecording,
  onSetSimulationSpeed,
  onUpdatePreferences,
}) => {
  const [telemetryTab, setTelemetryTab] = useState<'both' | 'speed' | 'elevation'>('both');
  const [showAddModal, setShowAddModal] = useState(false);
  const [name, setName] = useState('');
  const [color, setColor] = useState(COLOR_OPTIONS[0]);
  const [notes, setNotes] = useState('');
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [quickMarkFeedback, setQuickMarkFeedback] = useState<string | null>(null);

  const targetWaypoint = waypoints.find((w) => w.id === preferences.targetWaypointId) ?? null;

  // Quick Mark: Automatically captures current GPS coordinate & barometric altitude without manual input
  const handleQuickMark = () => {
    if (sensors.latitude === null || sensors.longitude === null) {
      alert('GPS location is unavailable. Please ensure location permissions are granted or simulator is active.');
      return;
    }

    const markNumber = waypoints.length + 1;
    const now = new Date();
    const timeStr = now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    const isRecording = trackSession.isRecording;
    const autoName = isRecording
      ? `Track Mark #${trackSession.points.length || markNumber} (${timeStr})`
      : `Quick Mark #${markNumber} (${timeStr})`;

    const color = isRecording ? '#f43f5e' : '#38bdf8';

    onAddWaypoint({
      name: autoName,
      latitude: Number(sensors.latitude.toFixed(6)),
      longitude: Number(sensors.longitude.toFixed(6)),
      altitude: Number(sensors.barometricAltitude.toFixed(1)),
      color,
      notes: `Captured via Quick Mark during track session • Alt: ${sensors.barometricAltitude.toFixed(1)}m • Press: ${sensors.pressure.toFixed(1)} hPa • Speed: ${((sensors.gpsSpeed ?? 0) * 3.6).toFixed(1)} km/h`,
    });

    setQuickMarkFeedback(`✓ Quick Mark saved: ${sensors.latitude.toFixed(4)}°, ${sensors.longitude.toFixed(4)}° • ${sensors.barometricAltitude.toFixed(0)}m`);
    setTimeout(() => setQuickMarkFeedback(null), 3500);
  };

  const handleCreateCurrent = () => {
    if (sensors.latitude === null || sensors.longitude === null) {
      alert('GPS location unavailable yet.');
      return;
    }
    onAddWaypoint({
      name: name.trim() || `Waypoint ${waypoints.length + 1}`,
      latitude: sensors.latitude,
      longitude: sensors.longitude,
      altitude: sensors.barometricAltitude,
      color,
      notes: notes.trim() || undefined,
    });
    setName('');
    setNotes('');
    setShowAddModal(false);
  };

  const handleCopyCoords = (wp: Waypoint) => {
    const text = `${wp.latitude.toFixed(6)}, ${wp.longitude.toFixed(6)}`;
    navigator.clipboard?.writeText(text);
    setCopiedId(wp.id);
    setTimeout(() => setCopiedId(null), 2000);
  };

  // Export GPX File
  const handleExportGpx = () => {
    if (trackSession.points.length === 0) {
      alert('No recorded track points in current session yet.');
      return;
    }
    const gpxData = generateGpxString(trackSession.points, 'AeroGlass Session Track');
    const blob = new Blob([gpxData], { type: 'application/gpx+xml' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `aeroglass-track-${new Date().toISOString().slice(0, 10)}.gpx`;
    a.click();
    URL.revokeObjectURL(url);
  };

  // Export JSON Telemetry
  const handleExportJson = () => {
    const exportData = {
      exportedAt: new Date().toISOString(),
      waypoints,
      session: trackSession,
    };
    const blob = new Blob([JSON.stringify(exportData, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `aeroglass-telemetry-${new Date().toISOString().slice(0, 10)}.json`;
    a.click();
    URL.revokeObjectURL(url);
  };

  // Elapsed recording time
  const [elapsedSec, setElapsedSec] = useState(0);
  useEffect(() => {
    if (!trackSession.isRecording || !trackSession.startTime) {
      setElapsedSec(0);
      return;
    }
    const interval = setInterval(() => {
      setElapsedSec(Math.floor((Date.now() - trackSession.startTime!) / 1000));
    }, 1000);
    return () => clearInterval(interval);
  }, [trackSession.isRecording, trackSession.startTime]);

  const formatElapsed = (sec: number) => {
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  };

  return (
    <Page>
      <PageHeader
        icon={<MapPin className="w-5 h-5 text-cyan-400" />}
        title="Tracks"
        subtitle="Waypoints & GPX recorder"
        actions={
          <button
            onClick={() => {
              setName(`Mark ${waypoints.length + 1}`);
              setShowAddModal(true);
            }}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-cyan-500 hover:bg-cyan-400 text-slate-950 font-semibold text-xs shadow-md"
          >
            <Plus className="w-4 h-4" />
            <span>Mark Here</span>
          </button>
        }
      />

      {/* Static Map Thumbnail & Vector Radar Visualization */}
      <WaypointMapThumbnail
        sensors={sensors}
        preferences={preferences}
        waypoints={waypoints}
        targetWaypoint={targetWaypoint}
        onSelectTarget={onSelectTarget}
        onNavigateToCompass={onNavigateToCompass}
      />

      {/* Live Track Session Recorder Card */}
      <GlassCard className={`w-full !p-4 transition-all ${trackSession.isRecording ? 'border-rose-500/40 bg-rose-950/20' : ''}`}>
        <CardHeader
          icon={<div className={`w-2.5 h-2.5 rounded-full shrink-0 ${trackSession.isRecording ? 'bg-rose-500 animate-ping' : 'bg-slate-500'}`} />}
          title={trackSession.isRecording ? 'Recording GPX Track' : 'GPX Track Recorder'}
        >
          <div className="flex items-center space-x-1.5">
            {/* Quick Mark button: auto captures GPS coordinate & barometric altitude */}
            <button
              onClick={handleQuickMark}
              className="flex items-center space-x-1 px-3 py-1 rounded-full text-xs font-bold bg-cyan-500/25 hover:bg-cyan-500/35 border border-cyan-400/50 text-cyan-200 shadow-[0_0_12px_rgba(56,189,248,0.25)] transition-all active:scale-95"
              title="Automatically capture current GPS coordinates and barometric altitude as a waypoint"
            >
              <MapPin className="w-3.5 h-3.5 text-cyan-300" />
              <span>Quick Mark</span>
            </button>

            <button
              onClick={onToggleTrackRecording}
              className={`flex items-center space-x-1 px-3 py-1 rounded-full text-xs font-bold transition-all active:scale-95 shadow ${
                trackSession.isRecording
                  ? 'bg-rose-500 hover:bg-rose-400 text-white'
                  : 'bg-emerald-500 hover:bg-emerald-400 text-slate-950'
              }`}
            >
              {trackSession.isRecording ? (
                <>
                  <Square className="w-3 h-3 fill-current" />
                  <span>Stop</span>
                </>
              ) : (
                <>
                  <Play className="w-3 h-3 fill-current" />
                  <span>Start Track</span>
                </>
              )}
            </button>
          </div>
        </CardHeader>

        {/* Quick Mark Status Feedback Banner */}
        {quickMarkFeedback && (
          <div className="mb-2 p-2 rounded-xl bg-cyan-500/20 border border-cyan-400/40 text-cyan-200 text-xs font-mono flex items-center space-x-1.5 animate-pulse">
            <Check className="w-4 h-4 text-emerald-400 flex-shrink-0" />
            <span className="truncate">{quickMarkFeedback}</span>
          </div>
        )}

        {/* Live track metrics */}
        <div className="grid grid-cols-2 min-[380px]:grid-cols-4 gap-2 text-center text-xs font-mono my-2">
          <div className="p-2 rounded-xl bg-white/[0.03] border border-white/[0.05]">
            <span className="text-[11px] text-slate-400 uppercase font-sans block">Duration</span>
            <span className="text-white font-bold">{formatElapsed(elapsedSec)}</span>
          </div>
          <div className="p-2 rounded-xl bg-white/[0.03] border border-white/[0.05]">
            <span className="text-[11px] text-slate-400 uppercase font-sans block">Distance</span>
            <span className="text-cyan-300 font-bold">
              {trackSession.totalDistance >= 1000
                ? `${(trackSession.totalDistance / 1000).toFixed(2)} km`
                : `${Math.round(trackSession.totalDistance)} m`}
            </span>
          </div>
          <div className="p-2 rounded-xl bg-white/[0.03] border border-white/[0.05]">
            <span className="text-[11px] text-slate-400 uppercase font-sans block">Ascent</span>
            <span className="text-emerald-300 font-bold">+{Math.round(trackSession.totalAscent)}m</span>
          </div>
          <div className="p-2 rounded-xl bg-white/[0.03] border border-white/[0.05]">
            <span className="text-[11px] text-slate-400 uppercase font-sans block">Points</span>
            <span className="text-amber-300 font-bold">{trackSession.points.length}</span>
          </div>
        </div>

        {/* Export Buttons */}
        <div className="flex items-center justify-between pt-1 border-t border-white/[0.06] text-xs">
          <span className="text-[11px] text-slate-400">Export formats:</span>
          <div className="flex space-x-1.5">
            <button
              onClick={handleExportGpx}
              disabled={trackSession.points.length === 0}
              className="flex items-center space-x-1 px-2.5 py-1 rounded-lg bg-white/10 hover:bg-white/20 border border-white/15 text-slate-200 text-[11px] font-semibold disabled:opacity-40 transition-all"
            >
              <Download className="w-3 h-3 text-cyan-400" />
              <span>GPX</span>
            </button>
            <button
              onClick={handleExportJson}
              className="flex items-center space-x-1 px-2.5 py-1 rounded-lg bg-white/10 hover:bg-white/20 border border-white/15 text-slate-200 text-[11px] font-semibold transition-all"
            >
              <Download className="w-3 h-3 text-emerald-400" />
              <span>JSON</span>
            </button>
          </div>
        </div>
      </GlassCard>

      {/* Telemetry Chart Selector & Cards */}
      <SectionLabel icon={<Activity className="w-3.5 h-3.5 text-cyan-400" />} hint={
        <div className="flex bg-white/10 rounded-full p-0.5 border border-white/15 text-[11px] font-mono">
          <button
            onClick={() => setTelemetryTab('both')}
            className={`px-2 py-0.5 rounded-full transition-all whitespace-nowrap ${
              telemetryTab === 'both'
                ? 'bg-cyan-500 text-slate-950 font-bold shadow'
                : 'text-slate-300 hover:text-white'
            }`}
          >
            All (2)
          </button>
          <button
            onClick={() => setTelemetryTab('speed')}
            className={`px-2 py-0.5 rounded-full transition-all whitespace-nowrap flex items-center space-x-1 ${
              telemetryTab === 'speed'
                ? 'bg-cyan-500 text-slate-950 font-bold shadow'
                : 'text-slate-300 hover:text-white'
            }`}
          >
            <Gauge className="w-2.5 h-2.5" />
            <span>Speed</span>
          </button>
          <button
            onClick={() => setTelemetryTab('elevation')}
            className={`px-2 py-0.5 rounded-full transition-all whitespace-nowrap flex items-center space-x-1 ${
              telemetryTab === 'elevation'
                ? 'bg-cyan-500 text-slate-950 font-bold shadow'
                : 'text-slate-300 hover:text-white'
            }`}
          >
            <Mountain className="w-2.5 h-2.5" />
            <span>Elevation</span>
          </button>
        </div>
      }>
        Telemetry Charts
      </SectionLabel>

      {/* Real-Time GPS Speed Trends Area Chart (Recharts) */}
      {(telemetryTab === 'both' || telemetryTab === 'speed') && (
        <TrackSpeedTrendChart
          trackSession={trackSession}
          preferences={preferences}
          currentSpeedMs={sensors.gpsSpeed ?? 0}
          sensors={sensors}
          onSetSimulationSpeed={onSetSimulationSpeed}
          onToggleTrackRecording={onToggleTrackRecording}
          onUpdatePreferences={onUpdatePreferences}
        />
      )}

      {/* Real-Time Track Elevation Profile Chart (Recharts) */}
      {(telemetryTab === 'both' || telemetryTab === 'elevation') && (
        <TrackElevationProfileChart
          trackSession={trackSession}
          preferences={preferences}
          currentAltitude={sensors.barometricAltitude}
          sensors={sensors}
          onAddWaypoint={onAddWaypoint}
          onToggleTrackRecording={onToggleTrackRecording}
        />
      )}

      {/* Waypoint List Section */}
      <SectionLabel icon={<MapPin className="w-3.5 h-3.5 text-cyan-400" />} hint="Tap pin to target">
        Saved Waypoints ({waypoints.length})
      </SectionLabel>

      {waypoints.length === 0 ? (
        <GlassCard className="w-full !p-8 text-center">
          <div className="w-12 h-12 rounded-full bg-white/10 flex items-center justify-center mx-auto mb-3 text-cyan-400">
            <MapPin className="w-6 h-6" />
          </div>
          <h3 className="text-sm font-bold text-white mb-1">No Saved Waypoints</h3>
          <p className="text-xs text-slate-400 max-w-xs mx-auto leading-relaxed mb-4">
            Tap "Mark Here" to save your campsite, parked car, or summit. The map and compass will guide you directly back!
          </p>
          <button
            onClick={() => {
              setName('Basecamp');
              setShowAddModal(true);
            }}
            className="px-4 py-2 rounded-xl bg-white/10 hover:bg-white/20 border border-white/20 text-xs font-semibold text-slate-200"
          >
            Mark Current Location
          </button>
        </GlassCard>
      ) : (
        <div className="w-full space-y-2.5">
          {waypoints.map((wp) => {
            const isTarget = preferences.targetWaypointId === wp.id;
            let distMeters: number | null = null;
            let bearingDeg: number | null = null;
            let elevDiff: number | null = null;

            if (sensors.latitude !== null && sensors.longitude !== null) {
              distMeters = calculateDistanceMeters(
                sensors.latitude,
                sensors.longitude,
                wp.latitude,
                wp.longitude
              );
              bearingDeg = calculateBearingDegrees(
                sensors.latitude,
                sensors.longitude,
                wp.latitude,
                wp.longitude
              );
              elevDiff = wp.altitude - sensors.barometricAltitude;
            }

            const altConverted = convertAltitude(wp.altitude, preferences.altitudeUnit);
            const elevDiffConverted = elevDiff !== null ? convertAltitude(elevDiff, preferences.altitudeUnit) : null;

            return (
              <GlassCard
                key={wp.id}
                className={`!p-4 transition-all ${
                  isTarget ? 'border-cyan-500/60 bg-cyan-950/30' : ''
                }`}
              >
                <div className="flex items-start justify-between">
                  <div
                    className="flex items-start space-x-2.5 flex-1 cursor-pointer"
                    onClick={() => onSelectTarget(isTarget ? null : wp.id)}
                  >
                    <div
                      className="w-3.5 h-3.5 rounded-full mt-0.5 shadow-[0_0_8px_currentColor] flex-shrink-0"
                      style={{ backgroundColor: wp.color }}
                    />
                    <div>
                      <div className="flex items-center space-x-2">
                        <span className="text-sm font-bold text-white">{wp.name}</span>
                        {isTarget && (
                          <span className="text-[11px] font-bold text-cyan-300 bg-cyan-500/20 px-2 py-0.5 rounded-full border border-cyan-500/40">
                            TARGET LOCKED
                          </span>
                        )}
                      </div>

                      {wp.notes && (
                        <p className="text-[11px] text-slate-400 mt-0.5">{wp.notes}</p>
                      )}

                      {/* GPS & Altitude telemetry */}
                      <div className="flex items-center space-x-3 text-[11px] text-slate-400 font-mono mt-1">
                        <span
                          onClick={(e) => {
                            e.stopPropagation();
                            handleCopyCoords(wp);
                          }}
                          className="cursor-pointer hover:text-white flex items-center space-x-1"
                          title="Click to copy coordinates"
                        >
                          <span>{wp.latitude.toFixed(4)}°, {wp.longitude.toFixed(4)}°</span>
                          {copiedId === wp.id ? (
                            <Check className="w-3 h-3 text-emerald-400" />
                          ) : (
                            <Copy className="w-2.5 h-2.5 opacity-60" />
                          )}
                        </span>
                        <span>•</span>
                        <span className="flex items-center space-x-0.5">
                          <Mountain className="w-3 h-3 text-cyan-400" />
                          <span>{altConverted.value.toFixed(0)} {altConverted.label}</span>
                        </span>
                      </div>
                    </div>
                  </div>

                  <button
                    onClick={() => onDeleteWaypoint(wp.id)}
                    aria-label="Delete waypoint"
                    className="p-2 -m-1 rounded-lg text-slate-400 hover:text-rose-400 hover:bg-white/10 transition-all ml-2"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                  </button>
                </div>

                {/* Distance & Bearing HUD Bar */}
                {distMeters !== null && bearingDeg !== null && (
                  <div className="mt-3 pt-2.5 border-t border-white/[0.08] flex flex-wrap items-center justify-between gap-x-3 gap-y-2">
                    <div className="flex flex-wrap items-center gap-x-4 gap-y-2 text-xs">
                      <div>
                        <span className="text-[11px] text-slate-400 uppercase block">Distance</span>
                        <span className="font-mono font-bold text-white whitespace-nowrap">
                          {distMeters >= 1000
                            ? `${(distMeters / 1000).toFixed(2)} km`
                            : `${Math.round(distMeters)} m`}
                        </span>
                      </div>

                      <div>
                        <span className="text-[11px] text-slate-400 uppercase block">Bearing</span>
                        <span className="font-mono font-bold text-cyan-400 flex items-center space-x-0.5">
                          <span>{Math.round(bearingDeg).toString().padStart(3, '0')}°</span>
                          <span className="text-[11px] font-sans">({getCardinalDirection(bearingDeg)})</span>
                        </span>
                      </div>

                      {elevDiffConverted !== null && (
                        <div>
                          <span className="text-[11px] text-slate-400 uppercase block">Elev Delta</span>
                          <span className={`font-mono text-xs font-semibold whitespace-nowrap ${elevDiff! >= 0 ? 'text-emerald-400' : 'text-amber-400'}`}>
                            {elevDiff! >= 0 ? `+${elevDiffConverted.value.toFixed(0)}` : elevDiffConverted.value.toFixed(0)} {elevDiffConverted.label}
                          </span>
                        </div>
                      )}
                    </div>

                    <div className="flex items-center space-x-1.5">
                      {isTarget ? (
                        <button
                          onClick={onNavigateToCompass}
                          className="flex items-center space-x-1 px-3 py-1.5 rounded-xl bg-cyan-500 hover:bg-cyan-400 text-slate-950 font-bold text-xs shadow-md transition-all active:scale-95"
                        >
                          <Compass className="w-3.5 h-3.5" />
                          <span>View on Dial</span>
                        </button>
                      ) : (
                        <button
                          onClick={() => {
                            onSelectTarget(wp.id);
                          }}
                          className="flex items-center space-x-1 px-3 py-1.5 rounded-xl bg-white/10 hover:bg-white/20 border border-white/15 text-slate-200 text-xs font-semibold whitespace-nowrap shrink-0"
                        >
                          <MapIcon className="w-3.5 h-3.5 text-cyan-400" />
                          <span>Focus Map</span>
                        </button>
                      )}
                    </div>
                  </div>
                )}
              </GlassCard>
            );
          })}
        </div>
      )}

      {/* Add Waypoint Modal */}
      <Modal isOpen={showAddModal} onClose={() => setShowAddModal(false)} title="Mark Waypoint" size="sm" icon={<MapPin className="w-5 h-5 text-cyan-400" />}>
              <div className="space-y-3 text-xs">
                <div>
                  <label className="block text-slate-300 mb-1">Waypoint Name</label>
                  <input
                    type="text"
                    value={name}
                    placeholder="e.g. Campsite, Summit, Car"
                    onChange={(e) => setName(e.target.value)}
                    className="w-full px-3 py-2 rounded-xl bg-white/10 border border-white/20 text-white placeholder-slate-500 focus:outline-none focus:border-cyan-400 text-xs"
                  />
                </div>

                <div>
                  <label className="block text-slate-300 mb-1">Color Tag</label>
                  <div className="flex space-x-2">
                    {COLOR_OPTIONS.map((c) => (
                      <button
                        key={c}
                        onClick={() => setColor(c)}
                        aria-label={`Color ${c}`}
                        aria-pressed={color === c}
                        className={`w-8 h-8 rounded-full border-2 transition-all ${
                          color === c ? 'border-white scale-110' : 'border-transparent'
                        }`}
                        style={{ backgroundColor: c }}
                      />
                    ))}
                  </div>
                </div>

                <div>
                  <label className="block text-slate-300 mb-1">Notes (Optional)</label>
                  <input
                    type="text"
                    value={notes}
                    placeholder="e.g. Near blue trail marker"
                    onChange={(e) => setNotes(e.target.value)}
                    className="w-full px-3 py-2 rounded-xl bg-white/10 border border-white/20 text-white placeholder-slate-500 focus:outline-none focus:border-cyan-400 text-xs"
                  />
                </div>

                <div className="p-2.5 rounded-xl bg-white/[0.04] border border-white/[0.08] text-[11px] text-slate-400 space-y-0.5 font-mono">
                  <div>Lat: {sensors.latitude?.toFixed(5)}°</div>
                  <div>Lon: {sensors.longitude?.toFixed(5)}°</div>
                  <div>Alt: {sensors.barometricAltitude.toFixed(0)} m</div>
                </div>

                <div className="flex space-x-2 pt-2">
                  <button
                    onClick={() => setShowAddModal(false)}
                    className="flex-1 py-2 rounded-xl bg-white/10 text-slate-300 text-xs font-semibold hover:bg-white/15"
                  >
                    Cancel
                  </button>
                  <button
                    onClick={handleCreateCurrent}
                    className="flex-1 py-2 rounded-xl bg-cyan-500 text-slate-950 text-xs font-bold hover:bg-cyan-400 shadow-md"
                  >
                    Save Waypoint
                  </button>
                </div>
              </div>
      </Modal>
    </Page>
  );
};
