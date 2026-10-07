package com.trailblazer.core.astro

import com.trailblazer.core.math.DEG
import com.trailblazer.core.time.CivilDate
import com.trailblazer.core.time.JulianDay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * Golden values from JPL Horizons (DE441), geocentric (500@399), fetched 2026-09-29 with
 * `ssd.jpl.nasa.gov/api/horizons.api?...&CENTER='500@399'&QUANTITIES='1,2,9,20'&ANG_FORMAT='DEG'&EXTRA_PREC='YES'`
 * for COMMAND 199…899 at 00:00 UT. Columns: astrometric RA/Dec (ICRF), apparent RA/Dec of date, APmag, Δ (au).
 */
class PlanetsTest {
    private data class Row(val planet: Planet, val y: Int, val m: Int, val d: Int, val raA: Double, val decA: Double, val raApp: Double, val decApp: Double, val mag: Double, val delta: Double)

    private val rows = listOf(
        Row(Planet.Mercury, 2026, 9, 29, 205.091431992, -12.029688977, 205.444336509, -12.164272895, -0.130, 1.20101542267645),
        Row(Planet.Venus, 2026, 9, 29, 213.035284452, -20.587906402, 213.405193194, -20.713184197, -4.788, 0.35902242673616),
        Row(Planet.Mars, 2026, 9, 29, 122.555285222, 21.075455192, 122.946522267, 20.996774225, 1.088, 1.67903711617809),
        Row(Planet.Jupiter, 2026, 9, 29, 141.476441111, 15.732120885, 141.843945753, 15.617229209, -1.865, 5.94380050974490),
        Row(Planet.Saturn, 2026, 9, 29, 11.498789461, 1.994963391, 11.849729813, 2.144342914, 0.356, 8.43904949917862),
        Row(Planet.Uranus, 2026, 9, 29, 63.276843701, 21.009313389, 63.675833839, 21.078805796, 5.655, 18.9165444313576),
        Row(Planet.Neptune, 2026, 9, 29, 2.891239386, -0.295136013, 3.241143583, -0.143201919, 7.680, 28.8778155466616),
        Row(Planet.Mercury, 2010, 6, 15, 66.784773459, 20.627016379, 66.937918848, 20.650172990, -0.899, 1.18425179283107),
        Row(Planet.Venus, 2010, 6, 15, 123.263445194, 21.978342598, 123.417235930, 21.946800350, -3.982, 1.19255914625684),
        Row(Planet.Mars, 2010, 6, 15, 156.273678185, 11.140623732, 156.414900725, 11.086593626, 1.182, 1.67240198182030),
        Row(Planet.Jupiter, 2010, 6, 15, 1.349055548, -0.733091415, 1.486455109, -0.673265396, -2.340, 4.99593969518298),
        Row(Planet.Saturn, 2010, 6, 15, 178.998610394, 3.022921394, 179.137325243, 2.962950370, 0.911, 9.39636762165777),
        Row(Planet.Uranus, 2010, 6, 15, 0.544412663, -0.578948279, 0.681901072, -0.519128193, 5.921, 20.1856142913128),
        Row(Planet.Neptune, 2010, 6, 15, 330.813922737, -12.432428349, 330.960814611, -12.379338940, 7.742, 29.5736826930168),
        Row(Planet.Mercury, 2040, 3, 1, 325.658017802, -10.869069243, 326.187369450, -10.687771351, 1.674, 0.66543458339023),
        Row(Planet.Venus, 2040, 3, 1, 319.918479951, -16.400019102, 320.465843990, -16.231846348, -3.890, 1.52408689160413),
        Row(Planet.Mars, 2040, 3, 1, 95.419079232, 26.429535069, 96.042381098, 26.408671609, 0.004, 0.96791077305084),
        Row(Planet.Jupiter, 2040, 3, 1, 179.106669212, 2.048304006, 179.622558944, 1.824195145, -2.454, 4.48578471874835),
        Row(Planet.Saturn, 2040, 3, 1, 190.340617462, -1.596250136, 190.857100057, -1.816655538, 0.551, 8.68953038854426),
        Row(Planet.Uranus, 2040, 3, 1, 121.576179342, 20.877258938, 122.163451420, 20.760153523, 5.450, 17.8252064363332),
        Row(Planet.Neptune, 2040, 3, 1, 29.419767864, 10.198393970, 29.946976378, 10.390388808, 7.789, 30.4316464284537),
        Row(Planet.Mercury, 1995, 1, 10, 307.729742619, -20.773003742, 307.654951676, -20.788897266, -0.875, 1.19789097488785),
        Row(Planet.Venus, 1995, 1, 10, 241.196600367, -17.114058891, 241.125036294, -17.099051445, -4.574, 0.64670743249912),
        Row(Planet.Mars, 1995, 1, 10, 155.781636750, 14.167368800, 155.721754823, 14.188774253, -0.605, 0.78570623095105),
        Row(Planet.Jupiter, 1995, 1, 10, 244.848157620, -20.655747813, 244.774370603, -20.642008867, -1.801, 6.05858333787952),
        Row(Planet.Saturn, 1995, 1, 10, 341.141802078, -9.901153767, 341.075864166, -9.926960425, 1.017, 10.2882298059410),
        Row(Planet.Uranus, 1995, 1, 10, 298.166844831, -21.422449651, 298.090665983, -21.434147620, 5.894, 20.6688677601543),
        Row(Planet.Neptune, 1995, 1, 10, 294.706768793, -20.940836833, 294.630575228, -20.950917677, 7.895, 31.1546943282550),
    )

