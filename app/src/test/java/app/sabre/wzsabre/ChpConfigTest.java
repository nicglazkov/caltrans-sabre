package app.sabre.wzsabre;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for ChpConfig category filtering, type overrides,
 * incident age filtering, and LogTime parsing.
 */
public class ChpConfigTest {

    private static final double LAT = 37.7749, LON = -122.4194, R = 50_000;

    // XML templates — incident inside radius SF
    private String xml(String logType, String logTime) {
        return "<?xml version=\"1.0\"?><CHP_INCIDENTS><INCIDENTS Area=\"SF\">" +
               "<Log ID=\"TST-1\">" +
               "<LogTime>\"" + logTime + "\"</LogTime>" +
               "<LogType>\"" + logType + "\"</LogType>" +
               "<Location>\"I-80\"</Location><Area>\"SF\"</Area>" +
               "<LATLON>37774567:122419400</LATLON>" +
               "</Log></INCIDENTS></CHP_INCIDENTS>";
    }

    // Helpers

    private List<SabreAlert> parse(String logType, String logTime, ChpConfig config)
            throws Exception {
        return new CHPSource().parseXml(xml(logType, logTime), LAT, LON, R, config);
    }

    private static ChpConfig allEnabled() {
        // Default ChpConfig has all categories enabled
        return makeConfig(true, true, true, true, true, true, 0);
    }

    private static ChpConfig makeConfig(
            boolean majorAcc, boolean minorAcc,
            boolean police, boolean congestion,
            boolean debris,  boolean weather,
            int maxAgeMinutes) {
        // Build config using load defaults then mutate
        ChpConfig cfg = ChpConfig.loadDefaults();
        cfg.setEnabled(ChpCategory.MAJOR_ACCIDENT, majorAcc);
        cfg.setEnabled(ChpCategory.MINOR_ACCIDENT, minorAcc);
        cfg.setEnabled(ChpCategory.POLICE_ON_ROAD,  police);
        cfg.setEnabled(ChpCategory.CONGESTION,      congestion);
        cfg.setEnabled(ChpCategory.DEBRIS,          debris);
        cfg.setEnabled(ChpCategory.WEATHER,         weather);
        cfg.maxAgeMinutes = maxAgeMinutes;
        return cfg;
    }

    // ── Category enabled/disabled ─────────────────────────────────────────────

    @Test
    public void majorAccident_enabled_isIncluded() throws Exception {
        List<SabreAlert> alerts = parse("1179 INJURY TC", "03/25/2026 10:00 AM", allEnabled());
        assertEquals(1, alerts.size());
        assertEquals("ACCIDENT_MAJOR", alerts.get(0).type);
    }

    @Test
    public void majorAccident_disabled_isDropped() throws Exception {
        ChpConfig cfg = allEnabled();
        cfg.setEnabled(ChpCategory.MAJOR_ACCIDENT, false);
        assertTrue(parse("1179 INJURY TC", "03/25/2026 10:00 AM", cfg).isEmpty());
    }

    @Test
    public void minorAccident_disabled_isDropped() throws Exception {
        ChpConfig cfg = allEnabled();
        cfg.setEnabled(ChpCategory.MINOR_ACCIDENT, false);
        assertTrue(parse("1182 NON-INJURY TC", "03/25/2026 10:00 AM", cfg).isEmpty());
    }

    @Test
    public void police_disabled_isDropped() throws Exception {
        ChpConfig cfg = allEnabled();
        cfg.setEnabled(ChpCategory.POLICE_ON_ROAD, false);
        assertTrue(parse("1184 TRAFFIC CONTROL", "03/25/2026 10:00 AM", cfg).isEmpty());
    }

    @Test
    public void congestion_disabled_isDropped() throws Exception {
        ChpConfig cfg = allEnabled();
        cfg.setEnabled(ChpCategory.CONGESTION, false);
        assertTrue(parse("ROAD CLOSURE", "03/25/2026 10:00 AM", cfg).isEmpty());
    }

    @Test
    public void debris_disabled_isDropped() throws Exception {
        ChpConfig cfg = allEnabled();
        cfg.setEnabled(ChpCategory.DEBRIS, false);
        assertTrue(parse("1125 DEBRIS", "03/25/2026 10:00 AM", cfg).isEmpty());
    }

    @Test
    public void weather_disabled_isDropped() throws Exception {
        ChpConfig cfg = allEnabled();
        cfg.setEnabled(ChpCategory.WEATHER, false);
        assertTrue(parse("FOG WARNING", "03/25/2026 10:00 AM", cfg).isEmpty());
    }

