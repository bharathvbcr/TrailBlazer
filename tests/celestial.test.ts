import test from 'node:test';
import assert from 'node:assert';
import {
  calculateSolarDay,
  calculateMoonPhase,
  getInstantaneousSunPosition,
  toJulianDate,
  fromJulianDate,
  getCardinal,
} from '../src/utils/celestial.ts';
import {
  convertTrueToDialBearing,
  calculateRelativeTargetAngle,
} from '../src/utils/calculations.ts';

test('toJulianDate and fromJulianDate bidirectional conversion', () => {
  const d = new Date(Date.UTC(2026, 8, 29, 12, 0, 0));
  const jd = toJulianDate(d);
  const back = fromJulianDate(jd);
  assert.strictEqual(Math.abs(back.getTime() - d.getTime()) < 10, true);
});

test('getCardinal produces correct directions across all 16 points', () => {
  assert.strictEqual(getCardinal(0), 'N');
  assert.strictEqual(getCardinal(360), 'N');
  assert.strictEqual(getCardinal(90), 'E');
  assert.strictEqual(getCardinal(180), 'S');
  assert.strictEqual(getCardinal(270), 'W');
  assert.strictEqual(getCardinal(45), 'NE');
  assert.strictEqual(getCardinal(67.5), 'ENE');
  assert.strictEqual(getCardinal(292.5), 'WNW');
});

test('calculateSolarDay calculates realistic sunrise and sunset azimuths and times for mid-latitudes', () => {
  // Test at Equator on Equinox (March 20, 2026)
  const equinox = new Date(Date.UTC(2026, 2, 20, 12, 0, 0));
  const equatorSolar = calculateSolarDay(0, 0, equinox);
  
  assert.strictEqual(equatorSolar.isPolarDay, false);
  assert.strictEqual(equatorSolar.isPolarNight, false);
  assert.ok(equatorSolar.sunriseAzimuth !== null);
  assert.ok(equatorSolar.sunsetAzimuth !== null);
  // On equinox at equator, sunrise azimuth should be very close to 90° (due East) and sunset ~270° (due West)
  assert.strictEqual(Math.abs(equatorSolar.sunriseAzimuth! - 90) < 3.0, true, `Expected ~90, got ${equatorSolar.sunriseAzimuth}`);
  assert.strictEqual(Math.abs(equatorSolar.sunsetAzimuth! - 270) < 3.0, true, `Expected ~270, got ${equatorSolar.sunsetAzimuth}`);
  assert.strictEqual(Math.abs(equatorSolar.daylightDurationMinutes - 720) < 30, true);
});

test('calculateSolarDay respects seasonal changes (Summer vs Winter solstice)', () => {
  // Summer Solstice in Northern Hemisphere (Denver, CO: lat 39.7, lon -104.9)
  const summer = new Date(Date.UTC(2026, 5, 21, 12, 0, 0));
  const summerSolar = calculateSolarDay(39.7392, -104.9903, summer);
  
  // In summer, sunrise is north of east (azimuth < 90°, e.g. ~57-60°) and sunset is north of west (>270°, e.g. ~300-303°)
  assert.ok(summerSolar.sunriseAzimuth! < 70, `Summer sunrise azimuth should be < 70, got ${summerSolar.sunriseAzimuth}`);
  assert.ok(summerSolar.sunsetAzimuth! > 290, `Summer sunset azimuth should be > 290, got ${summerSolar.sunsetAzimuth}`);
  assert.ok(summerSolar.daylightDurationMinutes > 14 * 60, `Summer daylight should be > 14 hours, got ${summerSolar.daylightDurationMinutes / 60}`);

  // Winter Solstice (Dec 21)
  const winter = new Date(Date.UTC(2026, 11, 21, 12, 0, 0));
  const winterSolar = calculateSolarDay(39.7392, -104.9903, winter);
  
  // In winter, sunrise is south of east (azimuth > 90°, e.g. ~120°) and sunset is south of west (<270°, e.g. ~240°)
  assert.ok(winterSolar.sunriseAzimuth! > 110, `Winter sunrise azimuth should be > 110, got ${winterSolar.sunriseAzimuth}`);
  assert.ok(winterSolar.sunsetAzimuth! < 250, `Winter sunset azimuth should be < 250, got ${winterSolar.sunsetAzimuth}`);
  assert.ok(winterSolar.daylightDurationMinutes < 10 * 60, `Winter daylight should be < 10 hours, got ${winterSolar.daylightDurationMinutes / 60}`);
});

