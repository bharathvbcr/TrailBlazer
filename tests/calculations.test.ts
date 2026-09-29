import { test, describe } from 'node:test';
import assert from 'node:assert';
import {
  calculateBarometricAltitude,
  calculateQnhFromAltitude,
  convertPressure,
  convertAltitude,
  convertSpeed,
  calculateWaterBoilingPoint,
  calculateSunPosition,
  formatToDMS,
  calculateThreeHourBarometricTrend,
  generateGpxString,
  getCardinalDirection,
  calculateDistanceMeters,
  calculateBearingDegrees,
} from '../src/utils/calculations.ts';

describe('Adversarial and Stress Tests for Calculations', () => {
  test('calculateBarometricAltitude handles NaN, negative, and infinite pressure safely', () => {
    assert.strictEqual(calculateBarometricAltitude(NaN), 0);
    assert.strictEqual(calculateBarometricAltitude(Infinity), 0);
    assert.strictEqual(calculateBarometricAltitude(-100), 0);
    assert.strictEqual(calculateBarometricAltitude(1013.25, NaN), 0);
    assert.strictEqual(calculateBarometricAltitude(1013.25, 0), 0);
  });

  test('calculateQnhFromAltitude handles out-of-bounds and extreme altitudes without producing NaN', () => {
    const qnhExtreme = calculateQnhFromAltitude(1013.25, 50000);
    assert.ok(Number.isFinite(qnhExtreme), `Expected finite number, got ${qnhExtreme}`);
    const qnhNaN = calculateQnhFromAltitude(NaN, 100);
    assert.ok(Number.isFinite(qnhNaN), `Expected finite number, got ${qnhNaN}`);
  });

  test('calculateSunPosition handles poles without NaN', () => {
    const northPole = calculateSunPosition(90, 0, new Date('2026-06-21T12:00:00Z'));
    assert.ok(Number.isFinite(northPole.azimuth), `North pole azimuth should be finite, got ${northPole.azimuth}`);
    assert.ok(Number.isFinite(northPole.elevation), `North pole elevation should be finite, got ${northPole.elevation}`);

    const southPole = calculateSunPosition(-90, 0, new Date('2026-12-21T12:00:00Z'));
    assert.ok(Number.isFinite(southPole.azimuth), `South pole azimuth should be finite, got ${southPole.azimuth}`);
    assert.ok(Number.isFinite(southPole.elevation), `South pole elevation should be finite, got ${southPole.elevation}`);
  });

  test('formatToDMS never outputs NaN', () => {
    const dmsNaN = formatToDMS(NaN, true);
    assert.ok(!dmsNaN.includes('NaN'), `formatToDMS(NaN) returned ${dmsNaN}`);
    const dmsInf = formatToDMS(Infinity, false);
    assert.ok(!dmsInf.includes('NaN') && !dmsInf.includes('Infinity'), `formatToDMS(Infinity) returned ${dmsInf}`);
  });

  test('calculateWaterBoilingPoint never produces NaN or crashes on negative pressure', () => {
    const bp = calculateWaterBoilingPoint(-50);
    assert.ok(Number.isFinite(bp.celsius));
    assert.ok(Number.isFinite(bp.fahrenheit));
    const bpNaN = calculateWaterBoilingPoint(NaN);
    assert.ok(Number.isFinite(bpNaN.celsius));
    assert.ok(Number.isFinite(bpNaN.fahrenheit));
  });

  test('calculateThreeHourBarometricTrend handles adversarial and corrupted history', () => {
    const corruptedHistory = [
      { timestamp: Date.now() - 10000, pressure: NaN },
      { timestamp: NaN, pressure: 1013.25 },
      { timestamp: Date.now() - 3600000, pressure: Infinity },
    ];
    const trend = calculateThreeHourBarometricTrend(corruptedHistory as any, 1013.25);
    assert.ok(Number.isFinite(trend.deltaHpa3h));
    assert.ok(Number.isFinite(trend.ratePerHour));
    assert.ok(!trend.label.includes('NaN'));
  });

  test('generateGpxString escapes XML special characters to prevent corrupted GPX files', () => {
    const gpx = generateGpxString(
      [{ latitude: 37.77, longitude: -122.41, altitude: 100, pressure: 1013, heading: 0, speed: 5, timestamp: 1700000000000 }],
      'Track <script>alert("xss")</script> & "Dangerous"'
    );
    assert.ok(!gpx.includes('<script>'), 'XML must escape < and >');
    assert.ok(gpx.includes('&lt;script&gt;'), 'XML must contain escaped &lt;script&gt;');
  });

  test('Stress test: 10,000 rapid calculations remain performant and strictly finite', () => {
    const start = performance.now();
    for (let i = 0; i < 10000; i++) {
      const alt = calculateBarometricAltitude(900 + (i % 200), 1013.25);
      const dist = calculateDistanceMeters(37.7 + (i % 10) * 0.01, -122.4, 37.8, -122.5);
      const bearing = calculateBearingDegrees(37.7, -122.4, 37.8, -122.5);
      assert.ok(Number.isFinite(alt));
      assert.ok(Number.isFinite(dist));
      assert.ok(Number.isFinite(bearing));
    }
    const duration = performance.now() - start;
    assert.ok(duration < 1000, `Expected 10,000 iterations in < 1000ms, took ${duration}ms`);
  });
});