    @Test
    public void allDisabled_returnsEmpty() throws Exception {
        ChpConfig cfg = makeConfig(false, false, false, false, false, false, 0);
        assertTrue(parse("1179 INJURY TC",   "03/25/2026 10:00 AM", cfg).isEmpty());
        assertTrue(parse("1182 NON-INJURY",  "03/25/2026 10:00 AM", cfg).isEmpty());
        assertTrue(parse("1184 TRAFFIC CTL", "03/25/2026 10:00 AM", cfg).isEmpty());
        assertTrue(parse("ROAD CLOSURE",     "03/25/2026 10:00 AM", cfg).isEmpty());
        assertTrue(parse("1125 DEBRIS",      "03/25/2026 10:00 AM", cfg).isEmpty());
        assertTrue(parse("FOG WARNING",      "03/25/2026 10:00 AM", cfg).isEmpty());
    }

    // ── Type overrides ───────────────────────────────────────────────────────

    @Test
    public void typeOverride_majorAccidentShownAsMinor() throws Exception {
        ChpConfig cfg = allEnabled();
        cfg.setTypeOverride(ChpCategory.MAJOR_ACCIDENT, "ACCIDENT_MINOR");
        List<SabreAlert> alerts = parse("1179 INJURY TC", "03/25/2026 10:00 AM", cfg);
        assertEquals(1, alerts.size());
        assertEquals("ACCIDENT_MINOR", alerts.get(0).type);
    }

    @Test
    public void typeOverride_policeShownAsCongestion() throws Exception {
        ChpConfig cfg = allEnabled();
        cfg.setTypeOverride(ChpCategory.POLICE_ON_ROAD, "HAZARD_ON_ROAD_CONGESTION");
        List<SabreAlert> alerts = parse("1184 TRAFFIC CONTROL", "03/25/2026 10:00 AM", cfg);
        assertEquals(1, alerts.size());
        assertEquals("HAZARD_ON_ROAD_CONGESTION", alerts.get(0).type);
    }

    @Test
    public void typeOverride_null_usesDefault() throws Exception {
        ChpConfig cfg = allEnabled();
        cfg.setTypeOverride(ChpCategory.MAJOR_ACCIDENT, null);  // explicit null
        List<SabreAlert> alerts = parse("1179 INJURY TC", "03/25/2026 10:00 AM", cfg);
        assertEquals("ACCIDENT_MAJOR", alerts.get(0).type);
    }

    @Test
    public void weatherCategory_keepNaturalMapping() throws Exception {
        // WEATHER with no override should keep the per-type natural mapping
        ChpConfig cfg = allEnabled();
        List<SabreAlert> fog  = parse("FOG WARNING",       "03/25/2026 10:00 AM", cfg);
        List<SabreAlert> wind = parse("HIGH WIND ADVISORY","03/25/2026 10:00 AM", cfg);
        List<SabreAlert> snow = parse("SNOW",              "03/25/2026 10:00 AM", cfg);
        assertEquals("HAZARD_WEATHER_FOG",      fog.get(0).type);
        assertEquals("HAZARD_WEATHER_WIND",     wind.get(0).type);
        assertEquals("HAZARD_ON_ROAD_SLIPPERY", snow.get(0).type);
    }

    @Test
    public void weatherCategory_overrideToDebris() throws Exception {
        ChpConfig cfg = allEnabled();
        cfg.setTypeOverride(ChpCategory.WEATHER, "HAZARD_ON_ROAD_DEBRIS");
        List<SabreAlert> alerts = parse("FOG WARNING", "03/25/2026 10:00 AM", cfg);
        assertEquals("HAZARD_ON_ROAD_DEBRIS", alerts.get(0).type);
    }

    // ── Incident age filter ───────────────────────────────────────────────────

    @Test
    public void ageFilter_freshIncident_isKept() throws Exception {
        // Time = "now" (use today's date)
        String nowStr = new java.text.SimpleDateFormat("MM/dd/yyyy hh:mm a", java.util.Locale.US)
                .format(new java.util.Date());
        ChpConfig cfg = allEnabled();
        cfg.maxAgeMinutes = 60;
        List<SabreAlert> alerts = parse("1179 INJURY TC", nowStr, cfg);
        assertEquals("Fresh incident should be included", 1, alerts.size());
    }

    @Test
    public void ageFilter_staleIncident_isDropped() throws Exception {
        // 3-hour-old incident with 1-hour max age
        ChpConfig cfg = allEnabled();
        cfg.maxAgeMinutes = 60;
        List<SabreAlert> alerts = parse("1179 INJURY TC", "01/01/2020 08:00 AM", cfg);
        assertTrue("Very old incident should be dropped", alerts.isEmpty());
    }

    @Test
    public void ageFilter_noLimit_keepsOldIncident() throws Exception {
        ChpConfig cfg = allEnabled();
        cfg.maxAgeMinutes = 0;  // 0 = no limit
        List<SabreAlert> alerts = parse("1179 INJURY TC", "01/01/2020 08:00 AM", cfg);
        assertEquals("No limit should keep all incidents", 1, alerts.size());
    }

    @Test
    public void ageFilter_unparseable_logTime_isKept() throws Exception {
        // If we can't parse LogTime, the alert is kept (fail-open)
        ChpConfig cfg = allEnabled();
        cfg.maxAgeMinutes = 30;
        List<SabreAlert> alerts = parse("1179 INJURY TC", "BAD TIME FORMAT", cfg);
        assertEquals("Unparseable logTime should keep the alert (fail-open)", 1, alerts.size());
    }