    private fun ms(r: Row) = CivilDate.daysFromCivil(r.y, r.m, r.d) * 86_400_000L

    /** Great-circle separation in arcminutes. */
    private fun sepArcmin(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val c = sin(dec1 * DEG) * sin(dec2 * DEG) + cos(dec1 * DEG) * cos(dec2 * DEG) * cos((ra1 - ra2) * DEG)
        return acos(c.coerceIn(-1.0, 1.0)) / DEG * 60
    }

    /**
     * Measured worst errors against these rows: Mercury–Mars 0.74′, Jupiter 1.95′, Saturn 7.4′, Uranus 1.44′, Neptune 0.65′.
     * JPL quotes errors of this size for the Table 1 elements (Saturn is the worst). Tolerances sit just above them,
     * so a regression in the algorithm fails while the documented model error passes.
     */
    private val toleranceArcmin = mapOf(
        Planet.Mercury to 1.0, Planet.Venus to 1.0, Planet.Mars to 1.0, Planet.Jupiter to 2.5,
        Planet.Saturn to 8.5, Planet.Uranus to 2.0, Planet.Neptune to 1.0,
    )

    @Test
    fun astrometricPositionsMatchHorizons() {
        for (r in rows) {
            val p = Planets.position(r.planet, ms(r))
            val err = sepArcmin(p.astrometric.raDeg, p.astrometric.decDeg, r.raA, r.decA)
            assertTrue(err <= toleranceArcmin.getValue(r.planet), "${r.planet} ${r.y}: ${err}′")
            assertEquals(r.delta, p.earthDistanceAu, 0.015, "${r.planet} ${r.y} distance")
        }
    }

    /**
     * Horizons' apparent-minus-astrometric difference is exactly precession + nutation + aberration (+ light deflection,
     * under 0.01″ away from the Sun), so our apparent place must carry the same error as our astrometric one.
     */
    @Test
    fun apparentPlaceAddsPrecessionNutationAndAberrationCorrectly() {
        for (r in rows) {
            val p = Planets.position(r.planet, ms(r))
            val astro = sepArcmin(p.astrometric.raDeg, p.astrometric.decDeg, r.raA, r.decA)
            val app = sepArcmin(p.apparent.raDeg, p.apparent.decDeg, r.raApp, r.decApp)
            assertTrue(abs(app - astro) < 0.05, "${r.planet} ${r.y}: apparent $app′ vs astrometric $astro′")
        }
    }

    /** Horizons uses Mallama & Hilton (2018); the Almanac formulas agree within 0.25 mag on these rows. */
    @Test
    fun magnitudesMatchHorizons() {
        for (r in rows) {
            val p = Planets.position(r.planet, ms(r))
            assertEquals(r.mag, p.magnitude, 0.3, "${r.planet} ${r.y} magnitude")
        }
    }

    /** Meeus example 21.b: θ Persei, J2000 → 2028 Nov 13.19 TD, with proper motion. */
    @Test
    fun precessionMatchesMeeus21b() {
        val jde = 2462088.69
        val years = (jde - JulianDay.J2000) / 365.25
        val ra0 = (2 + 44.0 / 60 + 11.986 / 3600) * 15 + 0.03425 * years * 15 / 3600
        val dec0 = 49 + 13.0 / 60 + 42.48 / 3600 - 0.0895 * years / 3600
        val (ra, dec) = Precession.fromJ2000(ra0, dec0, jde)
        assertEquals((2 + 46.0 / 60 + 11.331 / 3600) * 15, ra, 0.001 / 3600 * 15 * 5)
        assertEquals(49 + 20.0 / 60 + 54.54 / 3600, dec, 0.05 / 3600)
    }

    @Test
    fun keplerConvergesForEveryEccentricityInUse() {
        for (e in listOf(0.0, 0.0068, 0.0934, 0.2056, 0.25)) {
            var m = -180.0
            while (m <= 180.0) {
                val ecc = Planets.solveKepler(m, e)
                val back = ecc - e / DEG * sin(ecc * DEG)
                assertEquals(m, back, 1e-5, "e=$e M=$m")
                m += 7.5
            }
        }
    }

    /** Outside 1800–2050 the long-range tables take over; results stay finite and ordered. */
    @Test
    fun positionsAreFiniteAcrossTheValidRange() {
        val years = listOf(-2000, 0, 1600, 1799, 1800, 2050, 2051, 2500, 2999)
        for (y in years) for (planet in Planet.entries) {
            val p = Planets.position(planet, CivilDate.daysFromCivil(y, 3, 1) * 86_400_000L)
            val v = listOf(p.apparent.raDeg, p.apparent.decDeg, p.magnitude, p.earthDistanceAu, p.elongationDeg, p.phaseAngleDeg)
            assertTrue(v.all { it.isFinite() }, "$planet $y $v")
            assertTrue(p.apparent.raDeg in 0.0..360.0 && p.apparent.decDeg in -90.0..90.0)
            assertTrue(p.elongationDeg in 0.0..180.0)
        }
    }

    /** Mercury and Venus can never be far from the Sun: 28° and 48° are their greatest elongations. */
    @Test
    fun innerPlanetsStayNearTheSun() {
        var t = CivilDate.daysFromCivil(2020, 1, 1) * 86_400_000L
        val end = CivilDate.daysFromCivil(2032, 1, 1) * 86_400_000L
        while (t < end) {
            assertTrue(Planets.position(Planet.Mercury, t).elongationDeg < 28.5)
            assertTrue(Planets.position(Planet.Venus, t).elongationDeg < 47.9)
            t += 5 * 86_400_000L
        }
    }
}
