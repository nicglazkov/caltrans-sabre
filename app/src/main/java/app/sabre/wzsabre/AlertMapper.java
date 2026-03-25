package app.sabre.wzsabre;

/**
 * Maps CHP LogType strings and Waze alert types to SABRE protocol alert types.
 */
public class AlertMapper {

    // ── CHP ──────────────────────────────────────────────────────────────────

    public static String fromChpLogType(String logType) {
        if (logType == null) return null;
        String t = logType.toUpperCase();

        // Injury / fatal collisions
        if (t.contains("1179") || t.contains("1141")) return "ACCIDENT_MAJOR";
        if (t.contains("1183"))                         return "ACCIDENT_MAJOR";
        if (t.contains("1144") || t.contains("FATAL")) return "ACCIDENT_MAJOR";
        if (t.contains("SIG ALERT"))                   return "ACCIDENT_MAJOR";

        // Non-injury collision / hit-and-run
        if (t.contains("1182"))   return "ACCIDENT_MINOR";
        if (t.contains("20002"))  return "ACCIDENT_MINOR";

        // Officer on road (directing traffic, assisting construction/maintenance)
        if (t.contains("1184"))  return "POLICE_VISIBLE";   // Provide Traffic Control
        if (t.contains("CZP"))   return "POLICE_VISIBLE";   // Assist with Construction
        if (t.contains("MZP"))   return "POLICE_VISIBLE";   // Assist CT with Maintenance

        // Road hazards (debris, stalled vehicle, wrong-way, vehicle fire, animal)
        if (t.contains("1125"))  return "HAZARD_ON_ROAD_DEBRIS";
        if (t.contains("FIRE"))  return "HAZARD_ON_ROAD_DEBRIS";

        // Road / lane closure
        if (t.contains("CLOSURE")) return "HAZARD_ON_ROAD_CONGESTION";
        if (t.contains("1184"))    return "HAZARD_ON_ROAD_CONGESTION";
        if (t.contains("TADV"))    return "HAZARD_ON_ROAD_CONGESTION";

        // Weather
        if (t.contains("WIND"))  return "HAZARD_WEATHER_WIND";
        if (t.contains("FOG"))   return "HAZARD_WEATHER_FOG";
        if (t.contains("SNOW") || t.contains("ICE") || t.contains("CHAIN"))
                                  return "HAZARD_ON_ROAD_SLIPPERY";
        if (t.contains("1013") || t.contains("WEATHER") || t.contains("ROAD CONDITION"))
                                  return "HAZARD_ON_ROAD_SLIPPERY";

        // Skip administrative / non-driver-relevant types
        if (t.contains("SILVER") || t.contains("MISSING")) return null;
        if (t.contains("ESCORT")) return "HAZARD_ON_ROAD_SLIPPERY";

        // Unknown — return as generic road hazard rather than drop it
        return "HAZARD_ON_ROAD_DEBRIS";
    }

    // ── Waze ─────────────────────────────────────────────────────────────────

    public static String fromWazeType(String type, String subtype) {
        if (type == null) return null;
        String t = type.toUpperCase();
        String s = subtype != null ? subtype.toUpperCase() : "";

        switch (t) {
            case "POLICE":
                return s.contains("HIDDEN") ? "POLICE_HIDDEN" : "POLICE_VISIBLE";

            case "ACCIDENT":
                return s.contains("MAJOR") ? "ACCIDENT_MAJOR" : "ACCIDENT_MINOR";

            case "HAZARD":
                if (s.contains("ICE") || s.contains("SLIPPERY")) return "HAZARD_ON_ROAD_SLIPPERY";
                if (s.contains("POT_HOLE") || s.contains("POTHOLE")) return "HAZARD_ON_ROAD_POT_HOLE";
                if (s.contains("CONGESTION")) return "HAZARD_ON_ROAD_CONGESTION";
                if (s.contains("FOG"))   return "HAZARD_WEATHER_FOG";
                if (s.contains("RAIN"))  return "HAZARD_WEATHER_RAIN";
                if (s.contains("SNOW"))  return "HAZARD_WEATHER_SNOW";
                if (s.contains("WIND"))  return "HAZARD_WEATHER_WIND";
                if (s.contains("STORM")) return "HAZARD_WEATHER_STORM";
                if (s.contains("HAIL"))  return "HAZARD_WEATHER_HAIL";
                return "HAZARD_ON_ROAD_DEBRIS";

            case "JAM":
                return "HAZARD_ON_ROAD_CONGESTION";

            case "ROAD_CLOSED":
                return "HAZARD_ON_ROAD_CONGESTION";

            default:
                return null; // Waze types we don't care about (e.g., WEATHERHAZARD duplicates)
        }
    }
}
