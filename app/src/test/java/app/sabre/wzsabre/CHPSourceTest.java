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
