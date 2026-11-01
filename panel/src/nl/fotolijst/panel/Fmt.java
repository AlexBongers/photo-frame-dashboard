package nl.fotolijst.panel;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** Dutch dates, numbers and Home Assistant condition names. No locale data is needed from the OS. */
final class Fmt {
  static final String[] DAYS_LONG = {"zondag", "maandag", "dinsdag", "woensdag", "donderdag", "vrijdag", "zaterdag"};
  static final String[] DAYS_SHORT = {"zo", "ma", "di", "wo", "do", "vr", "za"};
  static final String[] MONTHS = {"januari", "februari", "maart", "april", "mei", "juni", "juli",
      "augustus", "september", "oktober", "november", "december"};

  private Fmt() {}

  static String two(int v) {
    return v < 10 ? "0" + v : String.valueOf(v);
  }

  static String time(long ms) {
    Calendar c = Calendar.getInstance();
    c.setTimeInMillis(ms);
    return two(c.get(Calendar.HOUR_OF_DAY)) + ":" + two(c.get(Calendar.MINUTE));
  }

  static String dayLong(Calendar c) {
    return DAYS_LONG[c.get(Calendar.DAY_OF_WEEK) - 1] + " " + c.get(Calendar.DAY_OF_MONTH) + " "
        + MONTHS[c.get(Calendar.MONTH)];
  }

  /** 12.3456, 1 -> "12,3"; thousands get a dot: 3883.6, 0 -> "3.884". */
  static String num(double v, int digits) {
    boolean neg = v < 0;
    double a = Math.abs(v);
    double scale = Math.pow(10, digits);
    long scaled = Math.round(a * scale);
    long whole = (long) (scaled / scale);
    String frac = "";
    if (digits > 0) {
      long f = scaled - (long) (whole * scale);
      StringBuilder sb = new StringBuilder(String.valueOf(f));
      while (sb.length() < digits) sb.insert(0, '0');
      frac = "," + sb;
    }
    String w = String.valueOf(whole);
    StringBuilder grouped = new StringBuilder();
    for (int i = 0; i < w.length(); i++) {
      if (i > 0 && (w.length() - i) % 3 == 0) grouped.append('.');
      grouped.append(w.charAt(i));
    }
    return (neg && scaled != 0 ? "-" : "") + grouped + frac;
  }

  static String condition(String c) {
    if (c == null) return "";
    switch (c) {
      case "sunny": return "Zonnig";
      case "clear-night": return "Helder";
      case "partlycloudy": return "Half bewolkt";
      case "cloudy": return "Bewolkt";
      case "rainy": return "Regen";
      case "pouring": return "Zware regen";
      case "snowy": return "Sneeuw";
      case "snowy-rainy": return "Sneeuw/regen";
      case "hail": return "Hagel";
      case "fog": return "Mist";
      case "lightning": return "Onweer";
      case "lightning-rainy": return "Onweer met regen";
      case "windy": case "windy-variant": return "Winderig";
      case "exceptional": return "Bijzonder";
      default: return c;
    }
  }

  /** Parses a Home Assistant timestamp ("2026-10-31T15:00:00+01:00", "...Z", fractions allowed). */
  static long parseDateTime(String s) {
    try {
      String t = s.replaceAll("\\.\\d+", "");
      if (t.endsWith("Z")) t = t.substring(0, t.length() - 1) + "+0000";
      else if (t.matches(".*[+-]\\d\\d:\\d\\d$")) t = t.substring(0, t.length() - 3) + t.substring(t.length() - 2);
      SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
      return f.parse(t).getTime();
    } catch (Exception e) {
      return 0;
    }
  }

  /** Local midnight for "2026-11-02". */
  static long parseDate(String s) {
    try {
      SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
      f.setTimeZone(TimeZone.getDefault());
      return f.parse(s).getTime();
    } catch (Exception e) {
      return 0;
    }
  }

  static String utcIso(long ms) {
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
    f.setTimeZone(TimeZone.getTimeZone("UTC"));
    return f.format(ms);
  }

  static long startOfDay(long ms) {
    Calendar c = Calendar.getInstance();
    c.setTimeInMillis(ms);
    c.set(Calendar.HOUR_OF_DAY, 0);
    c.set(Calendar.MINUTE, 0);
    c.set(Calendar.SECOND, 0);
    c.set(Calendar.MILLISECOND, 0);
    return c.getTimeInMillis();
  }

  static long addDays(long dayStart, int days) {
    Calendar c = Calendar.getInstance();
    c.setTimeInMillis(dayStart);
    c.add(Calendar.DAY_OF_MONTH, days);
    return c.getTimeInMillis();
  }
}
