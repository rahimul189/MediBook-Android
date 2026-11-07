package com.example.medibook.airquality;

import android.content.Context;
import android.content.SharedPreferences;

/** Non-personal, lightweight disk cache. Failed fetches never overwrite the last valid response. */
public final class AirQualityRepository {
    private final SharedPreferences cache;
    public AirQualityRepository(Context context) { cache = context.getSharedPreferences("air_quality_cache", Context.MODE_PRIVATE); }
    public AirQualityReport cached() {
        String raw = cache.getString("json", null);
        try { return raw == null ? null : AirQualityParser.parse(raw, cache.getLong("fetched", 0)); }
        catch (AirQualityException invalidCache) { return null; }
    }
    public AirQualityReport fetch() throws AirQualityException {
        String raw = new AirQualityHttpClient().fetch();
        long now = System.currentTimeMillis();
        AirQualityReport report = AirQualityParser.parse(raw, now);
        cache.edit().putString("json", raw).putLong("fetched", now).apply();
        return report;
    }
}