    @Test
    public void ageFilter_nullConfig_doesNotFilter() throws Exception {
        // null config = no filtering, all kept
        List<SabreAlert> alerts = parse("1179 INJURY TC", "01/01/2020 08:00 AM", null);
        assertEquals("null config should not filter", 1, alerts.size());
    }

    // ── LogTime parsing ───────────────────────────────────────────────────────

    @Test
    public void parseLogTime_12hour() {
        long ts = CHPSource.parseLogTime("03/25/2026 10:30 AM");
        assertTrue("Should parse successfully", ts > 0);
    }

    @Test
    public void parseLogTime_12hourPM() {
        long ts = CHPSource.parseLogTime("03/25/2026 02:15 PM");
        assertTrue(ts > 0);
    }

    @Test
    public void parseLogTime_24hour() {
        long ts = CHPSource.parseLogTime("03/25/2026 14:15");
        assertTrue(ts > 0);
    }

    @Test
    public void parseLogTime_pmIsLaterThanAm_sameDay() {
        long am = CHPSource.parseLogTime("03/25/2026 08:00 AM");
        long pm = CHPSource.parseLogTime("03/25/2026 08:00 PM");
        assertTrue("PM should be 12h later than AM", pm > am);
        assertEquals(12 * 3600L, pm - am);
    }

    @Test
    public void parseLogTime_badFormat_returnsZero() {
        assertEquals(0, CHPSource.parseLogTime("not a date"));
        assertEquals(0, CHPSource.parseLogTime(""));
        assertEquals(0, CHPSource.parseLogTime(null));
    }

    // ── AlertMapper.categoryFor ───────────────────────────────────────────────

    @Test
    public void categoryFor_majorAccidents() {
        assertEquals(ChpCategory.MAJOR_ACCIDENT, AlertMapper.categoryFor("1179 INJURY TC"));
        assertEquals(ChpCategory.MAJOR_ACCIDENT, AlertMapper.categoryFor("1144 FATAL"));
        assertEquals(ChpCategory.MAJOR_ACCIDENT, AlertMapper.categoryFor("SIG ALERT TRAFFIC"));
    }

    @Test
    public void categoryFor_minorAccidents() {
        assertEquals(ChpCategory.MINOR_ACCIDENT, AlertMapper.categoryFor("1182 NON-INJURY TC"));
        assertEquals(ChpCategory.MINOR_ACCIDENT, AlertMapper.categoryFor("20002 HIT AND RUN"));
    }

    @Test
    public void categoryFor_police() {
        assertEquals(ChpCategory.POLICE_ON_ROAD, AlertMapper.categoryFor("1184 TRAFFIC CONTROL"));
        assertEquals(ChpCategory.POLICE_ON_ROAD, AlertMapper.categoryFor("CZP CONSTRUCTION"));
    }

    @Test
    public void categoryFor_congestion() {
        assertEquals(ChpCategory.CONGESTION, AlertMapper.categoryFor("ROAD CLOSURE"));
        assertEquals(ChpCategory.CONGESTION, AlertMapper.categoryFor("TADV TRAFFIC ADVISORY"));
    }

    @Test
    public void categoryFor_weather() {
        assertEquals(ChpCategory.WEATHER, AlertMapper.categoryFor("FOG WARNING"));
        assertEquals(ChpCategory.WEATHER, AlertMapper.categoryFor("HIGH WIND"));
        assertEquals(ChpCategory.WEATHER, AlertMapper.categoryFor("SNOW"));
    }

    @Test
    public void categoryFor_debris() {
        assertEquals(ChpCategory.DEBRIS, AlertMapper.categoryFor("1125 DEBRIS"));
        assertEquals(ChpCategory.DEBRIS, AlertMapper.categoryFor("UNKNOWN TYPE XYZ"));
    }

    @Test
    public void categoryFor_silverAlert_returnsNull() {
        assertNull(AlertMapper.categoryFor("SILVER ALERT"));
        assertNull(AlertMapper.categoryFor("MISSING PERSON"));
        assertNull(AlertMapper.categoryFor(null));
    }

    // ── ChpConfig helpers ─────────────────────────────────────────────────────

    @Test
    public void typeToSpinnerIndex_roundtrip() {
        for (int i = 0; i < ChpConfig.TYPE_VALUES.length; i++) {
            assertEquals(i, ChpConfig.typeToSpinnerIndex(ChpConfig.TYPE_VALUES[i]));
        }
    }

    @Test
    public void ageToSpinnerIndex_roundtrip() {
        for (int i = 0; i < ChpConfig.AGE_VALUES_MINUTES.length; i++) {
            assertEquals(i, ChpConfig.ageToSpinnerIndex(ChpConfig.AGE_VALUES_MINUTES[i]));
        }
    }
}
