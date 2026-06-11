package app.sabre.wzsabre;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

/** Tests CHP XML parsing and alert construction. */
public class CHPSourceTest {

    private static final double CENTER_LAT = 37.7749;
    private static final double CENTER_LON = -122.4194;
    private static final double RADIUS_M   = 50_000;

    // Minimal CHP XML with a single incident inside our radius
    private static final String XML_ONE_INCIDENT =
        "<?xml version=\"1.0\"?><CHP_INCIDENTS>" +
        "<LogTime>\"03/25/2026 10:00 AM\"</LogTime>" +
        "<INCIDENTS Area=\"San Francisco\">" +
        "<Log ID=\"SF-1234\">" +
        "<LogTime>\"03/25/2026 10:00 AM\"</LogTime>" +
        "<LogType>\"1179 INJURY TRAFFIC COLLISION\"</LogType>" +
        "<Location>\"I-80 WB\"</Location>" +
        "<Area>\"San Francisco\"</Area>" +
        "<LATLON>37774567:122419400</LATLON>" +
        "</Log>" +
        "</INCIDENTS>" +
        "</CHP_INCIDENTS>";

    // Incident OUTSIDE radius (~200km away)
    private static final String XML_OUT_OF_RADIUS =
        "<?xml version=\"1.0\"?><CHP_INCIDENTS>" +
        "<INCIDENTS Area=\"Los Angeles\">" +
        "<Log ID=\"LA-001\">" +
        "<LogTime>\"03/25/2026 09:00 AM\"</LogTime>" +
        "<LogType>\"1182 NON-INJURY TC\"</LogType>" +
        "<Location>\"I-405\"</Location>" +
        "<Area>\"Los Angeles\"</Area>" +
        "<LATLON>34052200:118243700</LATLON>" +  // LA, ~560km from SF
        "</Log>" +
        "</INCIDENTS>" +
        "</CHP_INCIDENTS>";

    private static final String XML_INVALID_COORDS =
        "<?xml version=\"1.0\"?><CHP_INCIDENTS>" +
        "<INCIDENTS><Log ID=\"bad-1\">" +
        "<LogType>\"1179\"</LogType>" +
        "<Location>\"Unknown\"</Location><Area>\"CA\"</Area>" +
        "<LATLON>0:0</LATLON>" +   // 0:0 is invalid
        "</Log></INCIDENTS></CHP_INCIDENTS>";

    private static final String XML_MISSING_COORDS =
        "<?xml version=\"1.0\"?><CHP_INCIDENTS>" +
        "<INCIDENTS><Log ID=\"no-loc-1\">" +
        "<LogType>\"1179\"</LogType>" +
        "<Location>\"Unknown\"</Location><Area>\"CA\"</Area>" +
        "</Log></INCIDENTS></CHP_INCIDENTS>";

    // ── parseXml tests ────────────────────────────────────────────────────────

    @Test
    public void parseXml_findsIncidentInRadius() throws Exception {
        CHPSource src = new CHPSource();
        List<SabreAlert> alerts = callParseXml(src, XML_ONE_INCIDENT);
        assertEquals("Should find 1 incident within radius", 1, alerts.size());
    }

    @Test
    public void parseXml_incidentOutsideRadiusExcluded() throws Exception {
        CHPSource src = new CHPSource();
        List<SabreAlert> alerts = callParseXml(src, XML_OUT_OF_RADIUS);
        assertEquals("LA incident should be outside SF 50km radius", 0, alerts.size());
    }

    @Test
    public void parseXml_invalidCoordsExcluded() throws Exception {
        CHPSource src = new CHPSource();
        List<SabreAlert> alerts = callParseXml(src, XML_INVALID_COORDS);
        assertEquals("0:0 coords should be excluded", 0, alerts.size());
    }

    @Test
    public void parseXml_missingCoordsExcluded() throws Exception {
        CHPSource src = new CHPSource();
        List<SabreAlert> alerts = callParseXml(src, XML_MISSING_COORDS);
        assertEquals("Incident without LATLON should be excluded", 0, alerts.size());
    }

    @Test
    public void parseXml_emptyXml_returnsEmpty() throws Exception {
        String empty = "<?xml version=\"1.0\"?><CHP_INCIDENTS></CHP_INCIDENTS>";
        List<SabreAlert> alerts = callParseXml(new CHPSource(), empty);
        assertEquals(0, alerts.size());
    }

    // ── alert field correctness ───────────────────────────────────────────────

    @Test
    public void alert_sourceIsLowercaseChp() throws Exception {
        List<SabreAlert> alerts = callParseXml(new CHPSource(), XML_ONE_INCIDENT);
        assertEquals("alert_source must be 'chp' (lowercase, matches handshake source id)",
                SabreResponseBuilder.SOURCE_CHP, alerts.get(0).alertSource);
    }

    @Test
    public void alert_idHasChpPrefix() throws Exception {
        List<SabreAlert> alerts = callParseXml(new CHPSource(), XML_ONE_INCIDENT);
        assertTrue("alertId should start with 'chp_'",
                alerts.get(0).alertId.startsWith("chp_"));
    }

    @Test
    public void alert_typeIsValidSabreType() throws Exception {
        List<SabreAlert> alerts = callParseXml(new CHPSource(), XML_ONE_INCIDENT);
        assertNotNull("type must not be null", alerts.get(0).type);
        assertFalse("type must not be empty", alerts.get(0).type.isEmpty());
    }