test('calculateSolarDay handles Arctic extreme polar day and polar night gracefully without crashes', () => {
  // Tromso, Norway (lat 69.6)
  // Summer Solstice: Midnight sun (Polar Day)
  const polarDay = calculateSolarDay(69.6492, 18.9553, new Date(Date.UTC(2026, 5, 21, 12, 0, 0)));
  assert.strictEqual(polarDay.isPolarDay, true);
  assert.strictEqual(polarDay.sunriseAzimuth, null);
  assert.strictEqual(polarDay.sunsetAzimuth, null);
  assert.strictEqual(polarDay.daylightDurationMinutes, 1440);

  // Winter Solstice: Polar Night
  const polarNight = calculateSolarDay(69.6492, 18.9553, new Date(Date.UTC(2026, 11, 21, 12, 0, 0)));
  assert.strictEqual(polarNight.isPolarNight, true);
  assert.strictEqual(polarNight.sunriseAzimuth, null);
  assert.strictEqual(polarNight.sunsetAzimuth, null);
  assert.strictEqual(polarNight.daylightDurationMinutes, 0);
});

test('calculateMoonPhase calculates valid lunar phase, illumination, and days to full/new moon', () => {
  const moon = calculateMoonPhase(new Date(Date.UTC(2026, 8, 29, 12, 0, 0)), 37.7749, -122.4194);
  assert.ok(moon.phase >= 0 && moon.phase <= 1, `Phase was ${moon.phase}`);
  assert.ok(moon.fraction >= 0 && moon.fraction <= 1, `Fraction was ${moon.fraction}`);
  assert.ok(moon.illuminationPercentage >= 0 && moon.illuminationPercentage <= 100);
  assert.ok(typeof moon.phaseName === 'string' && moon.phaseName.length > 0);
  assert.ok(typeof moon.phaseEmoji === 'string');
  assert.ok(moon.ageDays >= 0 && moon.ageDays <= 29.53);
  assert.ok(moon.daysToNextFullMoon >= 0 && moon.daysToNextFullMoon <= 30);
  assert.ok(moon.daysToNextNewMoon >= 0 && moon.daysToNextNewMoon <= 30);
  assert.ok(moon.stargazing !== undefined);
  assert.ok(['excellent', 'good', 'fair', 'poor'].includes(moon.stargazing.rating));
  assert.ok(moon.moonAzimuth !== null && moon.moonAzimuth >= 0 && moon.moonAzimuth <= 360);
  assert.ok(moon.moonElevation !== null && moon.moonElevation >= -90 && moon.moonElevation <= 90);
});

test('calculateMoonPhase handles missing or invalid coordinates gracefully', () => {
  const moon = calculateMoonPhase(new Date(), null, null);
  assert.strictEqual(moon.moonAzimuth, null);
  assert.strictEqual(moon.moonElevation, null);
  assert.ok(moon.illuminationPercentage >= 0);
});

test('getInstantaneousSunPosition computes bounded azimuth and elevation', () => {
  const pos = getInstantaneousSunPosition(37.7749, -122.4194, new Date());
  assert.ok(pos.azimuth >= 0 && pos.azimuth <= 360, `Azimuth was ${pos.azimuth}`);
  assert.ok(pos.elevation >= -90 && pos.elevation <= 90, `Elevation was ${pos.elevation}`);
});

