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
    public final long reportTs;       // seconds since epoch
    public final Long confirmTs;      // seconds since epoch, or null if never confirmed
    public final int  confirmCount;   // crowd confirmations (Waze thumbs-up); 0 for CHP/LCS

    public SabreAlert(String alertId, String alertSource, String type,
                      double lat, double lon, double headingDeg,
                      String streetName, long reportTs,
                      Long confirmTs, int confirmCount) {
        this.alertId = alertId;
        this.alertSource = alertSource;
        this.type = type;
        this.lat = lat;
        this.lon = lon;
        this.headingDeg = headingDeg;
        this.streetName = streetName;
        this.reportTs = reportTs;
        this.confirmTs = confirmTs;
        this.confirmCount = confirmCount;
    }

    /** Convenience for sources with no crowd-confirmation data (CHP, LCS). */
    public SabreAlert(String alertId, String alertSource, String type,
                      double lat, double lon, double headingDeg,
                      String streetName, long reportTs) {
        this(alertId, alertSource, type, lat, lon, headingDeg, streetName, reportTs, null, 0);
    }
}
