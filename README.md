# AeroGlass Sensors Pro

Precision compass, altimeter, barometer, and environmental sensor suite with Material You Liquid Glass design, live weather tendency, AR camera sighting, and variometer.

## Features

- **Material You Liquid Glass Design**: Dynamic translucent glass UI with multiple theme palettes (Cyber Cyan, Aurora Green, Sunset Amber, Cosmic Violet, Night Vision Red, Phosphor Green).
- **Precision Compass & Navigation**: Real-time heading with True North and Magnetic North modes, NATO mils display, bearing crosshairs, and waypoint azimuth tracking.
- **Barometer & Altimeter**: Barometric altitude calculation, MSL pressure calibration (QNH), tare zeroing, and 3-hour pressure trend forecasting.
- **Sensor Telemetry Matrix**: Live GPS tracking, speed with configurable threshold warning alerts, 3-axis accelerometer (G-force), and gyroscope tilt/roll indicators.
- **Glider Audio Variometer**: Acoustic feedback with pitch-modulated climb beeps and sink tone.
- **AR Camera Sighting**: Augmented reality camera overlay with a scrolling azimuth tape, off-screen target hints and a clear camera-unavailable state.
- **Mobile-first UX**: Swipe between tabs, remembered last tab, compact Telemetry view, undoable waypoint deletion, toasts instead of blocking alerts, and a first-run sensor setup (location is only requested once you opt in; a built-in simulator works without any permissions).
- **Real data only**: No placeholder positions, sample tracks, pre-loaded waypoints or invented sensor values. Anything a device can't measure shows as unavailable. Magnetic declination and expected field strength come from the World Magnetic Model; the sun position is computed from your position and clock; the pressure trend is built from readings actually recorded on your device. The built-in simulator is opt-in and clearly labelled.
- **Sensor sources**: Browsers provide orientation, motion and GPS. The Android app additionally bridges the barometer, magnetometer and ambient light sensor. Without a barometer you can enter a pressure reading manually.
- **Night modes**: Night Red and Phosphor Green tint the entire UI to preserve dark adaptation.

## Getting Started

### Prerequisites

- [Node.js](https://nodejs.org/) (v18 or higher)
- npm or pnpm

### Installation

1. Install project dependencies:
   ```bash
   npm install
   ```

2. Start the development server:
   ```bash
   npm run dev
   ```

3. Open your browser and navigate to `http://localhost:3000`.

### Production Build

To compile and bundle the application for production:
```bash
npm run build
```

To preview the production build locally:
```bash
npm run preview
```
