package app.sabre.wzsabre;

/**
 * Unified alert object used internally before converting to SABRE JSON response.
 */
public class SabreAlert {
    public final String alertId;
    public final String alertSource;
    public final String type;
    public final double lat;
    public final double lon;
    public final double headingDeg;
    public final String streetName;
    public final long reportTs;  // seconds since epoch

    public SabreAlert(String alertId, String alertSource, String type,
                      double lat, double lon, double headingDeg,
                      String streetName, long reportTs) {
        this.alertId = alertId;
        this.alertSource = alertSource;
        this.type = type;
        this.lat = lat;
        this.lon = lon;
        this.headingDeg = headingDeg;
        this.streetName = streetName;
        this.reportTs = reportTs;
    }
}