test('convertTrueToDialBearing correctly handles True vs Magnetic North across declinations', () => {
  // True North mode: dial bearing is identical to true bearing
  assert.strictEqual(convertTrueToDialBearing(90, 'true', 15), 90);
  assert.strictEqual(convertTrueToDialBearing(350, 'true', -20), 350);
  assert.strictEqual(convertTrueToDialBearing(null, 'true', 10), null);

  // Magnetic North mode with East declination (+15°):
  // True North (0°) is at 345° Magnetic (0 - 15 = -15 = 345°)
  assert.strictEqual(convertTrueToDialBearing(0, 'magnetic', 15), 345);
  // True East (90°) is at 75° Magnetic
  assert.strictEqual(convertTrueToDialBearing(90, 'magnetic', 15), 75);

  // Magnetic North mode with West declination (-20°):
  // True North (0°) is at 20° Magnetic (0 - (-20) = 20°)
  assert.strictEqual(convertTrueToDialBearing(0, 'magnetic', -20), 20);
  // True West (270°) is at 290° Magnetic
  assert.strictEqual(convertTrueToDialBearing(270, 'magnetic', -20), 290);
});

test('calculateRelativeTargetAngle is invariant to compass north mode', () => {
  // Target is at 90° True. Device physical heading is 90° True.
  // Physical relative angle MUST be 0° (on course)
  const targetTrue = 90;
  const devTrue = 90;
  assert.strictEqual(calculateRelativeTargetAngle(targetTrue, devTrue), 0);

  // When device turns 30° to the left (heading 60° True):
  // Relative angle to target is +30° (steer 30° right)
  assert.strictEqual(calculateRelativeTargetAngle(targetTrue, 60), 30);

  // In Magnetic mode with +15° declination:
  // targetDial = 75° Mag, deviceMag = 60 - 15 = 45° Mag.
  // Relative angle: 75 - 45 = +30°! Identical physical result!
  const targetMag = convertTrueToDialBearing(targetTrue, 'magnetic', 15)!;
  const devMag = 45;
  assert.strictEqual(calculateRelativeTargetAngle(targetMag, devMag), 30);
});

test('calculateSolarDay handles International Date Line and extreme timezones without NaN', () => {
  // Apia, Samoa (lat -13.8, lon -171.7) near Date Line West
  const samoaDate = new Date(Date.UTC(2026, 9, 15, 12, 0, 0));
  const samoaSolar = calculateSolarDay(-13.8333, -171.7667, samoaDate);
  assert.ok(samoaSolar.sunriseAzimuth !== null && Number.isFinite(samoaSolar.sunriseAzimuth));
  assert.ok(samoaSolar.sunsetAzimuth !== null && Number.isFinite(samoaSolar.sunsetAzimuth));
  assert.ok(samoaSolar.daylightDurationMinutes > 0 && samoaSolar.daylightDurationMinutes <= 1440);

  // Suva, Fiji (lat -18.1, lon 178.4) near Date Line East
  const fijiSolar = calculateSolarDay(-18.1416, 178.4419, samoaDate);
  assert.ok(fijiSolar.sunriseAzimuth !== null && Number.isFinite(fijiSolar.sunriseAzimuth));
  assert.ok(fijiSolar.sunsetAzimuth !== null && Number.isFinite(fijiSolar.sunsetAzimuth));
  assert.ok(fijiSolar.daylightDurationMinutes > 0 && fijiSolar.daylightDurationMinutes <= 1440);
});

test('Stress test: 5,000 celestial calculations across global coordinate space and seasons', () => {
  for (let i = 0; i < 5000; i++) {
    const lat = (Math.random() - 0.5) * 180; // -90 to +90
    const lon = (Math.random() - 0.5) * 360; // -180 to +180
    const month = Math.floor(Math.random() * 12);
    const day = Math.floor(Math.random() * 28) + 1;
    const testDate = new Date(Date.UTC(2026, month, day, 12, 0, 0));

    const solar = calculateSolarDay(lat, lon, testDate);
    assert.strictEqual(typeof solar.daylightDurationMinutes, 'number');
    assert.ok(Number.isFinite(solar.daylightDurationMinutes));
    assert.ok(solar.daylightDurationMinutes >= 0 && solar.daylightDurationMinutes <= 1440);

    const moon = calculateMoonPhase(testDate, lat, lon);
    assert.ok(Number.isFinite(moon.phase));
    assert.ok(moon.phase >= 0 && moon.phase <= 1);
    assert.ok(Number.isFinite(moon.illuminationPercentage));
  }
});