    @Test
    public void alert_coordsNonZero() throws Exception {
        List<SabreAlert> alerts = callParseXml(new CHPSource(), XML_ONE_INCIDENT);
        SabreAlert a = alerts.get(0);
        assertNotEquals("lat must not be 0", 0.0, a.lat, 0.0001);
        assertNotEquals("lon must not be 0", 0.0, a.lon, 0.0001);
        assertFalse("lat must not be NaN",      Double.isNaN(a.lat));
        assertFalse("lon must not be NaN",      Double.isNaN(a.lon));
        assertFalse("lat must not be Infinite", Double.isInfinite(a.lat));
        assertFalse("lon must not be Infinite", Double.isInfinite(a.lon));
    }

    @Test
    public void alert_reportTsFitsInInt() throws Exception {
        List<SabreAlert> alerts = callParseXml(new CHPSource(), XML_ONE_INCIDENT);
        long ts = alerts.get(0).reportTs;
        assertTrue("report_ts must be positive",          ts > 0);
        assertTrue("report_ts must fit in Int (not Long)", ts <= Integer.MAX_VALUE);
    }

    @Test
    public void alert_headingIsDirectionlessSentinel() throws Exception {
        // CHP incidents have no travel direction; they must use the -720 sentinel
        // so HR shows them regardless of which way the driver is heading.
        List<SabreAlert> alerts = callParseXml(new CHPSource(), XML_ONE_INCIDENT);
        assertEquals(SabreResponseBuilder.HEADING_UNKNOWN, alerts.get(0).headingDeg, 0.0);
    }

    @Test
    public void parseLatLon_sfCoords() {
        double[] coords = CHPSource.parseLatLon("37774567:122419400");
        assertEquals(37.774567, coords[0], 0.0001);
        assertEquals(-122.4194, coords[1], 0.0001);
    }

    @Test
    public void parseLatLon_laCoords() {
        double[] coords = CHPSource.parseLatLon("34052200:118243700");
        assertEquals(34.0522, coords[0], 0.0001);
        assertEquals(-118.2437, coords[1], 0.0001);
    }

    @Test
    public void parseLatLon_explicitNegativeLon_staysWest() {
        // If CHP ever starts sending a signed longitude, it must not flip eastward
        double[] coords = CHPSource.parseLatLon("34052200:-118243700");
        assertEquals(-118.2437, coords[1], 0.0001);
    }

    // ── entity references must not truncate text values ──────────────────────

    @Test
    public void parseXml_entityRefInLocation_keepsFullText() throws Exception {
        String xml =
            "<?xml version=\"1.0\"?><State><Log ID=\"E-1\">" +
            "<LogType>\"1179 INJURY TC\"</LogType>" +
            "<Location>\"I-80 WB &amp; Powell St\"</Location>" +
            "<Area>\"San Francisco\"</Area>" +
            "<LATLON>37774567:122419400</LATLON>" +
            "</Log></State>";
        List<SabreAlert> alerts = callParseXml(new CHPSource(), xml);
        assertEquals(1, alerts.size());
        assertEquals("Text around an entity reference must be preserved in full",
                "I-80 WB & Powell St (San Francisco)", alerts.get(0).streetName);
    }

    // ── nested LogDetails must not pollute Log fields (real feed shape) ──────

    @Test
    public void parseXml_realFeedShape_withLogDetails() throws Exception {
        String xml =
            "<?xml version=\"1.0\" ?><State><Center ID = \"SAHB\"><Dispatch ID = \"STCC\">" +
            "<Log ID = \"260609ST0188\">" +
            "<LogTime>\"Jun  9 2026  2:05PM\"</LogTime>" +
            "<LogType>\"1183-Trfc Collision-Unkn Inj\"</LogType>" +
            "<Location>\"I5 N / Mossdale Rd Ofr\"</Location>" +
            "<LocationDesc>\"NB AT\"</LocationDesc>" +
            "<Area>\"Tracy\"</Area>" +
            "<ThomasBrothers>\"\"</ThomasBrothers>" +
            "<LATLON>\"37779552:121314514\"</LATLON>" +
            "<LogDetails><details>" +
            "<DetailTime>\"Jun  9 2026  2:08PM\"</DetailTime>" +
            "<IncidentDetail>\"[1] OVER 6 VEHS TC\"</IncidentDetail>" +
            "</details></LogDetails></Log>" +
            "</Dispatch></Center></State>";
        List<SabreAlert> alerts = new CHPSource().parseXml(xml, 37.78, -121.31, 50_000);
        assertEquals(1, alerts.size());
        SabreAlert a = alerts.get(0);
        assertEquals("chp_260609ST0188", a.alertId);
        assertEquals("ACCIDENT_MAJOR", a.type);   // 1183 = unknown-injury collision
        assertEquals(37.779552, a.lat, 0.0001);
        assertEquals(-121.314514, a.lon, 0.0001);
        assertEquals("I5 N / Mossdale Rd Ofr (Tracy)", a.streetName);
        assertTrue("report_ts must come from LogTime", a.reportTs > 0);
    }

    // ── haversine distance ────────────────────────────────────────────────────

    @Test
    public void haversine_samePoint_isZero() {
        assertEquals(0.0, CHPSource.haversineMeters(37.7749, -122.4194, 37.7749, -122.4194), 0.01);
    }

    @Test
    public void haversine_sfToLa_isAbout560km() {
        double d = CHPSource.haversineMeters(37.7749, -122.4194, 34.0522, -118.2437);
        assertTrue("SF to LA distance should be ~550-570 km", d > 540_000 && d < 580_000);
    }

    private List<SabreAlert> callParseXml(CHPSource src, String xml) throws Exception {
        return src.parseXml(xml, CENTER_LAT, CENTER_LON, RADIUS_M);
    }
}
